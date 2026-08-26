package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.http.HttpClientOptions;
import org.junit.jupiter.api.Test;

/**
 * The proxy client's two timeouts, which pull in opposite directions and are each a real outage
 * when they are wrong.
 *
 * <p>Framework-free on purpose: the options object is what {@code EdgeRouter.init} hands {@code
 * createHttpClient}, so reading it back proves the wiring without booting the application — and
 * without the second Quarkus start that {@code EdgeRoutingTest} cannot afford.
 *
 * <p>Behaviour is not asserted here. A real connect timeout needs an address that black-holes the
 * SYN, and a blackhole answers differently in different network namespaces — the flip proof on
 * swarm is where that belongs.
 */
class EdgeProxyClientOptionsTest {

  @Test
  void theConnectTimeoutIsBoundedSoAnUnreadyGatewayFails502Fast() {
    // Vert.x defaults to 60 000. Under swarm a name resolves to a VIP with no live task behind it,
    // so the connection is dropped rather than refused and the default hangs the request for a
    // full minute before the 502 — at the outermost hop, where a browser is waiting.
    assertEquals(5000, EdgeRouter.proxyClientOptions(5000).getConnectTimeout());
  }

  @Test
  void theIdleTimeoutStaysZero() {
    // The opposite direction, and the one that must never be "tidied" to match the line above:
    // zero is what keeps a terminal socket, an SSE channel and a multi-minute layer push alive.
    assertEquals(0, EdgeRouter.proxyClientOptions(5000).getIdleTimeout());
  }

  @Test
  void thePoolIsWideEnoughForAConcurrentLayerPush() {
    // Vert.x pools per origin and defaults to five, and every request for an environment shares
    // one origin here, so one `docker push` would starve that whole environment.
    assertEquals(64, EdgeRouter.proxyClientOptions(5000).getMaxPoolSize());
  }

  @Test
  void connectionsAreReused() {
    // Keep-alive is the default; stated in the options and stated here, because an edge that
    // reconnected per request would turn every hop into a handshake.
    HttpClientOptions options = EdgeRouter.proxyClientOptions(5000);
    assertEquals(true, options.isKeepAlive());
  }

  @Test
  void theWaitQueueIsBoundedSoAnExhaustedPoolFailsInsteadOfHangingSilently() {
    // Vert.x defaults the wait queue to unbounded. With 64 slots gone — the leak this bound
    // backstops held all 64 for a day — every further request to that origin queued forever with
    // nothing logged: the whole vhost hung, plain document GETs included. Bounded, the excess
    // fails immediately and the origin provider's log line names the origin.
    assertEquals(256, EdgeRouter.proxyClientOptions(5000).getMaxWaitQueueSize());
  }

  @Test
  void anOriginRequestIsBoundedWhileItWaitsForAPoolSlot() {
    // RequestOptions.setConnectTimeout bounds the whole acquisition, pool wait included — the
    // client options' connect timeout only bounds the TCP connect once a slot is free. Without
    // this, a request that got INTO the bounded queue would still wait there indefinitely.
    assertEquals(
        EdgeRouter.ACQUIRE_TIMEOUT_MS,
        EdgeRouter.originRequestOptions(new Upstream("gateway", 8080)).getConnectTimeout());
  }

  @Test
  void anOriginRequestCarriesTheFixedAddress() {
    // The same SSRF posture as the proxies' registration: the server address is the configured
    // upstream's, set before any request exists, and no client-supplied character reaches it.
    var options = EdgeRouter.originRequestOptions(new Upstream("gateway", 8080));
    assertEquals("gateway", options.getServer().host());
    assertEquals(8080, options.getServer().port());
  }
}
