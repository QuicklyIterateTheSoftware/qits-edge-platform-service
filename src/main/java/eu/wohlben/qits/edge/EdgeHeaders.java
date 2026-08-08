package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

/**
 * Everything the edge does to a request, which is two things and nothing else.
 *
 * <p><b>1. It keeps the client's Host.</b> {@code vertx-http-proxy} leaves a proxied request's
 * authority unset, and the Vert.x client then fills the {@code Host} header in from the socket it
 * opened — so without this the environment gateway would see {@code prod-qits-gateway:8080} for
 * every request and never learn the name the browser typed. That name is what any redirect, cookie
 * domain or absolute URL it generates has to be built from, so losing it breaks all three at once
 * and leaves nothing in a log to say why. Measured on Vert.x 4.5.26 against a real upstream, not
 * assumed: {@code EdgeRoutingTest.theOriginalHostReachesTheUpstream} is the regression.
 *
 * <p><b>2. It describes the original client</b> with three headers, each set <b>only when
 * absent</b>: {@code X-Forwarded-For}, {@code X-Forwarded-Host} and {@code X-Forwarded-Proto}.
 *
 * <p>Set-if-absent, not set. qits-gateway overwrites the same names because it is the outermost hop
 * and an inbound value can only be client-supplied. The edge is not always outermost: a TLS
 * terminator may sit in front of it, and that terminator is the only hop that can tell the truth
 * about {@code https}. Overwriting there would replace a true value with a false one. What it costs
 * is that a direct client can supply its own — which is why nothing may make a trust decision on
 * these three, and nothing does: they are diagnostics and link generation, while authentication
 * terminates at the environment gateway on its own evidence.
 *
 * <p><b>Nothing else is touched.</b> No header is stripped, no header is added, no path is
 * rewritten, no body is read. {@code X-Qits-*} hygiene in particular is <i>not</i> done here: it
 * belongs to the component that asserts those headers, which is the environment gateway. Doing it
 * twice would put one contract in two repositories, and the copy that is not next to the injection
 * is the copy that rots.
 *
 * <p>{@code ProxyInterceptor} has no single abstract method, so this is a class rather than a
 * lambda. Nothing about it is environment-specific, so one instance is shared by every proxy.
 */
final class EdgeHeaders implements ProxyInterceptor {

  static final String FOR = "X-Forwarded-For";
  static final String HOST = "X-Forwarded-Host";
  static final String PROTO = "X-Forwarded-Proto";

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest request = context.request();
    HttpServerRequest inbound = request.proxiedRequest();
    // Job 1. The socket still goes to the configured origin — this is only what the request SAYS.
    if (inbound.authority() != null) {
      request.setAuthority(inbound.authority());
    }
    applyForwarded(request.headers(), inbound);
    return context.sendRequest();
  }

  /**
   * Job 2 on a request's own header map.
   *
   * <p>Called directly by {@link EdgeRouter} for a WebSocket handshake, which never reaches the
   * interceptor chain: {@code vertx-http-proxy}'s {@code ReverseProxy.handle} branches to its
   * upgrade path and returns before installing it. Without this call an upgraded connection — every
   * interactive terminal on the platform — would arrive at the environment gateway with no record
   * of who opened it.
   *
   * <p><b>Job 1 has no equivalent on that path</b>, and the gap is worth knowing: the upgrade path
   * rebuilds the handshake with the client's own {@code Host} dropped, and there is no hook before
   * it. So an upstream reads a socket's original host name from {@code X-Forwarded-Host}, not from
   * {@code Host}. It costs nothing today because a handshake's Host is a protocol formality rather
   * than something an environment gateway routes on.
   */
  static void applyForwarded(MultiMap headers, HttpServerRequest inbound) {
    SocketAddress remote = inbound.remoteAddress();
    if (remote != null && remote.hostAddress() != null && !headers.contains(FOR)) {
      headers.set(FOR, remote.hostAddress());
    }
    HostAndPort authority = inbound.authority();
    if (authority != null && !headers.contains(HOST)) {
      // The authority as the client wrote it, port included when it named one — a link built from a
      // name whose port was dropped points at a port nothing listens on.
      headers.set(
          HOST,
          authority.port() > 0 ? authority.host() + ":" + authority.port() : authority.host());
    }
    if (!headers.contains(PROTO)) {
      headers.set(PROTO, inbound.scheme() == null ? "http" : inbound.scheme());
    }
  }
}
