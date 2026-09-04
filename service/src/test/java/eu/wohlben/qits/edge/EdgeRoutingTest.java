package eu.wohlben.qits.edge;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edge end to end, against real stub upstreams on ephemeral loopback ports: two environment
 * gateways, two environments' {@code registry} and {@code mirror} applications, and a stand-in idp.
 * Only {@code mirror} is named in {@code qits.edge.auth.anonymous-read-apps}, so the gated and the
 * read-open answer are both observable from one boot.
 *
 * <p><b>Why one class rather than four.</b> A WebSocket upgrade through {@code vertx-http-proxy}
 * only survives the FIRST Quarkus start in a JVM — after a restart it silently degrades to a plain
 * proxied GET, so the handshake fails with nothing logged anywhere. It is a property of the test
 * harness, not of this code, and qits-gateway paid for finding it. A restart happens when a test
 * class needs a different configuration from the one before it, so the cheapest immunity is for
 * every {@code @QuarkusTest} here to share one: one class, one resource, one start. Splitting this
 * file is how the socket test starts failing for no visible reason.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class EdgeRoutingTest {

  @Inject DeploymentActiveSubscriber deployments;

  @Inject EdgeRoutes routes;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  private static EdgeClient client;

  /**
   * Built on first use, not in {@code @BeforeAll}. Quarkus fills {@code RestAssured.port} in from
   * the port the server actually bound, and with {@code quarkus.http.test-port=0} that is not known
   * until it has; a client constructed in {@code @BeforeAll} reads the unset {@code -1}.
   */
  private static EdgeClient client() {
    if (client == null) {
      client = new EdgeClient(RestAssured.port);
    }
    return client;
  }

  @AfterAll
  static void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  @BeforeEach
  void publishEnvironmentFixture() throws Exception {
    clearProjection();
    for (String environment : List.of("dev", "prod")) {
      routes.replace(
          environment,
          "test-environment",
          "test-" + environment,
          Instant.EPOCH,
          EdgeRoutes.Snapshot.ofEndpoints(
              List.of(
                  new EdgeEndpoint(
                      environment,
                      "test-environment",
                      "/",
                      upstream("qits.test.environment-upstreams." + environment)))));
    }
  }

  // --- the door, which serves nothing -----------------------------------------------------------

  @Test
  void theDoorServesNoPathAtAll() {
    // The ruling: every service is on its own name, so the environment's own name routes nothing.
    // These are the paths that worked here before — a wire protocol, a clone URL, an SPA's own XHR,
    // a segment, the login page — and each of them is now a 404 that reaches no upstream.
    activateArtifacts();
    activateCi();
    activateProjects();
    for (String path :
        List.of("/v2/", "/git/x", "/ci/api/runs", "/ci/", "/idp/login", "/anything")) {
      EdgeClient.Answer answer = client().get("dev.example.com", path);
      assertEquals(404, answer.status(), path);
      assertNull(answer.line("upstream"), path + " must reach no upstream");
      assertTrue(answer.body().contains("serves nothing"), answer.body());
    }
  }

  @Test
  void neitherACookieNorAMachineBearerOpensTheDoor() {
    // A credential is not a key to a name that routes nothing. Both are what a caller that has not
    // moved to the service's own name would hold.
    activateCi();
    for (Map<String, String> credential :
        List.of(Map.of("Cookie", "qits-session=" + StubGateways.SESSION), token("dev"))) {
      EdgeClient.Answer answer = client().get("dev.example.com", "/ci/api/runs", credential);
      assertEquals(404, answer.status(), credential.toString());
      assertNull(answer.line("upstream"));
    }
  }

  @Test
  void aWebSocketUpgradeToTheDoorIsRefusedRatherThanProxied() {
    // The terminals moved with everything else. An upgrade here must be answered, not forwarded.
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
                "/terminal",
                null,
                Map.of("Upgrade", "websocket", "Connection", "Upgrade"));
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
  }

  // --- the routing decision ------------------------------------------------------------------

  @Test
  void anApplicationSubdomainReachesThatEnvironmentsApplication() {
    // The WP1 decision in one line: the app label picks the upstream, the env label picks whose.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals(
        "registry-prod",
        client().get("registry.prod.example.com", "/v2/", token("prod")).line("upstream"));
  }

  @Test
  void anUnconfiguredApplicationLabelIsRefusedRatherThanSentToTheGateway() {
    // NOT a fall-through. The name was aimed at a service, and the gateway is the hop that does not
    // authenticate these — a mistyped registry vhost reaching it would be an open door with a typo
    // for a key.
    EdgeClient.Answer answer = client().get("registy.dev.example.com", "/v2/");
    assertEquals(404, answer.status());
    assertTrue(answer.body().contains("registy"), answer.body());
    assertNull(answer.line("upstream"), "it must not have reached any upstream");
  }

  @Test
  void aDeploymentActiveEndpointIsProxiedDirectlyAndItsPrefixHasABoundary() throws Exception {
    clearProjection();
    activateArtifacts();
    activateCi();

    assertEquals(
        "registry-dev",
        client().get("ci.dev.example.com", "/artifacts/api/files", token("dev")).line("upstream"));
    // The route's prefix boundary matters: /artifacts catches a child, never this merely similar
    // word — which nobody declared, so it falls to the service whose name this is.
    assertEquals(
        "mirror-dev",
        client().get("ci.dev.example.com", "/artifacts-old", token("dev")).line("upstream"));
  }

  @Test
  void mainNavigationCarriesEverySlotAndOneOriginPerService() {
    activateArtifacts();
    activateCi();

    EdgeClient.Answer navigation = client().get("dev.example.com", "/main-navigation");
    assertEquals(200, navigation.status());
    assertEquals("no-store", navigation.headers().get("cache-control"));
    JsonObject document = new JsonObject(navigation.body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.example.com", document.getString("origin"));

    JsonObject slots = document.getJsonObject("slots");
    // Every key, empty ones included: a shell iterates the document rather than a second copy of
    // the vocabulary.
    assertEquals(
        List.of(
            "system",
            "platform",
            "project.detail",
            "services.details",
            "daemons.details",
            "libs.details",
            "frontends.details",
            "cli.details",
            "images.details"),
        List.copyOf(slots.fieldNames()));
    assertTrue(slots.getJsonArray("platform").isEmpty(), slots.encode());

    // Position, then label. CI is 2 and Artifacts is 3, so the deployment's order is the one shown.
    assertEquals(
        List.of("CI", "Artifacts"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("label"))
            .toList());
    assertEquals(
        List.of("http://ci.dev.example.com", "http://registry.dev.example.com"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("origin"))
            .toList());
    assertEquals(
        List.of("qits-ci", "qits-artifacts"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("app"))
            .toList());
    // The primary route travels with a hosted entry too: it is what a shell renders an application
    // under until that application is flipped, so nothing leaves the sidebar mid-rollout.
    assertEquals(
        List.of("/ci", "/artifacts"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("path"))
            .toList());
  }

  @Test
  void mainNavigationIsSlotsApplicationsAndNothingElse() {
    // No flat list and no synthesized Home. Every shell reads the tree, and the environment's own
    // door is qits-projects' `system` entry — a deployment fact like every other entry here.
    activateArtifacts();
    activateCi();

    JsonObject document =
        new JsonObject(client().get("dev.example.com", "/main-navigation").body());
    assertEquals(
        List.of("environment", "origin", "slots", "applications"),
        List.copyOf(document.fieldNames()));
  }

  @Test
  void mainNavigationCarriesApiDocsPerApplicationAndSubpathsPerEntry() {
    // Two additions, two shapes. `applications` is per-application metadata — one object per
    // application that published an api-docs path, keyed by name, for the shell page that knows
    // which repository it shows. `subpath` rides each entry and names the view it opens, relative
    // to the scope the shell composes; entries declared before it existed read null.
    activateCi();
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-projects")
                .put("environmentName", "dev")
                .put("browserHost", "projects")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/projects", upstream("qits.edge.apps.registry.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("system", "Overview", 1))
                        .add(
                            placement("services.details", "Api Docs", 6)
                                .put("subpath", "api-docs")))));

    JsonObject document =
        new JsonObject(client().get("dev.example.com", "/main-navigation").body());
    JsonObject applications = document.getJsonObject("applications");
    assertEquals("/ci/q/swagger-ui", applications.getJsonObject("qits-ci").getString("apiDocs"));
    assertNull(applications.getJsonObject("qits-projects"), "no api-docs, no entry");

    JsonObject services =
        document.getJsonObject("slots").getJsonArray("services.details").stream()
            .map(JsonObject.class::cast)
            .filter(entry -> "qits-projects".equals(entry.getString("app")))
            .findFirst()
            .orElseThrow();
    assertEquals("api-docs", services.getString("subpath"));
    JsonObject ci =
        document.getJsonObject("slots").getJsonArray("services.details").stream()
            .map(JsonObject.class::cast)
            .filter(entry -> "qits-ci".equals(entry.getString("app")))
            .findFirst()
            .orElseThrow();
    assertNull(ci.getString("subpath"), "an entry without one opens the application's root");
  }

  @Test
  void oneApplicationHangsSeveralRowsUnderOneHeading() {
    // qits-workspaces is one application in one container publishing TWO rows under the project
    // node: the workspace list and the editor. The claim used to be the slot alone, which refused
    // this whole frame — and refused the spec a hop earlier, so the deployment failed as
    // "deployment spec unreadable" while its build stayed green.
    activateWorkspaces();

    List<JsonObject> project =
        new JsonObject(client().get("dev.example.com", "/main-navigation").body())
            .getJsonObject("slots").getJsonArray("project.detail").stream()
                .map(JsonObject.class::cast)
                .toList();
    assertEquals(
        List.of("Workspaces", "Editor"),
        project.stream().map(entry -> entry.getString("label")).toList(),
        "two rows, in the order the positions asked for");
    assertEquals(
        List.of("qits-workspaces", "qits-workspaces"),
        project.stream().map(entry -> entry.getString("app")).toList(),
        "both of them are the same application, and nothing collapses them");
    // The two rows differ where a shell needs them to: one opens the application's root under the
    // scope, the other the view its subpath names. Everything else — origin, path — is shared,
    // because it is one application.
    assertEquals(
        List.of("http://workspaces.dev.example.com", "http://workspaces.dev.example.com"),
        project.stream().map(entry -> entry.getString("origin")).toList());
    assertNull(project.get(0).getString("subpath"));
    assertEquals("editor", project.get(1).getString("subpath"));
  }

  @Test
  void twoRowsOfOneApplicationAtOnePositionAreATieAndNotARefusal() {
    // A position is the repository's own number, and the document has always broken a tie by label
    // and then by application — two applications naming 1 in one slot is ordinary. So the same tie
    // inside one application is ordinary too: refusing it would be a rule this document does not
    // have, and both rows would still be renderable if it did.
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-workspaces")
                .put("environmentName", "dev")
                .put("browserHost", "workspaces")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/workspaces", upstream("qits.edge.apps.mirror.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("project.detail", "Workspaces", 1))
                        .add(placement("project.detail", "Editor", 1).put("subpath", "editor")))));

    assertEquals(
        List.of("Editor", "Workspaces"),
        new JsonObject(client().get("dev.example.com", "/main-navigation").body())
            .getJsonObject("slots").getJsonArray("project.detail").stream()
                .map(value -> ((JsonObject) value).getString("label"))
                .toList(),
        "the tie is broken by label, as it is between applications");
  }

  @Test
  void theSamePlacementTwiceIsRefusedWholeAndNamesThePair() throws Exception {
    // One row asked for twice. The pair is the key the projection's primary key holds, so this is
    // the refusal that carries a reason before the insert fails without one — and, like every
    // poison frame, it changes no routes at all.
    clearProjection();
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-workspaces")
                .put("environmentName", "dev")
                .put("browserHost", "workspaces")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/workspaces", upstream("qits.edge.apps.mirror.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("project.detail", "Editor", 1))
                        .add(placement("project.detail", "Editor", 2).put("subpath", "editor")))));

    assertTrue(routes.navigation("dev").isEmpty());
    assertNull(routes.serviceHost("dev", "workspaces"));
    assertNull(routes.resolve("dev", "/workspaces/42"), "the poison frame published no routes");

    // The subscriber settles a poison frame with a WARN, so the sentence itself is read where it is
    // written. It names the PAIR: a frame with four project.detail entries says which one is twice.
    String message =
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    routes.replace(
                        "dev",
                        "qits-workspaces",
                        "duplicate-placement",
                        Instant.now(),
                        new EdgeRoutes.Snapshot(
                            List.of(
                                new EdgeEndpoint(
                                    "dev",
                                    "qits-workspaces",
                                    "/workspaces",
                                    upstream("qits.edge.apps.mirror.hosts.dev"))),
                            "workspaces",
                            List.of(
                                new EdgeRoutes.NavigationEntry("project.detail", "Editor", 1),
                                new EdgeRoutes.NavigationEntry(
                                    "project.detail", "Editor", 2, "editor")))))
            .getMessage();
    assertTrue(message.contains("project.detail.Editor"), message);
  }

  @Test
  void anApiDocsPathUnderNoneOfItsOwnRoutesIsSettledWithoutChangingRoutes() throws Exception {
    // The spec parser refuses this shape at the source; the projection restates the rule so a
    // hand-crafted frame stays poison rather than publishing a document nothing serves.
    clearProjection();
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-ci")
                .put("environmentName", "dev")
                .put("apiDocsPath", "/docs/q/swagger-ui")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/ci", upstream("qits.edge.apps.mirror.hosts.dev"))))));

    JsonObject document =
        new JsonObject(client().get("dev.example.com", "/main-navigation").body());
    assertTrue(document.getJsonObject("applications").isEmpty(), document.encode());
    assertNull(routes.resolve("dev", "/ci/api"), "the poison frame published no routes either");
  }

  @Test
  void anOldFramesOneLabelBecomesASystemEntryWithNoHost() {
    // Every frame ever published is replayed on every start, so the shape before hosts existed has
    // to keep meaning what it meant: one global entry, served under its path on the environment's
    // own name, because an old frame named no host and the edge may not invent one.
    deployments.onFrame(
        deployment(
            "qits-workspaces",
            "dev",
            "legacy-workspaces",
            upstream("qits.edge.apps.mirror.hosts.dev"),
            "/workspaces",
            "Workspaces"));

    JsonObject document =
        new JsonObject(client().get("dev.example.com", "/main-navigation").body());
    JsonObject entry = document.getJsonObject("slots").getJsonArray("system").getJsonObject(0);
    assertEquals("Workspaces", entry.getString("label"));
    assertNull(entry.getString("host"));
    assertEquals("http://dev.example.com", entry.getString("origin"));
    // The path is what a shell renders it under while it has no name of its own.
    assertEquals("/workspaces", entry.getString("path"));
  }

  @Test
  void navigationIsServedOnAServiceHostToo() {
    // Every shell renders the same tree, so the document is on every vhost — and it names the
    // environment's origins even when the request itself carried an application's name.
    activateCi();
    JsonObject document =
        new JsonObject(client().get("ci.dev.example.com", "/main-navigation").body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.example.com", document.getString("origin"));
    assertEquals(
        "http://ci.dev.example.com",
        document
            .getJsonObject("slots")
            .getJsonArray("services.details")
            .getJsonObject(0)
            .getString("origin"));
  }

  // --- a service's own name ---------------------------------------------------------------------

  @Test
  void aPublishedHostServesItsOwnServiceAtTheRoot() {
    activateCi();
    // `/` belongs to test-environment in this environment, and it does NOT travel: on a service's
    // own name the catch-all is that service.
    assertEquals(
        "mirror-dev", client().get("ci.dev.example.com", "/", token("dev")).line("upstream"));
    assertEquals(
        "/deep/link", client().get("ci.dev.example.com", "/deep/link", token("dev")).line("uri"));
  }

  @Test
  void anotherApplicationsPrimaryRouteIsPathRoutedOnAServiceHost() {
    // What makes the whole platform same-origin from any host: an SPA on ci.dev.example.com reads
    // /artifacts/api without CORS, because the segment an application is KNOWN by means the same
    // thing on every name.
    activateCi();
    activateArtifacts();
    assertEquals(
        "registry-dev",
        client().get("ci.dev.example.com", "/artifacts/api/files", token("dev")).line("upstream"));
    assertEquals(
        "/artifacts/api/files",
        client().get("ci.dev.example.com", "/artifacts/api/files", token("dev")).line("uri"));
  }

  @Test
  void anotherApplicationsSecondaryRouteStaysWithTheHostsOwnService() {
    // /v2 is a wire protocol several services legitimately answer — the registry and the
    // pull-through mirror both do — and only one of them can own that path in a projection whose
    // paths are unique per environment. Routing it everywhere would send mirror.dev/v2/ at the
    // registry and break every `docker pull` through the mirror. So a secondary route falls through
    // to the service whose name this is, exactly like a path nobody declared.
    activateCi();
    activateArtifacts();
    assertEquals(
        "mirror-dev", client().get("ci.dev.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals("/v2/", client().get("ci.dev.example.com", "/v2/", token("dev")).line("uri"));
    // On its owner's own name it is that service's, which is the only place it exists now.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", token("dev")).line("upstream"));
  }

  @Test
  void aHostASecondApplicationClaimsIsRefusedAndTheFirstKeepsIt() {
    activateCi();
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-impostor")
                .put("environmentName", "dev")
                .put("browserHost", "ci")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/impostor", upstream("qits.test.environment-upstreams.dev"))))));

    assertEquals("qits-ci", routes.serviceHost("dev", "ci").application());
    assertEquals(
        "mirror-dev", client().get("ci.dev.example.com", "/", token("dev")).line("upstream"));
  }

  @Test
  void aHostThatIsAnEnvironmentNameIsRefused() {
    // HostEnvironments reads the first label as an application, so `dev.dev.example.com` would be
    // routable and `dev.example.com` would not.
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-confused")
                .put("environmentName", "dev")
                .put("browserHost", "prod")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/confused", upstream("qits.test.environment-upstreams.dev"))))));

    assertNull(routes.serviceHost("dev", "prod"));
    // The whole frame is poison, so its routes are not activated either: `/confused` still falls to
    // whoever owns the environment's catch-all.
    assertEquals("test-environment", routes.resolve("dev", "/confused").application());
  }

  @Test
  void aPublishedHostThatContradictsAConfiguredVhostIsRefused() {
    // `registry` is a configured application vhost with its own audience and anonymous reads. A
    // deployment publishing that name for a DIFFERENT upstream would silently take those over.
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-impostor")
                .put("environmentName", "dev")
                .put("browserHost", "registry")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/impostor", upstream("qits.test.environment-upstreams.dev"))))));

    assertNull(routes.serviceHost("dev", "registry"));
    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", token("dev")).line("upstream"));
  }

  // --- the default environment, whose door is the apex -------------------------------------------

  @Test
  void aServiceOfTheDefaultEnvironmentIsReachedWithNoEnvironmentLabel() {
    // `prod` is this suite's default environment, so example.com is its door and ci.example.com is
    // its ci service. The long spelling stays a name for the same place.
    activateCi("prod");
    assertEquals(
        "mirror-prod", client().get("ci.example.com", "/", token("prod")).line("upstream"));
    assertEquals(
        "mirror-prod", client().get("ci.prod.example.com", "/", token("prod")).line("upstream"));
    // And a label nobody published is still the default environment's DOOR rather than an
    // app-shaped
    // refusal — which now serves nothing, like the apex it resolves to.
    EdgeClient.Answer unknown = client().get("nosuchapp.example.com", "/anything");
    assertEquals(404, unknown.status());
    assertTrue(unknown.body().contains("serves nothing"), unknown.body());
  }

  @Test
  void theDefaultEnvironmentsNavigationIsWrittenInTheShortForm() {
    activateCi("prod");
    // Both spellings of the environment's own name. A request on the APEX itself resolves the
    // authority from the canonical origin instead, which in this suite is localhost — that arm is
    // EnvironmentAuthorityTest's, where the canonical origin and the host names agree.
    for (String requested : List.of("prod.example.com", "ci.prod.example.com")) {
      JsonObject document = new JsonObject(client().get(requested, "/main-navigation").body());
      assertEquals("prod", document.getString("environment"), requested);
      assertEquals("http://example.com", document.getString("origin"), requested);
      assertEquals(
          "http://ci.example.com",
          document
              .getJsonObject("slots")
              .getJsonArray("services.details")
              .getJsonObject(0)
              .getString("origin"),
          requested);
    }
  }

  @Test
  void theEnvironmentsOwnNameIsADoorOnceTheProjectsHostIsKnown() {
    activateProjects();
    EdgeClient.Answer answer = client().get("dev.example.com", "/");
    assertEquals(302, answer.status());
    assertEquals("http://projects.dev.example.com/", answer.headers().get("location"));
  }

  @Test
  void theDoorHasNowhereToSendAnybodyUntilProjectsPublishesAHost() throws Exception {
    clearProjection();
    assertEquals(404, client().get("dev.example.com", "/").status());
  }

  @Test
  void theImmutableDefaultLeavesTheEdgeOnlyOnAHashedName() {
    activateArtifacts();

    // The SPA document: the mutable pointer naming the hashed bundles, and so the one file whose
    // staleness decides which version of an application a returning browser runs. qits-gateway
    // rewrote this and the edge did not when it replaced it, which is how a green, deployed release
    // stayed invisible for a day.
    assertEquals(
        "no-cache",
        client()
            .get("registry.dev.example.com", "/artifacts/spa/", token("dev"))
            .headers()
            .get("cache-control"));
    // A content-hashed name is the one place immutable is correct — a new build names a new file —
    // so this one keeps the day it was given.
    assertEquals(
        "public, immutable, max-age=86400",
        client()
            .get("registry.dev.example.com", "/artifacts/spa/main-4RS6EA47.js", token("dev"))
            .headers()
            .get("cache-control"));
    // Unhashed and not the document either: a favicon replaced in place would otherwise outlive its
    // own build by a day.
    assertEquals(
        "no-cache",
        client()
            .get("registry.dev.example.com", "/artifacts/spa/favicon.ico", token("dev"))
            .headers()
            .get("cache-control"));
  }

  @Test
  void aCacheHeaderTheUpstreamChoseIsNotOverruled() {
    activateArtifacts();

    // Only the untouched Quarkus default is known to be nobody's decision, and it is the only value
    // the edge may correct. no-store is somebody's decision, and the blanket rewrite this test
    // forbids would WEAKEN it.
    assertEquals(
        "no-store",
        client()
            .get("registry.dev.example.com", "/artifacts/spa/private", token("dev"))
            .headers()
            .get("cache-control"));
  }

  @Test
  void startupRebuildsAnEmptyProjectionFromHistoricalDeploymentsBeforeItBecomesReady()
      throws Exception {
    // A lost edge database is a real recovery path, not an empty development fixture. The
    // eventstream claim ledger may survive it, so the production bootstrap explicitly rewinds its
    // replay-from-epoch consumer and applies every application's latest historical snapshot.
    clearProjection();

    DeploymentProjectionBootstrap[] bootstrap = new DeploymentProjectionBootstrap[1];
    DeploymentProjectionCatchup historicalLog =
        new DeploymentProjectionCatchup() {
          @Override
          public eu.wohlben.qits.eventstream.control.CatchupResult rebuildFromEpoch(
              String consumerId) {
            assertEquals(DeploymentActiveSubscriber.CONSUMER_ID, consumerId);
            deployments.onFrame(
                deployment(
                    "qits-artifacts",
                    "dev",
                    "history-artifacts",
                    upstream("qits.edge.apps.registry.hosts.dev"),
                    "/history-artifacts",
                    "Artifacts"));
            deployments.onFrame(
                deployment(
                    "qits-workspaces",
                    "dev",
                    "history-workspaces",
                    upstream("qits.edge.apps.mirror.hosts.dev"),
                    "/history-workspaces",
                    "Workspaces"));
            assertFalse(
                bootstrap[0].authoritative(),
                "the snapshots must commit before the edge admits their routes");
            return new eu.wohlben.qits.eventstream.control.CatchupResult(
                consumerId,
                eu.wohlben.qits.eventstream.control.CatchupResult.Status.REACHED_HEAD,
                2);
          }

          @Override
          public eu.wohlben.qits.eventstream.control.CatchupResult catchUp(String consumerId) {
            throw new AssertionError("a confirmed head must not need a retry");
          }
        };
    bootstrap[0] =
        new DeploymentProjectionBootstrap(historicalLog, true, java.time.Duration.ofMillis(1));

    bootstrap[0].catchUpUntilReady();

    assertTrue(bootstrap[0].authoritative());
    assertNotNull(routes.resolve("dev", "/history-artifacts/api/files"));
    assertNotNull(routes.resolve("dev", "/history-workspaces/42"));
    assertEquals(
        List.of("Artifacts", "Workspaces"),
        routes.navigation("dev").stream().map(EdgeRoutes.NavigationPlacement::label).toList());
    assertEquals(
        List.of("system", "system"),
        routes.navigation("dev").stream().map(EdgeRoutes.NavigationPlacement::slot).toList());
  }

  private void clearProjection() throws java.sql.SQLException {
    try (java.sql.Connection connection = edgeDataSource.getConnection();
        java.sql.PreparedStatement navigation =
            connection.prepareStatement("delete from edge_navigation_entry");
        java.sql.PreparedStatement endpoints =
            connection.prepareStatement("delete from edge_endpoint");
        java.sql.PreparedStatement snapshots =
            connection.prepareStatement("delete from edge_deployment_snapshot")) {
      navigation.executeUpdate();
      endpoints.executeUpdate();
      snapshots.executeUpdate();
    }
    routes.load(null);
  }

  private static Upstream upstream(String property) {
    return Upstream.parse(ConfigProvider.getConfig().getValue(property, String.class), 8080);
  }

  private static eu.wohlben.qits.eventstream.control.EventFrame deployment(
      String application,
      String environment,
      String eventId,
      Upstream upstream,
      String path,
      String label) {
    return new eu.wohlben.qits.eventstream.control.EventFrame(
        eventId,
        "DeploymentActive",
        Instant.now(),
        new JsonObject()
            .put("applicationName", application)
            .put("environmentName", environment)
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(
                        new JsonObject()
                            .put("path", path)
                            .put("upstreamHost", upstream.host())
                            .put("upstreamPort", upstream.port())
                            .put("navigationLabel", label)
                            .put("navigationPosition", 1)))
            .encode(),
        null,
        null,
        environment);
  }

  @Test
  void anExplicitlyEmptySnapshotRemovesThePredecessorsRoutes() {
    activateArtifacts();
    activateCi();
    deployments.onFrame(
        new eu.wohlben.qits.eventstream.control.EventFrame(
            java.util.UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            new JsonObject()
                .put("applicationName", "qits-artifacts")
                .put("environmentName", "dev")
                .put("endpoints", new io.vertx.core.json.JsonArray())
                .encode(),
            null,
            null,
            "dev"));

    // /artifacts is nobody's route any more, so it stops travelling and falls to ci's own service.
    assertEquals(
        "mirror-dev",
        client().get("ci.dev.example.com", "/artifacts/api/files", token("dev")).line("upstream"));
  }

  /**
   * qits-artifacts as it is published after the flip: a primary route, a wire route, the public
   * name {@code registry} — which is also its CONFIGURED vhost, so the two have to agree — and one
   * placement.
   */
  private void activateArtifacts() {
    Upstream upstream = upstream("qits.edge.apps.registry.hosts.dev");
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-artifacts")
                .put("environmentName", "dev")
                .put("browserHost", "registry")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/artifacts", upstream))
                        .add(endpoint("/v2", upstream)))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("services.details", "Artifacts", 3)))));
  }

  /** A second flipped application, on a stub that names itself differently. */
  private void activateCi() {
    activateCi("dev");
  }

  private void activateCi(String environment) {
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-ci")
                .put("environmentName", environment)
                .put("browserHost", "ci")
                .put("apiDocsPath", "/ci/q/swagger-ui")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/ci", upstream("qits.edge.apps.mirror.hosts." + environment))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("services.details", "CI", 2)))));
  }

  /**
   * qits-workspaces as the editor epic publishes it: one application, one container, TWO rows under
   * the project node — the workspace list at its root and the editor under a subpath.
   */
  private void activateWorkspaces() {
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-workspaces")
                .put("environmentName", "dev")
                .put("browserHost", "workspaces")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/workspaces", upstream("qits.edge.apps.mirror.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("project.detail", "Workspaces", 1))
                        .add(placement("project.detail", "Editor", 2).put("subpath", "editor")))));
  }

  /** The landing service: what makes the environment's own name a door rather than a page. */
  private void activateProjects() {
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-projects")
                .put("environmentName", "dev")
                .put("browserHost", "projects")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/projects", upstream("qits.edge.apps.registry.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray().add(placement("system", "Overview", 1)))));
  }

  private static JsonObject endpoint(String path, Upstream upstream) {
    return new JsonObject()
        .put("path", path)
        .put("upstreamHost", upstream.host())
        .put("upstreamPort", upstream.port());
  }

  private static JsonObject placement(String slot, String label, int position) {
    return new JsonObject().put("slot", slot).put("label", label).put("position", position);
  }

  private static eu.wohlben.qits.eventstream.control.EventFrame frame(JsonObject payload) {
    return new eu.wohlben.qits.eventstream.control.EventFrame(
        java.util.UUID.randomUUID().toString(),
        "DeploymentActive",
        Instant.now(),
        payload.encode(),
        null,
        null,
        null);
  }

  @Test
  void theApexAndAnUnknownHostReachTheDefaultEnvironment() {
    // Nothing is served on any of them, so what the resolution answers is read off the one document
    // the door still writes.
    for (String host : List.of("example.com", "staging.example.com", "127.0.0.1")) {
      assertEquals(
          "prod",
          new JsonObject(client().get(host, "/main-navigation").body()).getString("environment"),
          host);
    }
  }

  // --- verbatim forwarding, on the name that serves it ------------------------------------------

  @Test
  void thePathAndQueryReachTheUpstreamUnchanged() {
    // No path knowledge means no path rewriting: a service's own route table is written against the
    // paths a client typed, and a prefix stripped here would break every one of them.
    activateCi();
    assertEquals(
        "/deep/path/here?x=1&y=2",
        client().get("ci.dev.example.com", "/deep/path/here?x=1&y=2", token("dev")).line("uri"));
  }

  @Test
  void aPostBodyReachesTheUpstream() {
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(HttpMethod.POST, "ci.dev.example.com", "/api/thing", "hello edge", token("dev"));
    assertEquals("POST", answer.line("method"));
    assertEquals("hello edge", answer.line("body"));
    assertEquals("10", answer.line("body-bytes"));
  }

  @Test
  void everyMethodPassesThrough() {
    activateCi();
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
      assertEquals(
          method.name(),
          client().send(method, "ci.dev.example.com", "/thing", "x", token("dev")).line("method"),
          "the edge must not have an opinion about " + method);
    }
  }

  @Test
  void headersReachTheUpstreamUntouched() {
    // Beyond the browser cookie and the reserved prefix, the edge strips NOTHING: an unrelated
    // cookie, a custom header and the credential itself all arrive as they were sent.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("Cookie", "q_session=abc");
    headers.put("X-Custom", "kept");
    EdgeClient.Answer answer =
        client().send(HttpMethod.GET, "ci.dev.example.com", "/thing", null, headers);

    assertEquals(headers.get("Authorization"), answer.upstreamHeader("Authorization"));
    assertEquals("q_session=abc", answer.upstreamHeader("Cookie"));
    assertEquals("kept", answer.upstreamHeader("X-Custom"));
  }

  @Test
  void theOriginalHostReachesTheUpstream() {
    // Load-bearing: every redirect, cookie domain and absolute URL a service builds comes from this
    // header. Rewriting it to the upstream's own name would break all three at once and leave
    // nothing in a log to say so.
    activateCi();
    String seen = client().get("ci.dev.example.com", "/thing", token("dev")).upstreamHeader("Host");
    assertTrue(
        seen != null && seen.startsWith("ci.dev.example.com"),
        "the upstream must see the name the client asked for, but saw: " + seen);
  }

  @Test
  void aResponseHeaderReachesTheClientUnchanged() {
    activateCi();
    assertEquals(
        "mirror-dev",
        client().get("ci.dev.example.com", "/thing", token("dev")).headers().get("x-upstream"));
  }

  // --- the forwarded headers -----------------------------------------------------------------

  @Test
  void theEdgeDescribesTheOriginalClient() {
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.example.com", "/thing", token("dev"));
    assertEquals("127.0.0.1", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("http", answer.upstreamHeader("X-Forwarded-Proto"));
    assertTrue(answer.upstreamHeader("X-Forwarded-Host").startsWith("ci.dev.example.com"));
  }

  @Test
  void anExistingForwardedHeaderIsKept() {
    // The edge is not always the outermost hop: a TLS terminator in front of it is the only thing
    // that can tell the truth about `https`, and overwriting would replace a true value with a
    // false one. Nothing downstream may make a trust decision on these three, and nothing does.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("X-Forwarded-For", "203.0.113.7");
    headers.put("X-Forwarded-Proto", "https");
    headers.put("X-Forwarded-Host", "edge.example.com");
    EdgeClient.Answer answer =
        client().send(HttpMethod.GET, "ci.dev.example.com", "/thing", null, headers);

    assertEquals("203.0.113.7", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("https", answer.upstreamHeader("X-Forwarded-Proto"));
    assertEquals("edge.example.com", answer.upstreamHeader("X-Forwarded-Host"));
  }

  // --- streaming -----------------------------------------------------------------------------

  @Test
  void aChunkedResponseIsNotBuffered() {
    // The stub writes two chunks with a gap between them. A proxy that buffered would deliver both
    // at the end, so the FIRST chunk's arrival time is the assertion — the body alone would pass
    // either way. SSE channels and `git clone` are what this protects.
    activateCi();
    EdgeClient.Streamed streamed = client().stream("ci.dev.example.com", "/stream", token("dev"));

    assertEquals("chunk-1\nchunk-2\n", streamed.body());
    assertTrue(
        streamed.firstChunkMillis() < StubGateways.STREAM_GAP_MILLIS,
        "the first chunk arrived after "
            + streamed.firstChunkMillis()
            + "ms, which is not before the upstream sent the second at "
            + StubGateways.STREAM_GAP_MILLIS
            + "ms — the response was buffered");
  }

  // --- websockets ----------------------------------------------------------------------------

  @Test
  void aWebSocketUpgradeReachesTheServiceItsHostNames() {
    // Every interactive terminal on the platform is one of these. Getting a frame back at all is
    // what proves the handshake survived the hop.
    activateCi();
    String seen = client().handshake("ci.dev.example.com", "/terminal", token("dev"));
    assertTrue(seen.lines().anyMatch("upstream=mirror-dev"::equals), seen);
  }

  @Test
  void aWebSocketUpgradeCarriesTheForwardedHeaders() {
    // The upgrade never reaches the interceptor chain — vertx-http-proxy short-circuits before
    // installing it — so this is a second code path with its own way of losing the headers.
    activateCi();
    String seen = client().handshake("ci.dev.example.com", "/terminal", token("dev"));
    assertTrue(seen.lines().anyMatch("x-forwarded-for=127.0.0.1"::equals), seen);
    assertTrue(seen.lines().anyMatch("x-forwarded-proto=http"::equals), seen);
    assertTrue(
        seen.lines().anyMatch(l -> l.startsWith("x-forwarded-host=ci.dev.example.com")), seen);
  }

  @Test
  void aWebSocketUpgradeStillCarriesTheClientsOwnHeaders() {
    // The edge strips nothing but the browser cookie on an upgrade either: an unrelated cookie is a
    // service's own and travels with the socket.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("Cookie", "q_session=abc");
    String seen = client().handshake("ci.dev.example.com", "/terminal", headers);
    assertTrue(seen.lines().anyMatch("cookie=q_session=abc"::equals), seen);
  }

  @Test
  void aRefusedUpgradeAnswersTheUpstreamsOwnStatus() {
    // The upstream said no; the caller learns what it said, not a generic 502 — a workspace
    // service answering 403 on a terminal socket is an authorization answer, not an edge fault.
    activateCi();
    EdgeClient.Answer answer =
        client().send(HttpMethod.GET, "ci.dev.example.com", "/terminal/refused", null, upgrade());
    assertEquals(403, answer.status());
  }

  @Test
  void aRefusedUpgradeReturnsItsPoolSlotEveryTime() {
    // The production outage this guards: an upgrade that failed after the upstream had accepted it
    // left its pool connection neither closed nor released — one slot per attempt, and the browser
    // retried until all 64 were gone and every request to the origin, plain GETs included, queued
    // forever. More refusals than the whole pool, then a plain GET: with a leak the attempts past
    // 64 hang and this test times out rather than fails an assertion.
    activateCi();
    for (int attempt = 0; attempt < 70; attempt++) {
      EdgeClient.Answer answer =
          client().send(HttpMethod.GET, "ci.dev.example.com", "/terminal/refused", null, upgrade());
      assertEquals(403, answer.status(), "attempt " + attempt);
    }
    EdgeClient.Answer plain = client().get("ci.dev.example.com", "/anything", token("dev"));
    assertEquals("mirror-dev", plain.line("upstream"), "the origin must survive 70 refusals");
  }

  /**
   * A complete handshake, sent raw: the JDK client of {@link EdgeClient#handshake} throws away the
   * response of a refused upgrade, and these tests are about exactly that response.
   */
  private Map<String, String> upgrade() {
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("Upgrade", "websocket");
    headers.put("Connection", "Upgrade");
    headers.put("Sec-WebSocket-Key", "AAAAAAAAAAAAAAAAAAAAAA==");
    headers.put("Sec-WebSocket-Version", "13");
    return headers;
  }

  // --- the edge's own surface ------------------------------------------------------------------

  @Test
  void healthIsAnsweredByTheEdgeItself() {
    // /q never leaves this process, whatever the Host name says and whatever the environment list
    // holds — it is the one thing an orchestrator asks the EDGE about, not an environment behind
    // it. The upstream marker below is what proves it was not proxied: a stub gateway names itself
    // in every answer, so its absence is the assertion.
    RestAssured.given()
        .header("Host", "dev.example.com")
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", is("UP"))
        .body("checks.find { it.name == 'edge upstreams' }.data.default", is("prod"));
  }

  @Test
  void livenessIsAnsweredByTheEdgeItself() {
    RestAssured.when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
  }

  @Test
  void aPathThatOnlyLooksLikeTheManagementRootIsProxied() {
    // /q is the prefix, not a substring: /queue belongs to a service like any other path.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.example.com", "/queue/items", token("dev"));
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("/queue/items", answer.line("uri"));
  }

  // --- idp auth, terminated here ---------------------------------------------------------------

  @Test
  void anApplicationVhostRefusesAnAnonymousCallerWithTheDockerChallenge() {
    // The exact string docker parses to find its token endpoint. Getting it wrong fails the pull
    // with no message anywhere, which is why it is asserted whole rather than by substring.
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        "Bearer realm=\"http://registry.dev.example.com/token\",service=\"registry.dev.example.com\"",
        answer.headers().get("www-authenticate"));
    assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
    assertNull(answer.line("upstream"), "an anonymous request must not reach the application");
  }

  @Test
  void aGatedRefusalOffersBearerAndThenBasic() {
    // Two clients, two schemes, and the ORDER is the contract:
    //   * docker and containerd walk the challenges and act on the first they know, so Bearer must
    //     come first or the token flow stops being used;
    //   * maven's resolver only spends its configured credentials against a scheme it implements,
    //     so without the Basic line every uncached resolve in a build dies 401 with the right
    //     credentials sitting unused.
    // Asserted from the raw header list: a map collapses the two into one and proves nothing.
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        List.of(
            "Bearer realm=\"http://registry.dev.example.com/token\",service=\"registry.dev.example.com\"",
            "Basic realm=\"registry.dev.example.com\""),
        answer.headerValues("www-authenticate"));
  }

  @Test
  void everyGatedRefusalCarriesBothChallengesAndNotJustTheAnonymousOne() {
    // A build resolves through both: the first request of a session carries nothing, and a later
    // one may carry a credential this vhost refuses. Both have to tell maven that Basic is taken.
    for (Map<String, String> credential :
        List.of(
            Map.<String, String>of(), basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET))) {
      EdgeClient.Answer answer =
          client().send(HttpMethod.PUT, "registry.dev.example.com", "/v2/blob", "x", credential);
      assertEquals(401, answer.status());
      List<String> challenges = answer.headerValues("www-authenticate");
      assertEquals(2, challenges.size(), challenges.toString());
      assertTrue(challenges.get(0).startsWith("Bearer realm="), challenges.toString());
      assertEquals("Basic realm=\"registry.dev.example.com\"", challenges.get(1));
    }
  }

  @Test
  void anApplicationVhostRefusesATokenSignedBySomebodyElse() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IMPOSTOR,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(),
                            List.of(StubGateways.audience("dev")),
                            Instant.now().plusSeconds(300)))));
    assertEquals(401, answer.status());
    // `error` is what tells docker the credential it holds is dead, so it re-fetches rather than
    // giving up. It is absent from the anonymous challenge above, on purpose.
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        answer.headers().get("www-authenticate"));
    assertNull(answer.line("upstream"));
  }

  @Test
  void anApplicationVhostRefusesAnExpiredTokenAndOneForAnotherAudience() {
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IDP,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(),
                            List.of(StubGateways.audience("dev")),
                            Instant.now().minusSeconds(3600)))))
            .status());
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IDP,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(), List.of("somebody-else"), Instant.now().plusSeconds(300)))))
            .status());
  }

  @Test
  void aTokenForOneEnvironmentDoesNotUnlockAnother() {
    // The audience the edge demands is derived per request, from the environment the vhost named —
    // so dev's registry token is refused at prod's registry, and the reverse, from ONE config
    // entry.
    // Without the derivation both would pass, and the tiers would share a key.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals(401, client().get("registry.prod.example.com", "/v2/", token("dev")).status());
    assertEquals(401, client().get("registry.dev.example.com", "/v2/", token("prod")).status());
  }

  @Test
  void aTokenNamingEveryEnvironmentsAudienceOpensEachOfThem() {
    // What idp actually mints when the grant asks for no audience: the client's whole allowed list.
    Map<String, String> whole =
        bearer(
            TestTokens.valid(
                issuer(), List.of(StubGateways.audience("dev"), StubGateways.audience("prod"))));
    assertEquals(
        "registry-dev", client().get("registry.dev.example.com", "/v2/", whole).line("upstream"));
    assertEquals(
        "registry-prod", client().get("registry.prod.example.com", "/v2/", whole).line("upstream"));
  }

  // --- the browser gate, dark
  // ---------------------------------------------------------------------

  @Test
  void theGateBeingOffTurnsOnNoSessionMachineryButStillStripsAForgedIdentity() {
    // qits.edge.sessions.enabled is off in this suite, which is the shipped default: no session
    // introspection and no identity WRITTEN. What is off is the browser gate — not the reserved-
    // prefix strip, which is orthogonal to it and unconditional. A client-supplied X-Qits-* is a
    // forged identity whatever the gate's state, and the edge cannot assume a downstream tier will
    // drop it — the reserved-namespace hygiene is its own to uphold on every path. `mirror` is the
    // vhost whose reads are open, so the request needs no credential at all — and the forged
    // headers
    // on it still must not survive.
    int before = StubGateways.introspections();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "mirror.dev.example.com",
                "/v2/",
                null,
                Map.of(
                    "Cookie", "qits-session=" + StubGateways.SESSION,
                    "X-Qits-User", "whoever",
                    "X-Qits-Roles", "qits:root",
                    "Sec-Fetch-Mode", "navigate"));

    assertEquals(200, answer.status(), "a navigation is not redirected while the gate is off");
    assertEquals("mirror-dev", answer.line("upstream"));
    assertNull(
        answer.upstreamHeader("X-Qits-User"), "a forged identity is stripped, gate or no gate");
    assertNull(answer.upstreamHeader("X-Qits-Roles"));
    assertEquals(before, StubGateways.introspections(), "and idp was never asked");
  }

  @Test
  void aWebSocketUpgradeIsNotGatedButStillStripsAForgedIdentityWhileTheGateIsOff() {
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("X-Qits-User", "whoever");
    String seen = client().handshake("ci.dev.example.com", "/terminal", headers);
    // The stub reports every reserved header slot as `name=value`, with `-` for absent — so the
    // strip shows as `x-qits-user=-`, and what must never appear is the forged value.
    assertTrue(
        seen.lines().noneMatch("x-qits-user=whoever"::equals),
        "a forged identity on an upgrade is stripped even with the gate off:\n" + seen);
  }

  // --- the anonymous-read exemption, per app ----------------------------------------------------

  @Test
  void anExemptedAppVhostServesAnAnonymousGet() {
    // `mirror` is named in qits.edge.auth.anonymous-read-apps. A pull with no credential is the
    // bootstrap case this exists for, and it has to reach the upstream rather than the challenge.
    assertEquals("mirror-dev", client().get("mirror.dev.example.com", "/v2/").line("upstream"));
    assertEquals("mirror-prod", client().get("mirror.prod.example.com", "/v2/").line("upstream"));
  }

  @Test
  void anExemptedAppVhostServesAnAnonymousHead() {
    // The other reading method, and docker uses it for every blob it checks before pulling. A HEAD
    // answer carries no body, so the upstream marker is read from the header the stub also sets.
    EdgeClient.Answer answer =
        client().send(HttpMethod.HEAD, "mirror.dev.example.com", "/v2/blob", null, Map.of());
    assertEquals(200, answer.status());
    assertEquals("mirror-dev", answer.headers().get("x-upstream"));
  }

  @Test
  void anExemptedAppVhostStillGatesEveryWritingMethod() {
    // The exemption opens READS, never a service. A push is what changes what the platform will
    // run, and it gets the same challenge as before — including the realm docker needs to act on
    // it.
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
      EdgeClient.Answer answer =
          client().send(method, "mirror.dev.example.com", "/v2/blob", "x", Map.of());
      assertEquals(401, answer.status(), method + " must still be gated");
      assertEquals(
          "Bearer realm=\"http://mirror.dev.example.com/token\",service=\"mirror.dev.example.com\"",
          answer.headers().get("www-authenticate"));
      assertNull(answer.line("upstream"), method + " must not have reached the application");
    }
  }

  @Test
  void anAuthenticatedWriteOnAnExemptedAppVhostPasses() {
    // The other half: the exemption is a way past the gate, not a replacement for it.
    EdgeClient.Answer answer =
        client().send(HttpMethod.POST, "mirror.dev.example.com", "/v2/blob", "x", token("dev"));
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("POST", answer.line("method"));
    assertEquals("x", answer.line("body"));
  }

  @Test
  void aMachineVhostNeverReceivesTheBrowserSessionCookieButKeepsOtherCookies() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                Map.of(
                    "Authorization",
                    token("dev").get("Authorization"),
                    "Cookie",
                    "theme=dark; qits-session=" + StubGateways.SESSION + "; locale=en"));
    assertEquals("registry-dev", answer.line("upstream"));
    assertEquals("theme=dark; locale=en", answer.upstreamHeader("Cookie"));
  }

  @Test
  void anAppThatWasNotNamedStillRefusesAnAnonymousRead() {
    // Per app label: `registry` is not on the list, so its reads are gated exactly as before.
    assertEquals(401, client().get("registry.dev.example.com", "/v2/").status());
    assertEquals(
        401,
        client()
            .send(HttpMethod.HEAD, "registry.dev.example.com", "/v2/", null, Map.of())
            .status());
  }

  @Test
  void anUnknownAppLabelIsStill404EvenWhereReadsAreOpen() {
    // The exemption is applied AFTER the label resolves, so it cannot turn a typo into a route.
    // `mirro` is one letter from an app whose reads are open and is still nobody's name.
    assertEquals(404, client().get("mirro.dev.example.com", "/v2/").status());
  }

  // --- the docker token endpoint ----------------------------------------------------------------

  @Test
  void theTokenEndpointAsksForTheStoredLoginCredential() {
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/token?service=x&scope=y");
    assertEquals(401, answer.status());
    // Basic ALONE, and that is the difference from a gated request: this is the endpoint that SELLS
    // bearer tokens, so a Bearer challenge here would point a client back at where it already is.
    assertEquals(
        List.of("Basic realm=\"registry.dev.example.com\""),
        answer.headerValues("www-authenticate"));
  }

  @Test
  void theTokenEndpointBrokersAGrantAndHandsBackADockerStyleToken() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/token?service=registry.dev.example.com&scope=repository:qits/x:pull",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(200, answer.status());
    JsonObject issued = new JsonObject(answer.body());
    assertNotNull(issued.getString("token"));
    assertEquals(issued.getString("token"), issued.getString("access_token"));
    assertEquals(300, issued.getInteger("expires_in"));
  }

  @Test
  void theTokenEndpointRefusesCredentialsIdpDoesNotKnow() {
    assertEquals(
        401,
        client().get("registry.dev.example.com", "/token", basic("nobody", "nothing")).status());
  }

  @Test
  void theWholeDockerFlowRoundTrips() {
    // Challenge, token, retry — the three hops a `docker pull` makes, in order, with no shortcut.
    EdgeClient.Answer challenged = client().get("registry.dev.example.com", "/v2/");
    assertEquals(401, challenged.status());
    String realm = challenged.headers().get("www-authenticate").split("realm=\"")[1].split("\"")[0];
    assertTrue(realm.endsWith("/token"), realm);

    String issued =
        new JsonObject(
                client()
                    .get(
                        "registry.dev.example.com",
                        "/token?service=registry.dev.example.com",
                        basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
                    .body())
            .getString("token");

    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", bearer(issued)).line("upstream"));
  }

  // --- HTTP Basic, for the clients that cannot do docker's dance --------------------------------

  @Test
  void aClientIdAndSecretOpenAGatedVhostOnTheirOwn() {
    // maven, npm and git send Basic and nothing else. The edge spends the credential at idp and
    // reads the token that comes back, so one commissioned client works for all three.
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    EdgeClient.Answer written =
        client()
            .send(
                HttpMethod.POST,
                "registry.dev.example.com",
                "/v2/blob",
                "x",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals("registry-dev", written.line("upstream"), "a write is the same decision");
    assertEquals("x", written.line("body"));
  }

  @Test
  void aBasicCredentialCarriesTheSameAudienceDemandAsABearer() {
    // The whole point of validating rather than trusting: the client is real, its secret is right,
    // and it is commissioned for an audience this vhost does not demand.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET));
    assertEquals(401, answer.status());
    assertTrue(
        answer.headers().get("www-authenticate").startsWith("Bearer realm="),
        // The challenge stays docker's, whatever the credential was: docker is the client that
        // reads it, and the one that sent Basic here does not read challenges at all.
        answer.headers().get("www-authenticate"));
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        "a credential that was refused says so, unlike a request that carried none");
    assertNull(answer.line("upstream"));
  }

  @Test
  void aWrongSecretIsRefusedAndIsNotRememberedAsARefusal() {
    // Refusals are not cached: a rotated secret must start working the moment it is right, rather
    // than staying shut for as long as a cache says it was wrong.
    int before = StubGateways.grants();
    assertEquals(
        401, client().get("registry.dev.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(
        401, client().get("registry.dev.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(before + 2, StubGateways.grants(), "each attempt is idp's decision to make");
  }

  @Test
  void aValidatedCredentialIsRememberedForATimeAndThenAskedAboutAgain() throws Exception {
    // A Basic client resends its credential on EVERY request — that is what makes it a Basic
    // client — so without a cache each dependency fetch would put an idp round trip on the path.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.grants();
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    assertEquals(before + 1, StubGateways.grants(), "the first request spends the credential");

    client()
        .get(
            "registry.dev.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    client()
        .get(
            "registry.prod.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(
        before + 1,
        StubGateways.grants(),
        "a remembered credential asks nobody — including on the other tier's vhost");

    Thread.sleep(cacheTtlMs() + 400);
    client()
        .get(
            "registry.dev.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 2, StubGateways.grants(), "and the belief runs out");
  }

  @Test
  void aBasicHeaderThatIsNotACredentialIsRefusedWithoutTroublingIdp() {
    // An empty credential store, a truncated helper answer. There is nothing to ask about, and
    // asking would hold the caller for the whole patience window while idp is being waited out.
    int before = StubGateways.grants();
    assertEquals(
        401,
        client()
            .get("registry.dev.example.com", "/v2/", Map.of("Authorization", "Basic !!not-base64"))
            .status());
    assertEquals(
        401,
        client()
            .get("registry.dev.example.com", "/v2/", Map.of("Authorization", "Basic "))
            .status());
    assertEquals(before, StubGateways.grants(), "neither reached the identity provider");
  }

  @Test
  void anOpenReadStaysOpenWhateverTheCredentialSays() {
    // The exemption is decided before any credential is read, so a garbage one cannot close a door
    // that is meant to be open — the same as it has always been for a garbage Bearer.
    assertEquals(
        "mirror-dev",
        client()
            .get("mirror.dev.example.com", "/v2/", Map.of("Authorization", "Basic !!not-base64"))
            .line("upstream"));
  }

  // --- an identity provider that is not there ---------------------------------------------------

  @Test
  void theBrokerWaitsOutAnIdpThatIsComingBack() throws Exception {
    // 2026-08-14: a deploy push died with "the identity provider could not be reached" because idp
    // was a few seconds into a redeploy. A refused connection is not an answer, so it is retried.
    StubGateways.idpDown();
    try {
      java.util.concurrent.CompletableFuture<EdgeClient.Answer> answer =
          client()
              .sending(
                  HttpMethod.GET,
                  "registry.dev.example.com",
                  "/token?service=registry.dev.example.com",
                  null,
                  basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
      Thread.sleep(400);
      StubGateways.idpUp();
      EdgeClient.Answer issued = answer.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(200, issued.status(), issued.body());
      assertNotNull(new JsonObject(issued.body()).getString("token"));
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void anIdpThatAcceptsAndNeverAnswersStillEndsInAnAnswerHere() {
    // THE HANG, and the only path in this process that could produce one: a Vert.x client is built
    // with no request timeout, so a connection that is accepted and never answered leaves the
    // caller with no status, no body and nothing to time out against. docker has no timeout of its
    // own on a realm call, so it waits for as long as the socket lives.
    long start = System.currentTimeMillis();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/token?service=registry.dev.example.com",
                basic(StubGateways.SINKHOLE_ID, StubGateways.SINKHOLE_SECRET));
    long took = System.currentTimeMillis() - start;
    assertEquals(502, answer.status());
    assertTrue(answer.body().contains("UNAVAILABLE"), answer.body());
    assertTrue(took < 25_000, "the window bounds it, and it took " + took + "ms");
  }

  @Test
  void aBasicRequestAgainstASilentIdpIsDeniedRatherThanHeld() {
    // The same certainty on the gate: a check that cannot be made denies, and it denies in bounded
    // time. An open-ended wait here would hold the connection instead of answering it.
    long start = System.currentTimeMillis();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.SINKHOLE_ID, StubGateways.SINKHOLE_SECRET));
    long took = System.currentTimeMillis() - start;
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(took < 25_000, "the window bounds it, and it took " + took + "ms");
  }

  // --- the token endpoint's own credential-less arms ---------------------------------------------

  @Test
  void everyShapeOfMissingCredentialIsAnsweredPromptlyAndWhole() {
    // What docker does after the challenge is call the realm, and with nothing stored it calls it
    // with no credential or an empty one. Each of these must be a COMPLETE response — a body, a
    // length, an end — because the client that gets it is waiting with no timeout of its own.
    for (Map<String, String> headers :
        List.of(
            Map.<String, String>of(),
            Map.of("Authorization", "Basic"),
            Map.of("Authorization", "Basic "),
            Map.of(
                "Authorization",
                "Basic "
                    + Base64.getEncoder().encodeToString(":".getBytes(StandardCharsets.UTF_8))),
            Map.of("Authorization", "Basic !!not-base64"))) {
      for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST}) {
        long start = System.currentTimeMillis();
        EdgeClient.Answer answer =
            client()
                .send(
                    method,
                    "registry.dev.example.com",
                    "/token?service=registry.dev.example.com",
                    method == HttpMethod.POST ? "grant_type=client_credentials" : null,
                    headers);
        assertEquals(401, answer.status(), method + " " + headers);
        assertTrue(
            answer.headers().get("www-authenticate").startsWith("Basic realm="),
            answer.headers().get("www-authenticate"));
        assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
        assertTrue(
            System.currentTimeMillis() - start < 5_000, method + " " + headers + " was not prompt");
      }
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * {@code qits.edge.auth.basic-cache-ttl-ms}, which StubGateways shrinks to a suite's patience.
   */
  private static long cacheTtlMs() {
    return ConfigProvider.getConfig().getValue("qits.edge.auth.basic-cache-ttl-ms", Long.class);
  }

  /** The issuer the stub idp uses, which is what {@code qits.idp.url} was set to. */
  private static String issuer() {
    return ConfigProvider.getConfig().getValue("qits.idp.url", String.class);
  }

  /** A token idp would mint for one environment's registry, and that environment's only. */
  private static Map<String, String> token(String environment) {
    return bearer(TestTokens.valid(issuer(), List.of(StubGateways.audience(environment))));
  }

  private static Map<String, String> bearer(String jwt) {
    return Map.of("Authorization", "Bearer " + jwt);
  }

  private static Map<String, String> basic(String id, String secret) {
    return Map.of(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
  }
}
