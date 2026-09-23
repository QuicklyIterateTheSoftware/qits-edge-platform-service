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

  @Inject EdgeProjects projects;

  /**
   * The project projection's barrier, which this suite lowers to see what a behind edge answers.
   */
  @Inject ProjectSansBootstrap projectSans;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  /** The project every name in the project tiers is spelled with here. It has environments. */
  private static final String PROJECT = "acme";

  /**
   * A project that has NO environments, whose applications therefore carry no environment label —
   * {@code <app>.gizmo.example.com}, served in the default environment. It is the shape the
   * platform's own project takes once its {@code supportsEnvironments} flag is off.
   */
  private static final String FLAT_PROJECT = "gizmo";

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
    publishProject();
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
      EdgeClient.Answer answer = client().get("dev.acme.example.com", path);
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
      EdgeClient.Answer answer = client().get("dev.acme.example.com", "/ci/api/runs", credential);
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
                "dev.acme.example.com",
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
        client().get("registry.dev.acme.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals(
        "registry-prod",
        client().get("registry.prod.acme.example.com", "/v2/", token("prod")).line("upstream"));
  }

  @Test
  void anUnconfiguredApplicationLabelIsRefusedRatherThanSentToTheGateway() {
    // NOT a fall-through. The name was aimed at a service, and the gateway is the hop that does not
    // authenticate these — a mistyped registry vhost reaching it would be an open door with a typo
    // for a key.
    EdgeClient.Answer answer = client().get("registy.dev.acme.example.com", "/v2/");
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
        client()
            .get("ci.dev.acme.example.com", "/artifacts/api/files", token("dev"))
            .line("upstream"));
    // The route's prefix boundary matters: /artifacts catches a child, never this merely similar
    // word — which nobody declared, so it falls to the service whose name this is.
    assertEquals(
        "mirror-dev",
        client().get("ci.dev.acme.example.com", "/artifacts-old", token("dev")).line("upstream"));
  }

  /**
   * The publisher's WHOLE frame, not the six fields this projection happens to read.
   *
   * <p>qits-deployments' {@code DeploymentActive} carries nine more components than {@link
   * DeploymentActiveSubscriber.DeploymentActivePayload} models — {@code deploymentId}, {@code
   * environmentId}, {@code version}, {@code commitSha}, {@code runId}, {@code containerName},
   * {@code finishedAt} — and the platform plane fills every one of them in. That is the shape on
   * the bus today, and it is the shape this edge must keep decoding after the publisher grows the
   * next field: the private wire DTO exists precisely so a vocabulary addition is not a Maven
   * release the edge has to wait for, and {@code CanonicalJson} ignores unknown properties so the
   * addition costs nothing.
   *
   * <p>The field names are literals rather than a dependency on the publisher's record. Spelling
   * them out is the point — a rename on the far side has to fail HERE, loudly, instead of quietly
   * decoding to null and unrouting an application.
   */
  @Test
  void aFullyPopulatedPublisherFrameRoutesAndItsUnmodelledFieldsAreIgnored() throws Exception {
    clearProjection();
    activateCi(); // the vhost the request below is addressed to, exactly as the test above uses it
    Upstream upstream = upstream("qits.edge.apps.registry.hosts.dev");

    deployments.onFrame(
        frame(
            new JsonObject()
                .put("deploymentId", "b7c1f3a4-0e29-4d51-9a8c-2f6b5d0e7c31")
                .put("applicationName", "qits-artifacts")
                .put("environmentId", "3f9d2c18-7b64-4a05-8e11-c6d4a92f7b83")
                .put("environmentName", "dev")
                .put("version", "2026.904.211334")
                .put("commitSha", "8a9b8fa6c2d14e0b7f35a9c8d1e2b4f60a3c7d95")
                .put("runId", "run-4821")
                .put("containerName", "qits-artifacts-b7c1f3a4")
                .put("finishedAt", "2026-09-04T21:13:34Z")
                .put("browserHost", "registry")
                .put("apiDocsPath", "/artifacts/q/swagger-ui")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/artifacts", upstream))
                        .add(endpoint("/v2", upstream)))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("services.details", "Artifacts", 3)))));

    // Decoded, not settled-unhandled: the routes, the public name and the placement all landed.
    assertEquals(
        "registry-dev",
        client()
            .get("ci.dev.acme.example.com", "/artifacts/api/files", token("dev"))
            .line("upstream"));
    assertNotNull(routes.resolve("dev", "/v2/"));
    assertEquals(
        List.of(
            new EdgeRoutes.NavigationPlacement(
                "qits-artifacts",
                "services.details",
                "Artifacts",
                3,
                "registry",
                "/artifacts",
                null)),
        routes.navigation("dev").stream()
            .filter(placement -> "qits-artifacts".equals(placement.application()))
            .toList());

    // environmentName is the routing key, and a populated one files the snapshot under THAT
    // environment alone rather than fanning out across qits.edge.environments.
    assertNull(routes.resolve("prod", "/artifacts/api/files"));
  }

  @Test
  void mainNavigationCarriesEverySlotAndOneOriginPerService() {
    activateArtifacts();
    activateCi();

    EdgeClient.Answer navigation = client().get("dev.acme.example.com", "/main-navigation");
    assertEquals(200, navigation.status());
    assertEquals("no-store", navigation.headers().get("cache-control"));
    JsonObject document = new JsonObject(navigation.body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.acme.example.com", document.getString("origin"));

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
            "apps.details",
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
        List.of("http://ci.dev.acme.example.com", "http://registry.dev.acme.example.com"),
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
  void anAppsDetailsPlacementIsAdmittedAndRendersBetweenLibsAndFrontends() {
    // The seventh archetype slot. It is published exactly like the six before it; what makes it the
    // seventh rather than the last is where it sits in EdgeRoutes.SLOTS, which is both the order
    // this document renders and the order qits-deployments' spec parser reads the same words in.
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-ci")
                .put("environmentName", "dev")
                .put("browserHost", "ci")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/ci", upstream("qits.edge.apps.mirror.hosts.dev"))))
                .put(
                    "navigation",
                    // Declared out of order on purpose: the vocabulary decides the render order,
                    // not the order the deployment happened to write the placements in.
                    new io.vertx.core.json.JsonArray()
                        .add(placement("frontends.details", "Shell", 1))
                        .add(placement("apps.details", "Docs", 1))
                        .add(placement("libs.details", "Eventstream", 1)))));

    assertEquals(
        List.of("libs.details", "apps.details", "frontends.details"),
        routes.navigation("dev").stream().map(EdgeRoutes.NavigationPlacement::slot).toList());

    JsonObject slots =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
            .getJsonObject("slots");
    assertEquals(
        List.of("Docs"),
        slots.getJsonArray("apps.details").stream()
            .map(value -> ((JsonObject) value).getString("label"))
            .toList());
    assertEquals(
        "http://ci.dev.acme.example.com",
        slots.getJsonArray("apps.details").getJsonObject(0).getString("origin"));
    // The key order of the document is the render order, so the new slot is drawn after the
    // libraries and before the microfrontends — the order qits-deployments publishes in too.
    List<String> keys = List.copyOf(slots.fieldNames());
    assertEquals(keys.indexOf("libs.details") + 1, keys.indexOf("apps.details"), keys.toString());
    assertEquals(
        keys.indexOf("apps.details") + 1, keys.indexOf("frontends.details"), keys.toString());
  }

  @Test
  void aWordOutsideTheVocabularyIsStillRefusedWholeAfterTheSeventhSlotWasAdded() {
    // Admitting a word does not open the vocabulary. The near miss is the singular of the new one,
    // which is exactly the typo a hand-written spec makes, and the refusal names both the word and
    // the list so it is readable where it is logged.
    String message =
        assertThrows(
                IllegalArgumentException.class,
                () -> new EdgeRoutes.NavigationEntry("app.details", "Docs", 1))
            .getMessage();
    assertTrue(message.contains("`app.details` is not a navigation slot"), message);
    assertTrue(message.contains("apps.details"), message);

    // And, like every poison frame, the one carrying it changes no routes at all.
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-ci")
                .put("environmentName", "dev")
                .put("browserHost", "ci")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/ci", upstream("qits.edge.apps.mirror.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray().add(placement("app.details", "Docs", 1)))));

    assertTrue(routes.navigation("dev").isEmpty());
    assertNull(routes.serviceHost("dev", "ci"));
  }

  @Test
  void mainNavigationIsSlotsApplicationsAndNothingElse() {
    // No flat list and no synthesized Home. Every shell reads the tree, and the environment's own
    // door is qits-projects' `system` entry — a deployment fact like every other entry here.
    // `projectOrigin` is the one addition: the authority a client puts `<app>.<slug>.` in front of.
    activateArtifacts();
    activateCi();

    JsonObject document =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
    assertEquals(
        List.of("environment", "origin", "projectOrigin", "slots", "applications"),
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
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
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
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
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
        List.of("http://workspaces.dev.acme.example.com", "http://workspaces.dev.acme.example.com"),
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
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
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
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
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
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
    JsonObject entry = document.getJsonObject("slots").getJsonArray("system").getJsonObject(0);
    assertEquals("Workspaces", entry.getString("label"));
    assertNull(entry.getString("host"));
    assertEquals("http://dev.acme.example.com", entry.getString("origin"));
    // The path is what a shell renders it under while it has no name of its own.
    assertEquals("/workspaces", entry.getString("path"));
  }

  @Test
  void navigationIsServedOnAServiceHostToo() {
    // Every shell renders the same tree, so the document is on every vhost — and it names the
    // environment's origins even when the request itself carried an application's name.
    activateCi();
    JsonObject document =
        new JsonObject(client().get("ci.dev.acme.example.com", "/main-navigation").body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.acme.example.com", document.getString("origin"));
    assertEquals(
        "http://ci.dev.acme.example.com",
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
        "mirror-dev", client().get("ci.dev.acme.example.com", "/", token("dev")).line("upstream"));
    assertEquals(
        "/deep/link",
        client().get("ci.dev.acme.example.com", "/deep/link", token("dev")).line("uri"));
  }

  @Test
  void anotherApplicationsPrimaryRouteIsPathRoutedOnAServiceHost() {
    // What makes the whole platform same-origin from any host: an SPA on ci.dev.acme.example.com
    // reads
    // /artifacts/api without CORS, because the segment an application is KNOWN by means the same
    // thing on every name.
    activateCi();
    activateArtifacts();
    assertEquals(
        "registry-dev",
        client()
            .get("ci.dev.acme.example.com", "/artifacts/api/files", token("dev"))
            .line("upstream"));
    assertEquals(
        "/artifacts/api/files",
        client().get("ci.dev.acme.example.com", "/artifacts/api/files", token("dev")).line("uri"));
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
        "mirror-dev",
        client().get("ci.dev.acme.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals("/v2/", client().get("ci.dev.acme.example.com", "/v2/", token("dev")).line("uri"));
    // On its owner's own name it is that service's, which is the only place it exists now.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", token("dev")).line("upstream"));
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
        "mirror-dev", client().get("ci.dev.acme.example.com", "/", token("dev")).line("upstream"));
  }

  @Test
  void aHostThatIsAnEnvironmentNameIsRefused() {
    // HostEnvironments reads the first label as an application, so `dev.dev.acme.example.com` would
    // be
    // routable and `dev.acme.example.com` would not.
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
        client().get("registry.dev.acme.example.com", "/v2/", token("dev")).line("upstream"));
  }

  // --- the editor, an ordinary app vhost --------------------------------------------------------

  @Test
  void theEditorIsReachedOnItsOwnTwoLabelNameLikeEveryOtherApp() {
    // The editor is ONE shared container for the whole platform, so its address is the ordinary
    // `editor.<env>.<domain>` — the environment is the SECOND label, and the two upstreams behind
    // the `{env}-qits-workspaces` host pattern are what make that an assertion about which process
    // answered rather than about a status code.
    assertEquals(
        "editor-dev",
        client().get("editor.dev.acme.example.com", "/editor", token("dev")).line("upstream"));
    assertEquals(
        "editor-prod",
        client().get("editor.prod.acme.example.com", "/editor", token("prod")).line("upstream"));
  }

  @Test
  void theEditorsOwnNameDemandsTheEditorsOwnAudience() {
    // The audience comes from the editor app entry's own pattern with the environment the NAME
    // states filled in, so the tier's own token is the only one that opens it.
    assertEquals(
        401, client().get("editor.dev.acme.example.com", "/editor", token("prod")).status());
    assertEquals(
        401, client().get("editor.prod.acme.example.com", "/editor", token("dev")).status());
  }

  // --- the project tiers -------------------------------------------------------------------------

  @Test
  void aPublishedHostIsReachedUnderAProjectToo() {
    // `workspaces` is a name a DEPLOYMENT publishes rather than a configured vhost, and it reaches
    // the app position like any other: the label is offered as unknown here and the projection
    // claims it.
    activateWorkspaces();
    assertEquals(
        "mirror-dev",
        client()
            .get("workspaces.dev." + PROJECT + ".example.com", "/workspaces/42", token("dev"))
            .line("upstream"));
  }

  @Test
  void anUnknownApplicationUnderAProjectIs404AndTheAnswerNamesBoth() {
    // The security property, unchanged by the new grammar: an app-shaped label nobody serves is
    // answered HERE, after the deployment projection has been asked, rather than falling through to
    // a hop that would not authenticate it.
    EdgeClient.Answer answer =
        client().get("nosuchapp.dev." + PROJECT + ".example.com", "/anything");
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("`nosuchapp` is not an application"), answer.body());
    assertTrue(answer.body().contains("the project `" + PROJECT + "`"), answer.body());
  }

  @Test
  void aProjectsOwnNameIsADoorAndSoIsAnEnvironmentInsideIt() {
    // Two doors now, one inside the other: `<project>.<domain>` and `<env>.<project>.<domain>`.
    // Neither serves a path, and each says which door it is.
    activateProjects();
    activateProjects("prod");

    EdgeClient.Answer project =
        client().get(PROJECT + ".example.com", "/ci/api/runs", token("dev"));
    assertEquals(404, project.status());
    assertNull(project.line("upstream"), "a door reaches no upstream");
    assertTrue(project.body().contains("`" + PROJECT + "` project's door"), project.body());
    assertTrue(project.body().contains("<app>." + PROJECT + ".example.com"), project.body());

    EdgeClient.Answer environment =
        client().get("dev." + PROJECT + ".example.com", "/ci/api/runs", token("dev"));
    assertEquals(404, environment.status());
    assertNull(environment.line("upstream"));
    assertTrue(
        environment.body().contains("`dev` environment of the `" + PROJECT + "` project"),
        environment.body());
    assertTrue(
        environment.body().contains("<app>.dev." + PROJECT + ".example.com"), environment.body());
  }

  @Test
  void anEnvironmentDoorStillSendsAVisitorToTheProjectsHost() {
    activateProjects();
    EdgeClient.Answer landing = client().get("dev." + PROJECT + ".example.com", "/");
    assertEquals(302, landing.status());
    assertEquals("http://projects.dev.acme.example.com/", landing.headers().get("location"));
  }

  @Test
  void aPublishedServiceNoLongerClaimsAProjectDoor() {
    // DELETED with the tie-breaks. A slug and a published service name used to be the same single
    // label in front of an environment, so the door had to be joined against the deployment
    // projection to see which of the two owned it. Positionally they are two different places —
    // `<project>.<domain>` against `<app>.<env>.<project>.<domain>` — so a deployment publishing a
    // name that happens to equal a slug takes nothing, and the door stays a door.
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-acme")
                .put("environmentName", "prod")
                .put("browserHost", PROJECT)
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/acme", upstream("qits.edge.apps.mirror.hosts.dev"))))));

    EdgeClient.Answer answer = client().get(PROJECT + ".example.com", "/acme", token("prod"));
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"), "the published service reaches nothing on the door's name");
    assertTrue(answer.body().contains("project's door"), answer.body());
  }

  @Test
  void aNameWithNoProjectLabelIsAnOrdinaryFourOhFour() {
    // The short-form refusal is GONE. `editor.dev.example.com` used to be a name with its project
    // label left out and got a sentence of its own about the spelling that works; now it is simply
    // a name whose project label says `dev`, and there is no project called that.
    EdgeClient.Answer editor = client().get("editor.dev.example.com", "/editor", token("dev"));
    assertEquals(404, editor.status());
    assertNull(editor.line("upstream"));
    assertTrue(editor.body().contains("`dev` is not a project on this platform"), editor.body());

    // And a name with a project label but too many in front of it is the other 404.
    EdgeClient.Answer deep = client().get("a.b.c." + PROJECT + ".example.com", "/editor");
    assertEquals(404, deep.status());
    assertTrue(deep.body().contains("more labels than the grammar has"), deep.body());
  }

  @Test
  void anEnvironmentTheProjectDoesNotHaveIsRefusedByName() {
    EdgeClient.Answer answer =
        client().get("editor.staging." + PROJECT + ".example.com", "/editor", token("dev"));
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("does not have an environment by that name"), answer.body());
  }

  @Test
  void aNavigationOnAProjectsAppTierIsTheNamedEnvironments() {
    // The document the editor's own shell reads. Getting the environment wrong here is the failure
    // this tier's authority reading exists to prevent: dev's editor rendering prod's services.
    activateCi();
    JsonObject document =
        new JsonObject(
            client().get("editor.dev." + PROJECT + ".example.com", "/main-navigation").body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.acme.example.com", document.getString("origin"));
    assertEquals("http://dev.acme.example.com", document.getString("projectOrigin"));
    assertEquals(
        "http://ci.dev.acme.example.com",
        document
            .getJsonObject("slots")
            .getJsonArray("services.details")
            .getJsonObject(0)
            .getString("origin"));
  }

  // --- the apex, which is the domain itself ------------------------------------------------------

  @Test
  void theApexIsReadPositionallyNowAndIsStillADoor() {
    // No rescue by the canonical origin any more: the domain is a stated value, so the apex is the
    // name that IS it. The trailing dot a resolver writes is the same name, as it is everywhere.
    activateProjects("prod");
    for (String apex : List.of("example.com", "example.com.")) {
      // And it no longer redirects. qits-projects is at `projects.<project>.<domain>` like every
      // other application, so composing that name needs a project label — the apex carries none,
      // and this deployment's canonical origin (`https://example.com`) names none either. It used
      // to send a visitor to `http://projects.prod.example.com/`, which is not a name any more.
      EdgeClient.Answer landing = client().get(apex, "/");
      assertEquals(404, landing.status(), apex);
      assertNull(landing.headers().get("location"), apex);
      assertTrue(landing.body().contains("Every application is inside a project"), landing.body());
      assertFalse(landing.body().contains("Start at"), landing.body());
    }
    EdgeClient.Answer elsewhere = client().get("example.com", "/anything");
    assertEquals(404, elsewhere.status());
    assertTrue(elsewhere.body().contains("serves nothing"), elsewhere.body());
  }

  @Test
  void aNameOutsideTheDomainServesNothingEither() {
    // The labels are somebody else's grammar, so there is no position to read — and the answer is a
    // door's: nothing is routed, nothing is proxied, and no upstream is reached.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.somewhere-else.test", "/", token("dev"));
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("serves nothing"), answer.body());
  }

  // --- the project projection's own barrier -----------------------------------------------------

  @Test
  void aNameWhoseReadingNeedsMoreSlugsIs503WhileTheProjectionIsBehind() throws Exception {
    // The window this exists for: the deployment catch-up reaches head first, or qits-events is
    // down for this one consumer, and the slug set is short. A name in the project tiers then reads
    // as a name nobody serves — and a 404 telling a person to use a name that also 404s is a bug
    // report against a platform that is merely reading. A retryable 503 is recoverable.
    projectSans.authoritative(false);
    try {
      // The deepest name there is, one slug short of being served at all.
      EdgeClient.Answer editor = client().get("editor.dev.nosuchproject.example.com", "/editor");
      assertEquals(503, editor.status());
      assertEquals("1", editor.headers().get("retry-after"));
      assertTrue(editor.body().contains("still reading the project log"), editor.body());
      assertNull(
          editor.line("upstream"), "nothing reaches an upstream while the answer is unknown");

      // And the project door, whose label would otherwise read as an application nobody routes.
      assertEquals(503, client().get("dev.nosuchproject.example.com", "/").status());
    } finally {
      projectSans.authoritative(true);
    }
  }

  @Test
  void theBarrierTouchesNoNameWhoseAnswerASlugCouldNotChange() throws Exception {
    // A restarted edge whose edge_project rows survived serves normally throughout, which is the
    // whole reason this is a per-name question rather than a second readiness gate: the only names
    // held are the ones that were going to 404 anyway.
    activateCi();
    activateProjects("prod");
    projectSans.authoritative(false);
    try {
      // A configured vhost, a published host, the apex, an environment's door, and the four-label
      // tier of a project this projection DOES know.
      assertEquals(
          "mirror-dev",
          client().get("ci.dev.acme.example.com", "/", token("dev")).line("upstream"));
      // The apex is answered rather than held — a 404 rather than the 503 the barrier writes,
      // because no slug this projection has yet to read could turn a name inside no project into
      // an application address.
      assertEquals(404, client().get("example.com", "/").status());
      assertEquals(404, client().get("dev.acme.example.com", "/anything").status());
      assertEquals(
          "editor-dev",
          client()
              .get("editor.dev." + PROJECT + ".example.com", "/editor", token("dev"))
              .line("upstream"));
    } finally {
      projectSans.authoritative(true);
    }
  }

  @Test
  void onceTheProjectionIsAuthoritativeTheSameNamesAre404Again() {
    // The barrier is a window, not a state: with the log read to its head an unknown slug is a slug
    // that does not exist, and the answer is the 404 that names the spelling which works.
    EdgeClient.Answer editor = client().get("editor.dev.nosuchproject.example.com", "/editor");
    assertEquals(404, editor.status());
    assertTrue(
        editor.body().contains("`nosuchproject` is not a project on this platform"), editor.body());

    EdgeClient.Answer door = client().get("dev.nosuchproject.example.com", "/");
    assertEquals(404, door.status());
    assertTrue(
        door.body().contains("`nosuchproject` is not a project on this platform"), door.body());
  }

  @Test
  void theDefaultEnvironmentsNavigationCarriesItsLabelLikeEveryOther() {
    // FLIPPED. These origins used to be written in the SHORT form — `http://example.com` and
    // `http://ci.example.com` — because the default environment's door was the apex. They are the
    // names the shell links to, so they have to be names that still resolve, and only the labelled
    // spelling does. The APEX is not asked here: it is inside no project, so it composes no
    // application name at all — see `theApexIsReadPositionallyNowAndIsStillADoor`.
    activateCi("prod");
    for (String requested : List.of("prod.acme.example.com", "ci.prod.acme.example.com")) {
      JsonObject document = new JsonObject(client().get(requested, "/main-navigation").body());
      assertEquals("prod", document.getString("environment"), requested);
      assertEquals("http://prod.acme.example.com", document.getString("origin"), requested);
      assertEquals(
          "http://ci.prod.acme.example.com",
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
    EdgeClient.Answer answer = client().get("dev.acme.example.com", "/");
    assertEquals(302, answer.status());
    assertEquals("http://projects.dev.acme.example.com/", answer.headers().get("location"));
  }

  @Test
  void anEnvLessProjectsDoorAndAppsComposeWithNoEnvironmentLabel() {
    // The other composition, and it is the same rule rather than a second one: `gizmo` has no
    // environment tier, so its own name IS the innermost door and its applications are one label in
    // front of it. Its apps are served in the DEFAULT environment, which is where the projection
    // for them has to be published.
    activateProjects("prod");
    activateCi("prod");

    EdgeClient.Answer landing = client().get(FLAT_PROJECT + ".example.com", "/");
    assertEquals(302, landing.status());
    assertEquals("http://projects.gizmo.example.com/", landing.headers().get("location"));

    JsonObject document =
        new JsonObject(
            client().get("ci." + FLAT_PROJECT + ".example.com", "/main-navigation").body());
    assertEquals("prod", document.getString("environment"));
    assertEquals("http://gizmo.example.com", document.getString("origin"));
    assertEquals("http://gizmo.example.com", document.getString("projectOrigin"));
    assertEquals(
        "http://ci.gizmo.example.com",
        document
            .getJsonObject("slots")
            .getJsonArray("services.details")
            .getJsonObject(0)
            .getString("origin"));
  }

  @Test
  void anEnvSupportingProjectsDoorComposesItsDefaultEnvironment() {
    // A project's door states no environment, so the name it sends a visitor to carries the default
    // one — the same environment the router itself reads that name as, which is what keeps the
    // redirect on a name this edge serves.
    activateProjects("prod");
    EdgeClient.Answer landing = client().get(PROJECT + ".example.com", "/");
    assertEquals(302, landing.status());
    assertEquals("http://projects.prod.acme.example.com/", landing.headers().get("location"));
  }

  @Test
  void theDoorHasNowhereToSendAnybodyUntilProjectsPublishesAHost() throws Exception {
    clearProjection();
    assertEquals(404, client().get("dev.acme.example.com", "/").status());
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
            .get("registry.dev.acme.example.com", "/artifacts/spa/", token("dev"))
            .headers()
            .get("cache-control"));
    // A content-hashed name is the one place immutable is correct — a new build names a new file —
    // so this one keeps the day it was given.
    assertEquals(
        "public, immutable, max-age=86400",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/spa/main-4RS6EA47.js", token("dev"))
            .headers()
            .get("cache-control"));
    // Unhashed and not the document either: a favicon replaced in place would otherwise outlive its
    // own build by a day.
    assertEquals(
        "no-cache",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/spa/favicon.ico", token("dev"))
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
            .get("registry.dev.acme.example.com", "/artifacts/spa/private", token("dev"))
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

  /**
   * The project set this suite routes with, from a clean table.
   *
   * <p>The rows are cleared first rather than merely added to: {@code ProjectSansTest} shares this
   * JVM and drives the same projection with hand-dated frames, and this projection is
   * last-writer-wins by {@code (occurredAt, eventId)} — so a row it left dated in the future would
   * make an ordinary create here a no-op, and the slug would simply not be there.
   */
  private void publishProject() throws java.sql.SQLException {
    try (java.sql.Connection connection = edgeDataSource.getConnection();
        java.sql.PreparedStatement delete =
            connection.prepareStatement("delete from edge_project")) {
      delete.executeUpdate();
    }
    projects.load(null);
    projects.apply(
        PROJECT, "p-routing", true, true, java.util.UUID.randomUUID().toString(), Instant.now());
    // A project with no tier of environments under it, which is the OTHER composition: its
    // applications are `<app>.<project>.<domain>`, served in the default environment, and its own
    // door is the innermost one there is. The platform's own project becomes one of these.
    projects.apply(
        FLAT_PROJECT,
        "p-routing-flat",
        true,
        false,
        java.util.UUID.randomUUID().toString(),
        Instant.now());
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
        client()
            .get("ci.dev.acme.example.com", "/artifacts/api/files", token("dev"))
            .line("upstream"));
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
    activateProjects("dev");
  }

  private void activateProjects(String environment) {
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-projects")
                .put("environmentName", environment)
                .put("browserHost", "projects")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/projects", upstream("qits.edge.apps.registry.hosts.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray().add(placement("system", "Overview", 1)))));
  }

  /**
   * An application the edge knows ONLY from the projection: {@code landing} has no {@code
   * qits.edge.apps.landing} entry of any kind, so HostEnvironments can only answer its name as an
   * unknown app, and it becomes an app route solely because {@code EdgeRouter.target()} rebuilds
   * the Route with the projection's label as the app. That is the second of the two ways a label
   * reaches the anonymous-read gate, and the one a public SSR landing page depends on.
   *
   * <p>Its endpoint points at the EDITOR stub deliberately, not mirror's. Every other exempted-read
   * assertion in this file reads {@code mirror-dev}, so answering {@code editor-dev} here is what
   * makes a 200 proof that the request reached the PROJECTION's own upstream rather than mirror's
   * configured route happening to answer.
   */
  private void activateLanding() {
    deployments.onFrame(
        frame(
            new JsonObject()
                .put("applicationName", "qits-landing")
                .put("environmentName", "dev")
                .put("browserHost", "landing")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(endpoint("/landing", upstream("qits.edge.apps.editor.hosts.dev"))))));
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
        client()
            .get("ci.dev.acme.example.com", "/deep/path/here?x=1&y=2", token("dev"))
            .line("uri"));
  }

  @Test
  void aPostBodyReachesTheUpstream() {
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.POST,
                "ci.dev.acme.example.com",
                "/api/thing",
                "hello edge",
                token("dev"));
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
          client()
              .send(method, "ci.dev.acme.example.com", "/thing", "x", token("dev"))
              .line("method"),
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
        client().send(HttpMethod.GET, "ci.dev.acme.example.com", "/thing", null, headers);

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
    String seen =
        client().get("ci.dev.acme.example.com", "/thing", token("dev")).upstreamHeader("Host");
    assertTrue(
        seen != null && seen.startsWith("ci.dev.acme.example.com"),
        "the upstream must see the name the client asked for, but saw: " + seen);
  }

  @Test
  void aResponseHeaderReachesTheClientUnchanged() {
    activateCi();
    assertEquals(
        "mirror-dev",
        client()
            .get("ci.dev.acme.example.com", "/thing", token("dev"))
            .headers()
            .get("x-upstream"));
  }

  // --- the forwarded headers -----------------------------------------------------------------

  @Test
  void theEdgeDescribesTheOriginalClient() {
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/thing", token("dev"));
    assertEquals("127.0.0.1", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("http", answer.upstreamHeader("X-Forwarded-Proto"));
    assertTrue(answer.upstreamHeader("X-Forwarded-Host").startsWith("ci.dev.acme.example.com"));
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
        client().send(HttpMethod.GET, "ci.dev.acme.example.com", "/thing", null, headers);

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
    EdgeClient.Streamed streamed =
        client().stream("ci.dev.acme.example.com", "/stream", token("dev"));

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
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", token("dev"));
    assertTrue(seen.lines().anyMatch("upstream=mirror-dev"::equals), seen);
  }

  @Test
  void aWebSocketUpgradeCarriesTheForwardedHeaders() {
    // The upgrade never reaches the interceptor chain — vertx-http-proxy short-circuits before
    // installing it — so this is a second code path with its own way of losing the headers.
    activateCi();
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", token("dev"));
    assertTrue(seen.lines().anyMatch("x-forwarded-for=127.0.0.1"::equals), seen);
    assertTrue(seen.lines().anyMatch("x-forwarded-proto=http"::equals), seen);
    assertTrue(
        seen.lines().anyMatch(l -> l.startsWith("x-forwarded-host=ci.dev.acme.example.com")), seen);
  }

  @Test
  void aWebSocketUpgradeStillCarriesTheClientsOwnHeaders() {
    // The edge strips nothing but the browser cookie on an upgrade either: an unrelated cookie is a
    // service's own and travels with the socket.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("Cookie", "q_session=abc");
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", headers);
    assertTrue(seen.lines().anyMatch("cookie=q_session=abc"::equals), seen);
  }

  @Test
  void aRefusedUpgradeAnswersTheUpstreamsOwnStatus() {
    // The upstream said no; the caller learns what it said, not a generic 502 — a workspace
    // service answering 403 on a terminal socket is an authorization answer, not an edge fault.
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(HttpMethod.GET, "ci.dev.acme.example.com", "/terminal/refused", null, upgrade());
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
          client()
              .send(
                  HttpMethod.GET, "ci.dev.acme.example.com", "/terminal/refused", null, upgrade());
      assertEquals(403, answer.status(), "attempt " + attempt);
    }
    EdgeClient.Answer plain = client().get("ci.dev.acme.example.com", "/anything", token("dev"));
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
        .header("Host", "dev.acme.example.com")
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
    EdgeClient.Answer answer =
        client().get("ci.dev.acme.example.com", "/queue/items", token("dev"));
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("/queue/items", answer.line("uri"));
  }

  // --- idp auth, terminated here ---------------------------------------------------------------

  @Test
  void anApplicationVhostRefusesAnAnonymousCallerWithTheDockerChallenge() {
    // The exact string docker parses to find its token endpoint. Getting it wrong fails the pull
    // with no message anywhere, which is why it is asserted whole rather than by substring.
    EdgeClient.Answer answer = client().get("registry.dev.acme.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        "Bearer realm=\"http://registry.dev.acme.example.com/token\",service=\"registry.dev.acme.example.com\"",
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
    EdgeClient.Answer answer = client().get("registry.dev.acme.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        List.of(
            "Bearer realm=\"http://registry.dev.acme.example.com/token\",service=\"registry.dev.acme.example.com\"",
            "Basic realm=\"registry.dev.acme.example.com\""),
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
          client()
              .send(HttpMethod.PUT, "registry.dev.acme.example.com", "/v2/blob", "x", credential);
      assertEquals(401, answer.status());
      List<String> challenges = answer.headerValues("www-authenticate");
      assertEquals(2, challenges.size(), challenges.toString());
      assertTrue(challenges.get(0).startsWith("Bearer realm="), challenges.toString());
      assertEquals("Basic realm=\"registry.dev.acme.example.com\"", challenges.get(1));
    }
  }

  @Test
  void anApplicationVhostRefusesATokenSignedBySomebodyElse() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
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
                "registry.dev.acme.example.com",
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
                "registry.dev.acme.example.com",
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
        client().get("registry.dev.acme.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals(
        401, client().get("registry.prod.acme.example.com", "/v2/", token("dev")).status());
    assertEquals(
        401, client().get("registry.dev.acme.example.com", "/v2/", token("prod")).status());
  }

  @Test
  void aTokenNamingEveryEnvironmentsAudienceOpensEachOfThem() {
    // What idp actually mints when the grant asks for no audience: the client's whole allowed list.
    Map<String, String> whole =
        bearer(
            TestTokens.valid(
                issuer(), List.of(StubGateways.audience("dev"), StubGateways.audience("prod"))));
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", whole).line("upstream"));
    assertEquals(
        "registry-prod",
        client().get("registry.prod.acme.example.com", "/v2/", whole).line("upstream"));
  }

  @Test
  void thePlatformAudienceOpensTheVhostOnEveryTier() {
    // A person's command-line token names only `qits-platform`. It must reach every service, and
    // its
    // roles are the permission. The suite runs on the shipped default of the setting.
    Map<String, String> platform =
        bearer(TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE)));
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", platform).line("upstream"));
    assertEquals(
        "registry-prod",
        client().get("registry.prod.acme.example.com", "/v2/", platform).line("upstream"));
  }

  @Test
  void gitsOauth2BasicFollowsThePlatformAudienceRule() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(
                    "oauth2", TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE))));
    assertEquals("registry-dev", answer.line("upstream"));
    assertTrue(
        answer.body().contains("header:authorization=Bearer "),
        "the token goes on as a Bearer: " + answer.body());
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic("oauth2", TestTokens.valid(issuer(), List.of("somebody-else"))))
            .status());
  }

  @Test
  void aClientCommissionedForThePlatformAudienceOpensTheVhostWithBasic() {
    // The Basic client-credential path: the minted token carries only the platform audience.
    for (String environment : List.of("dev", "prod")) {
      assertEquals(
          "registry-" + environment,
          client()
              .get(
                  "registry." + environment + "." + PROJECT + ".example.com",
                  "/v2/",
                  basic(StubGateways.PLATFORM_ID, StubGateways.PLATFORM_SECRET))
              .line("upstream"));
    }
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
                "mirror.dev.acme.example.com",
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
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", headers);
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
    assertEquals(
        "mirror-dev", client().get("mirror.dev.acme.example.com", "/v2/").line("upstream"));
    assertEquals(
        "mirror-prod", client().get("mirror.prod.acme.example.com", "/v2/").line("upstream"));
  }

  @Test
  void anExemptedAppVhostServesAnAnonymousHead() {
    // The other reading method, and docker uses it for every blob it checks before pulling. A HEAD
    // answer carries no body, so the upstream marker is read from the header the stub also sets.
    EdgeClient.Answer answer =
        client().send(HttpMethod.HEAD, "mirror.dev.acme.example.com", "/v2/blob", null, Map.of());
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
          client().send(method, "mirror.dev.acme.example.com", "/v2/blob", "x", Map.of());
      assertEquals(401, answer.status(), method + " must still be gated");
      assertEquals(
          "Bearer realm=\"http://mirror.dev.acme.example.com/token\",service=\"mirror.dev.acme.example.com\"",
          answer.headers().get("www-authenticate"));
      assertNull(answer.line("upstream"), method + " must not have reached the application");
    }
  }

  @Test
  void anAuthenticatedWriteOnAnExemptedAppVhostPasses() {
    // The other half: the exemption is a way past the gate, not a replacement for it.
    EdgeClient.Answer answer =
        client()
            .send(HttpMethod.POST, "mirror.dev.acme.example.com", "/v2/blob", "x", token("dev"));
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("POST", answer.line("method"));
    assertEquals("x", answer.line("body"));
  }

  @Test
  void aMachineVhostNeverReceivesTheBrowserSessionCookieButKeepsOtherCookies() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
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
    assertEquals(401, client().get("registry.dev.acme.example.com", "/v2/").status());
    assertEquals(
        401,
        client()
            .send(HttpMethod.HEAD, "registry.dev.acme.example.com", "/v2/", null, Map.of())
            .status());
  }

  @Test
  void anUnknownAppLabelIsStill404EvenWhereReadsAreOpen() {
    // The exemption is applied AFTER the label resolves, so it cannot turn a typo into a route.
    // `mirro` is one letter from an app whose reads are open and is still nobody's name.
    assertEquals(404, client().get("mirro.dev.acme.example.com", "/v2/").status());
  }

  @Test
  void aProjectedExemptedAppVhostServesAnAnonymousGetFromItsOwnUpstream() {
    // The second route to the same gate. `landing` is named in qits.edge.auth.anonymous-read-apps
    // and configured nowhere, so HostEnvironments answers it as an unknown app; it reaches the
    // exemption at all only because EdgeRouter.target() rebuilds the Route with the projection's
    // label as the app, which is what makes toApp() true. This is the path a public SSR landing
    // page depends on, and until now nothing exercised it. `editor-dev` rather than `mirror-dev`
    // is the load-bearing half of the assertion: it is the projection's OWN upstream answering.
    activateLanding();
    EdgeClient.Answer answer = client().get("landing.dev.acme.example.com", "/landing/");
    assertEquals(200, answer.status());
    assertEquals("editor-dev", answer.line("upstream"));
  }

  @Test
  void theProjectedExemptedAppVhostIsRoutedByNothingButTheProjection() {
    // The guard against this coverage silently degrading into a second copy of the `mirror` case:
    // if anyone gives `landing` a qits.edge.apps entry it becomes a CONFIGURED label, the test
    // above stops proving anything new, and this test is what fails instead.
    assertFalse(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.edge.apps.landing.host-pattern", String.class)
            .isPresent(),
        "`landing` must stay unconfigured or it is no longer the projected case");
    assertFalse(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.edge.apps.landing.hosts.dev", String.class)
            .isPresent(),
        "`landing` must stay unconfigured or it is no longer the projected case");

    // And the sharpest proof that configuration is not quietly supplying the route: with the
    // projection empty — @BeforeEach clears it — the very same anonymous read is nobody's name.
    assertEquals(404, client().get("landing.dev.acme.example.com", "/landing/").status());

    activateLanding();
    assertNotNull(
        routes.serviceHost("dev", "landing"),
        "the projection is the only thing on the estate that knows this name");
  }

  @Test
  void aProjectedAnonymousReadArrivesWithTheReservedNamespaceEmpty() {
    // Nobody vouched for anybody on this path, so there is no trusted identity to write — and
    // doing nothing is exactly what would let a stranger's `X-Qits-User: admin` travel to a
    // service that believes it. The strip cannot be conditional on there being a real identity to
    // replace the forgery with: it is where there is none that a forgery survives. The rule is the
    // whole `X-Qits-` prefix, not the three names the edge happens to write, so a name nobody has
    // invented yet is stripped too.
    activateLanding();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "landing.dev.acme.example.com",
                "/landing/",
                null,
                Map.of(
                    "X-Qits-User", "admin",
                    "X-Qits-User-Id", "00000000-0000-0000-0000-000000000000",
                    "X-Qits-Roles", "qits:root",
                    "X-Qits-Something-Nobody-Invented-Yet", "whatever"));

    assertEquals(200, answer.status());
    assertEquals("editor-dev", answer.line("upstream"));
    assertNull(answer.upstreamHeader("X-Qits-User"));
    assertNull(answer.upstreamHeader("X-Qits-User-Id"));
    assertNull(answer.upstreamHeader("X-Qits-Roles"));
    assertNull(answer.upstreamHeader("X-Qits-Something-Nobody-Invented-Yet"));
  }

  @Test
  void aProjectedExemptedAppVhostStillGatesEveryWritingMethod() {
    // The exemption is method-scoped on the projected path too: rebuilding the Route makes the
    // label an app, it does not make the vhost open. A projected label is learned at RUNTIME from
    // an event, so this is the half that keeps a deployment from publishing itself a writable
    // door. Status and the absent upstream only: the challenge a projected vhost carries is built
    // from the request authority rather than from a per-app entry it does not have, so pinning
    // mirror's expected realm string here would assert the wrong thing.
    activateLanding();
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
      EdgeClient.Answer answer =
          client().send(method, "landing.dev.acme.example.com", "/landing/", "x", Map.of());
      assertEquals(401, answer.status(), method + " must still be gated");
      assertNull(answer.line("upstream"), method + " must not have reached the application");
    }
  }

  @Test
  void anotherProjectedAppThatWasNotNamedStillRefusesAnAnonymousRead() {
    // The "one name over" property, for the dynamic path.
    // `anAppThatWasNotNamedStillRefusesAnAnonymousRead` proves it for a configured label; this is
    // the same property for a projected one, and it matters more here, because the projection is
    // attacker-adjacent: a label the edge learned at runtime must never inherit the exemption of
    // another label that happens to have been named.
    activateLanding();
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/ci/api/runs");
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"), "and it reached no upstream");
  }

  // --- the docker token endpoint ----------------------------------------------------------------

  @Test
  void theTokenEndpointAsksForTheStoredLoginCredential() {
    EdgeClient.Answer answer =
        client().get("registry.dev.acme.example.com", "/token?service=x&scope=y");
    assertEquals(401, answer.status());
    // Basic ALONE, and that is the difference from a gated request: this is the endpoint that SELLS
    // bearer tokens, so a Bearer challenge here would point a client back at where it already is.
    assertEquals(
        List.of("Basic realm=\"registry.dev.acme.example.com\""),
        answer.headerValues("www-authenticate"));
  }

  @Test
  void theTokenEndpointBrokersAGrantAndHandsBackADockerStyleToken() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token?service=registry.dev.acme.example.com&scope=repository:qits/x:pull",
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
        client()
            .get("registry.dev.acme.example.com", "/token", basic("nobody", "nothing"))
            .status());
  }

  @Test
  void theWholeDockerFlowRoundTrips() {
    // Challenge, token, retry — the three hops a `docker pull` makes, in order, with no shortcut.
    EdgeClient.Answer challenged = client().get("registry.dev.acme.example.com", "/v2/");
    assertEquals(401, challenged.status());
    String realm = challenged.headers().get("www-authenticate").split("realm=\"")[1].split("\"")[0];
    assertTrue(realm.endsWith("/token"), realm);

    String issued =
        new JsonObject(
                client()
                    .get(
                        "registry.dev.acme.example.com",
                        "/token?service=registry.dev.acme.example.com",
                        basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
                    .body())
            .getString("token");

    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", bearer(issued)).line("upstream"));
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
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    EdgeClient.Answer written =
        client()
            .send(
                HttpMethod.POST,
                "registry.dev.acme.example.com",
                "/v2/blob",
                "x",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals("registry-dev", written.line("upstream"), "a write is the same decision");
    assertEquals("x", written.line("body"));
  }

  @Test
  void anAcceptedClientIdAndSecretReachTheUpstreamAsABearerAndNotAsThemselves() {
    // The whole of the "only CI may publish" change at this hop. A service cannot check a secret,
    // so a relayed pair tells it nothing about WHICH commissioned client is calling — and hands it
    // a secret it has no business holding. What travels is the token the edge validated, exactly
    // as it does for git's `oauth2:` pair, so the service builds the roles from the JWT itself.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals("registry-dev", answer.line("upstream"));
    String forwarded = answer.upstreamHeader("Authorization");
    assertNotNull(forwarded, "an accepted request must arrive with a credential: " + answer.body());
    assertTrue(forwarded.startsWith("Bearer "), forwarded);
    assertEquals(
        List.of(StubGateways.audience("dev"), StubGateways.audience("prod")),
        SignedJwt.parse(forwarded.substring("Bearer ".length())).audiences().getList(),
        "and it is the token idp minted for this client, not something the edge made up");
    // The two ways the secret could still be there: the header, and the base64 of the pair.
    assertFalse(
        answer.body().contains(StubGateways.CLIENT_SECRET),
        "the client secret stops at the edge: " + answer.body());
    assertFalse(
        answer.body().contains("header:authorization=Basic"),
        "and nothing upstream sees a Basic credential at all: " + answer.body());
  }

  @Test
  void aCacheHitForwardsTheTokenItRememberedRatherThanNothingAtAll() throws Exception {
    // The trap the cache sets for this change: a remembered acceptance used to be a verdict and
    // nothing else, so a hit would have had no token to write — and a request that was ACCEPTED
    // would reach the service with no credential on it at all. Anonymously, silently.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.grants();
    EdgeClient.Answer cold =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 1, StubGateways.grants(), "the first request spends the credential");
    EdgeClient.Answer hit =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 1, StubGateways.grants(), "and the second is served from the cache");
    assertEquals("registry-dev", hit.line("upstream"));
    assertNotNull(
        hit.upstreamHeader("Authorization"),
        "a cache hit must forward a credential, not strip one: " + hit.body());
    assertEquals(
        cold.upstreamHeader("Authorization"),
        hit.upstreamHeader("Authorization"),
        "the same token, because the cache holds the token and not merely a yes");
  }

  @Test
  void aTokenTooCloseToItsExpiryIsMintedAgainRatherThanServedFromTheCache() {
    // What is cached is what will be FORWARDED, and a token is validated one hop further in, a
    // moment later, against another process' clock. So an entry is retired a minute before its
    // token's own `exp`, and a client whose tokens are shorter-lived than that margin is simply
    // one whose credential is spent on every request — never one served a token nobody would take.
    int before = StubGateways.grants();
    for (int request = 1; request <= 2; request++) {
      EdgeClient.Answer answer =
          client()
              .get(
                  "registry.dev.acme.example.com",
                  "/v2/",
                  basic(StubGateways.BRIEF_ID, StubGateways.BRIEF_SECRET));
      assertEquals("registry-dev", answer.line("upstream"), "request " + request);
      String forwarded = answer.upstreamHeader("Authorization");
      assertNotNull(forwarded, "request " + request + ": " + answer.body());
      assertTrue(forwarded.startsWith("Bearer "), forwarded);
    }
    assertEquals(
        before + 2,
        StubGateways.grants(),
        "a token inside the margin is never a belief worth keeping, so both requests ask idp");
  }

  @Test
  void aCredentialWhoseRemintFailsIsRefusedRatherThanForwardedBare() throws Exception {
    // The other half of the same invariant, from the failure side: once the belief has run out
    // there is no token to forward, and the only two answers left are a fresh one or a refusal.
    // Passing the request through with no credential would be the precise defect this closes.
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    Thread.sleep(cacheTtlMs() + 400);
    StubGateways.idpDown();
    try {
      EdgeClient.Answer answer =
          client()
              .get(
                  "registry.dev.acme.example.com",
                  "/v2/",
                  basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
      assertEquals(401, answer.status(), answer.body());
      assertNull(
          answer.line("upstream"),
          "the request reached no service — a check that cannot be made refuses");
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void aBasicCredentialCarriesTheSameAudienceDemandAsABearer() {
    // The whole point of validating rather than trusting: the client is real, its secret is right,
    // and it is commissioned for an audience this vhost does not demand.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
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
        401,
        client().get("registry.dev.acme.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(
        401,
        client().get("registry.dev.acme.example.com", "/v2/", basic("nobody", "nothing")).status());
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
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    assertEquals(before + 1, StubGateways.grants(), "the first request spends the credential");

    client()
        .get(
            "registry.dev.acme.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    client()
        .get(
            "registry.prod.acme.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(
        before + 1,
        StubGateways.grants(),
        "a remembered credential asks nobody — including on the other tier's vhost");

    Thread.sleep(cacheTtlMs() + 400);
    client()
        .get(
            "registry.dev.acme.example.com",
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
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                Map.of("Authorization", "Basic !!not-base64"))
            .status());
    assertEquals(
        401,
        client()
            .get("registry.dev.acme.example.com", "/v2/", Map.of("Authorization", "Basic "))
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
            .get(
                "mirror.dev.acme.example.com",
                "/v2/",
                Map.of("Authorization", "Basic !!not-base64"))
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
                  "registry.dev.acme.example.com",
                  "/token?service=registry.dev.acme.example.com",
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
                "registry.dev.acme.example.com",
                "/token?service=registry.dev.acme.example.com",
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
                "registry.dev.acme.example.com",
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
                    "registry.dev.acme.example.com",
                    "/token?service=registry.dev.acme.example.com",
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
