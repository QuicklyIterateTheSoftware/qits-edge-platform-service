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
import java.util.List;

/**
 * Everything the edge does to a request: two things always, and a third while the session gate is
 * on.
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
 * these three, and nothing does: they are diagnostics and link generation. What authentication the
 * edge does make a decision on is the bearer token, which is signed — see {@code EdgeAuth}.
 *
 * <p><b>3. It keeps the {@code X-Qits-*} namespace honest</b>, but only on the environment vhost
 * and only while {@link SessionsConfig#enabled()} — see {@link #applyIdentity}. Until the edge
 * asserted an identity of its own, this hygiene belonged to the component that asserts those
 * headers, which was the environment gateway alone; the edge terminating browser sessions is what
 * moved a copy of it here. The gateway still does its own, and has to: a request may reach it from
 * qits-net without passing this process at all.
 *
 * <p><b>Nothing else is touched.</b> No other header is stripped, no path is rewritten, no body is
 * read. {@code Authorization} and every custom header pass through as they arrived. The one
 * exception is the named browser-session cookie on a machine vhost; {@link #stripCookie} removes
 * only that pair and preserves the rest.
 *
 * <p>{@code ProxyInterceptor} has no single abstract method, so this is a class rather than a
 * lambda. Nothing about it is environment-specific, so one instance is shared by every proxy.
 */
final class EdgeHeaders implements ProxyInterceptor {

  static final String FOR = "X-Forwarded-For";
  static final String HOST = "X-Forwarded-Host";
  static final String PROTO = "X-Forwarded-Proto";

  /**
   * The platform's reserved header namespace: what a hop ASSERTS about a request and what every
   * service behind it believes unconditionally. The strip rule is the same prefix, which is the
   * whole point — an enumerated list's failure mode is adding a trusted header and forgetting to
   * extend it, a silent, additive mistake no test naturally catches. The spelling is
   * qits-gateway's, because it is the same contract one hop further in.
   */
  static final String RESERVED_PREFIX = "X-Qits-";

  /** The principal NAME — what an upstream writes into an audit column. */
  static final String USER = RESERVED_PREFIX + "User";

  /** The stable subject id, which outlives a rename. */
  static final String USER_ID = RESERVED_PREFIX + "User-Id";

  /**
   * The role strings, comma-separated. New with browser sessions, and nothing downstream is obliged
   * to read it yet — it is asserted from the start because adding a trusted header later means
   * re-proving the strip rule.
   */
  static final String ROLES = RESERVED_PREFIX + "Roles";

  /**
   * Whether a header name belongs to the reserved namespace. Case-insensitive: header names are,
   * and a client sending {@code x-qits-user} must be treated exactly like one sending {@code
   * X-Qits-User}.
   */
  static boolean isReserved(String name) {
    return name != null
        && name.length() > RESERVED_PREFIX.length()
        && name.regionMatches(true, 0, RESERVED_PREFIX, 0, RESERVED_PREFIX.length());
  }

  /**
   * Drop every inbound {@code X-Qits-*} header, then assert the session's own — the
   * strip-then-inject of the forward-auth contract, on one request's header map.
   *
   * <p><b>Both halves stay in this one method, and that is the rule rather than a tidiness.</b> The
   * forged header and the trusted one have the same name, so the code that writes the trusted value
   * has to be downstream of the code that removes the forged one; splitting them puts a security
   * property in the hands of a call order somewhere else.
   *
   * <p><b>Called on the INBOUND request's map</b>, by {@link EdgeRouter} at the single point where
   * a request leaves this process for an upstream. That is what covers the two paths at once: an
   * ordinary proxied request copies these headers, and a WebSocket upgrade — the edge's own path,
   * {@code EdgeWebSocketUpgrade}, which never installs the interceptor chain, so {@link
   * #handleProxyRequest} never runs for it — is forwarded from this same map. An upgrade was the
   * way a forged {@code X-Qits-User} reached the gateway through the front door before this
   * existed.
   *
   * @param session the validated session whose identity is asserted, or null to strip and assert
   *     nothing — an anonymous request, or a machine credential, whose identity is in its token
   */
  static void applyIdentity(MultiMap headers, EdgeSessions.Session session) {
    // Snapshot the names first: removing from the map while iterating its own name view would
    // otherwise skip entries.
    for (String name : List.copyOf(headers.names())) {
      if (isReserved(name)) {
        headers.remove(name);
      }
    }
    if (session == null) {
      return;
    }
    // Only now, with the namespace provably empty, is it safe to write into it.
    headers.set(USER, session.username());
    headers.set(USER_ID, session.userId());
    headers.set(ROLES, session.roles());
  }

  /**
   * Remove one named cookie while preserving every other pair as the browser sent it.
   *
   * <p>A parent-domain cookie is necessarily offered to sibling hosts. Registry, mirror, and git
   * host do not consume a person session, so forwarding it would turn a browser credential into a
   * service-visible bearer. Rebuilding only the Cookie header preserves unrelated application
   * cookies and keeps the edge's header policy narrow.
   */
  static void stripCookie(MultiMap headers, String name) {
    String raw = headers.get("Cookie");
    if (raw == null || name == null || name.isBlank()) {
      return;
    }
    java.util.ArrayList<String> kept = new java.util.ArrayList<>();
    for (String pair : raw.split(";")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && pair.substring(0, equals).strip().equals(name)) {
        continue;
      }
      if (!pair.isBlank()) {
        kept.add(pair.strip());
      }
    }
    if (kept.isEmpty()) {
      headers.remove("Cookie");
    } else {
      headers.set("Cookie", String.join("; ", kept));
    }
  }

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
   * interceptor chain: an upgrade goes through {@code EdgeWebSocketUpgrade}, the edge's own path,
   * which forwards the inbound request's headers as this method leaves them. Without this call an
   * upgraded connection — every interactive terminal on the platform — would arrive at the
   * environment gateway with no record of who opened it.
   *
   * <p><b>Job 1 has no equivalent on that path</b>, and the gap is deliberate: the upgrade path
   * rebuilds the handshake with the client's own {@code Host} dropped, exactly as {@code
   * vertx-http-proxy}'s did before it. So an upstream reads a socket's original host name from
   * {@code X-Forwarded-Host}, not from {@code Host}. It costs nothing today because a handshake's
   * Host is a protocol formality rather than something an environment gateway routes on.
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
