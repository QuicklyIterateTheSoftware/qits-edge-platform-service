package eu.wohlben.qits.edge;

import io.restassured.RestAssured;

/**
 * The one {@link EdgeClient} every story in this catalogue drives the launched front door with.
 *
 * <p><b>Not a field initialiser, and not one per class.</b> {@code RestAssured.port} is written by
 * the integration-test extension once the process is up, so a static initialiser would read it
 * before that; and a client built per class would be seven Vert.x instances where one will do.
 *
 * <p><b>It rebuilds itself when the port moves.</b> Whether failsafe reuses one launched artifact
 * across the classes of a shared profile or starts a fresh one per class is the harness's decision,
 * not this catalogue's — so the port is compared rather than assumed. A client left pointing at a
 * port the previous launch had would fail with a connection error that says nothing about the edge.
 */
final class StoryEdge {

  private StoryEdge() {}

  private static EdgeClient client;

  private static int port;

  /** The client for the process that is up right now. */
  static synchronized EdgeClient client() {
    if (client != null && port != RestAssured.port) {
      close();
    }
    if (client == null) {
      port = RestAssured.port;
      client = new EdgeClient(port);
    }
    return client;
  }

  /** Give the Vert.x instance back. Every story class calls this from its own {@code @AfterAll}. */
  static synchronized void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }
}
