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
 * <p>The document is {@code environment}, {@code origin} and {@code slots}, and nothing else. There
 * is no flat list and no synthesized {@code Home}: the environment's own door is qits-projects'
 * {@code system} entry, which is a deployment fact like every other entry here.
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
                .put("position", placement.position()));
      }
      slots.put(slot, entries);
    }

    context
        .response()
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(
            new JsonObject()
                .put("environment", environment)
                .put("origin", authority.origin())
                .put("slots", slots)
                .encode());
  }
}
