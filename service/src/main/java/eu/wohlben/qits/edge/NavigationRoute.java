package eu.wohlben.qits.edge;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The platform navigation, derived from the active deployment projection rather than a gateway enum
 * — on every vhost, because every service's shell renders the same tree.
 *
 * <p><b>Slots, not a list.</b> Each entry says WHERE it hangs in the shell's tree and the shell
 * decides what hangs there; the edge knows nothing about projects or repositories. Every slot of
 * the closed vocabulary is present, empty ones included, so a shell iterates the document rather
 * than a copy of the vocabulary.
 *
 * <p><b>A slot is an ARRAY, and one application may fill several of its entries.</b> The loop below
 * copies every placement of a slot in the order {@link EdgeRoutes#navigation} sorted it — slot,
 * position, label, application — and nothing here is keyed by application, so qits-workspaces'
 * Workspaces and Editor rows come out as two entries of the project node with one origin and two
 * subpaths. A repeated position is an ordinary tie broken by label, whether the two entries belong
 * to one application or to two.
 *
 * <p>The document is {@code environment}, {@code origin}, {@code projectOrigin}, {@code slots} and
 * {@code applications}, and nothing else. There is no flat list and no synthesized {@code Home}:
 * the environment's own door is qits-projects' {@code system} entry, which is a deployment fact
 * like every other entry here. {@code origin} is that door — it names the environment, and the door
 * serves nothing else.
 *
 * <p><b>{@code projectOrigin} is the origin a per-project name is built on</b>: prefix {@code
 * <app>.} onto its authority and that is where that application serves this project, {@code
 * https://editor.dev.acme.example.com}. ONE label — the project label is inside the authority
 * already, because a name is read right to left and every address this edge composes is inside a
 * project. It is equal to {@code origin} and is published anyway, because it is the FIELD that is
 * the contract rather than the value. It exists because the client side derived this name itself
 * twice and shipped two domain-derivation bugs doing it (editor-origin.ts): the server states the
 * authority, and the client's whole job is to put {@code editor.} in front of it.
 *
 * <p><b>An entry's origin is null on a name that is inside no project</b> — the apex, an address
 * literal, a name outside the domain — because an application address needs a project label and
 * such a name carries none; a link that would 404 is worse than an absent one. {@code origin} and
 * {@code projectOrigin} are then the canonical origin, which is a door like the name asked on. No
 * shell reads the document there: every one of those names is a door, and a door serves no SPA.
 *
 * <p>{@code applications} is per-application metadata rather than a placement: one object per
 * application that published an api-docs path, keyed by application name. The path is served on the
 * application's OWN host, so a shell page that knows which repository it shows composes it against
 * that entry's origin rather than against the door.
 */
@ApplicationScoped
public class NavigationRoute {

  static final String PATH = "/main-navigation";

  @Inject EdgeRoutes routes;
  @Inject EdgeRouter edgeRouter;
  @Inject DeploymentProjectionBootstrap projectionBootstrap;

  void register(@Observes Router router) {
    router
        .route(PATH)
        .method(HttpMethod.GET)
        .method(HttpMethod.HEAD)
        .order(100)
        .handler(this::handle);
  }

  private void handle(RoutingContext context) {
    if (!projectionBootstrap.authoritative()) {
      // Navigation is itself a projection response. Returning an incomplete list as 200 makes the
      // UI silently lose services during an edge restart, which is harder to recover from than a
      // short, explicit retry.
      context.response().setStatusCode(503).putHeader(HttpHeaders.RETRY_AFTER, "1").end();
      return;
    }
    String environment = edgeRouter.environment(context.request());
    EnvironmentAuthority authority = edgeRouter.authorityOf(context.request());
    List<EdgeRoutes.NavigationPlacement> placements = routes.navigation(environment);

    JsonObject slots = new JsonObject();
    for (String slot : EdgeRoutes.SLOTS) {
      JsonArray entries = new JsonArray();
      for (EdgeRoutes.NavigationPlacement placement : placements) {
        if (!placement.slot().equals(slot)) {
          continue;
        }
        entries.add(
            new JsonObject()
                .put("app", placement.application())
                .put("label", placement.label())
                .put("host", placement.host())
                .put(
                    "origin",
                    placement.host() == null
                        ? authority.origin()
                        : authority.hostOrigin(placement.host()))
                // The application's primary route, on a HOSTED entry as well: it is what the shell
                // renders a not-yet-flipped application under, so an application stays in the
                // sidebar through the whole rollout window rather than appearing when it flips.
                .put("path", placement.primaryPath())
                .put("position", placement.position())
                // The view this entry opens, relative to the scope the shell composes. Null is
                // every entry declared before subpaths existed: the application's root.
                .put("subpath", placement.subpath()));
      }
      slots.put(slot, entries);
    }

    JsonObject applications = new JsonObject();
    routes
        .apiDocs(environment)
        .forEach(
            (application, apiDocsPath) ->
                applications.put(application, new JsonObject().put("apiDocs", apiDocsPath)));

    context
        .response()
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(
            new JsonObject()
                .put("environment", environment)
                .put("origin", authority.origin())
                .put("projectOrigin", authority.origin())
                .put("slots", slots)
                .put("applications", applications)
                .encode());
  }
}
