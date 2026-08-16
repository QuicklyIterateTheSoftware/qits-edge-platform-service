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

/**
 * The platform navigation, derived from the active deployment projection rather than a gateway
 * enum.
 */
@ApplicationScoped
public class NavigationRoute {

  static final String PATH = "/main-navigation";
  static final String HOME_LABEL = "Home";

  @Inject EdgeRoutes routes;
  @Inject EdgeRouter edgeRouter;
  @Inject DeploymentProjectionBootstrap projectionBootstrap;

  record Link(String label, String href) {}

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
    JsonArray links = new JsonArray();
    for (Link link : routes.navigation(environment)) {
      links.add(new JsonObject().put("label", link.label()).put("href", link.href()));
    }
    context
        .response()
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(new JsonObject().put("links", links).encode());
  }
}
