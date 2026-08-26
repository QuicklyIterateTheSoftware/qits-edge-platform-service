package eu.wohlben.qits.edge;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetSocket;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * The edge's own WebSocket upgrade: handshake against the upstream, splice the two sockets, and —
 * the reason this class exists — <b>give the upstream connection back on every path that does not
 * end in a spliced socket</b>.
 *
 * <p><b>Why not {@code vertx-http-proxy}'s built-in upgrade handling.</b> Its upgrade path assumes
 * an unread inbound request: it registers a body handler on it after opening the upstream side, and
 * {@code Http1xServerRequest} answers that with {@code IllegalStateException: Request has already
 * been read} when the request has already been consumed. Under Quarkus it has been: the inbound
 * request is a {@code ResumingRequestWrapper}, which resumes the paused request on its own account,
 * and a handshake — a bodyless GET — is then read to its end before the proxy runs. The exception
 * is thrown <i>after</i> the upstream accepted the upgrade and <i>before</i> the proxy registers
 * its response handler, so nothing ever touches that connection again: not the pool, which believes
 * it is still in use, and not a close. One pool slot per attempt, gone until restart — at 64, the
 * whole origin hangs, plain GETs included. That is the outage this class replaces.
 *
 * <p>So this path never registers a handler on the inbound request at all. A WebSocket client sends
 * no body and no frame before it has the {@code 101}, so there is nothing to forward until {@link
 * HttpServerRequest#toNetSocket()} — which takes the whole connection over, read state and all.
 * What remains cannot crash on a consumed request, and every failure before the splice closes the
 * upstream connection in a {@code try/catch} that has no other job.
 *
 * <p><b>What an upstream sees is unchanged</b> from the proxy's upgrade path: the inbound headers
 * as {@link EdgeRouter} prepared them — identity asserted, forwarded headers applied — minus {@code
 * Host}, which the client fills in from the socket it opened, with {@code Connection} normalised to
 * {@code Upgrade}. An upstream that refuses the upgrade answers with its own status; the refusal is
 * relayed as that status and the connection is closed rather than pooled, because a connection that
 * has carried a {@code connect()} handshake is not one this class can prove clean.
 */
final class EdgeWebSocketUpgrade {

  private static final Logger LOG = Logger.getLogger(EdgeWebSocketUpgrade.class);

  private final HttpClient client;

  EdgeWebSocketUpgrade(HttpClient client) {
    this.client = client;
  }

  /**
   * Forward one handshake. The request must be paused — {@link EdgeRouter#handle} does it on
   * arrival — so its read state is wherever Quarkus left it and stays there.
   *
   * @param origin the upstream address and the acquisition timeout, prepared by {@link
   *     EdgeRouter#originRequestOptions} so the handshake queues under the same bound as every
   *     plain request
   */
  void handle(HttpServerRequest request, Upstream upstream, RequestOptions origin) {
    client
        .request(origin)
        .onFailure(
            failure -> {
              // The one saturation log line for this origin: pool exhaustion used to hang here
              // silently instead.
              LOG.warnf(
                  "no upstream connection to %s for a WebSocket upgrade: %s", upstream, failure);
              refuse(request, saturated(failure) ? 503 : 502);
            })
        .onSuccess(handshake -> forward(request, upstream, handshake));
  }

  /** Whether this failure is a full pool rather than a broken upstream. */
  private static boolean saturated(Throwable failure) {
    return failure instanceof io.vertx.core.http.ConnectionPoolTooBusyException
        || failure instanceof TimeoutException;
  }

  private void forward(HttpServerRequest request, Upstream upstream, HttpClientRequest handshake) {
    try {
      handshake.setMethod(HttpMethod.GET);
      handshake.setURI(request.uri());
      for (Map.Entry<String, String> header : request.headers()) {
        if (HttpHeaders.HOST.toString().equalsIgnoreCase(header.getKey())
            || HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())) {
          continue;
        }
        handshake.headers().add(header.getKey(), header.getValue());
      }
      // The inbound Connection header may carry other tokens; upstream needs exactly the upgrade.
      handshake.headers().set(HttpHeaders.CONNECTION, HttpHeaders.UPGRADE);
      handshake
          .connect()
          .onFailure(
              failure -> {
                close(handshake);
                LOG.warnf("the WebSocket handshake to %s failed: %s", upstream, failure);
                refuse(request, 502);
              })
          .onSuccess(response -> upgraded(request, upstream, handshake, response));
    } catch (RuntimeException failure) {
      // Nothing before this point may leave the acquired connection dangling, whatever throws.
      close(handshake);
      throw failure;
    }
  }

  /** The upstream has answered. From here every non-splice exit must close its connection. */
  private void upgraded(
      HttpServerRequest request,
      Upstream upstream,
      HttpClientRequest handshake,
      HttpClientResponse response) {
    try {
      if (response.statusCode() != 101) {
        // The upstream refused the upgrade; the caller learns the upstream's own answer. Closed
        // rather than pooled — see the class comment.
        close(handshake);
        refuse(request, response.statusCode());
        return;
      }
      HttpServerResponse accepted = request.response();
      accepted.setStatusCode(101);
      accepted.headers().addAll(response.headers());
      // Drain the handshake's own end-of-request before the connection changes hands. Safe on a
      // request Quarkus already read: resume never throws on a consumed request, only the body
      // handlers this class does not use do.
      request.resume();
      request
          .toNetSocket()
          .onFailure(
              failure -> {
                close(handshake);
                LOG.errorf(
                    failure,
                    "could not take over the client connection for a socket to %s",
                    upstream);
              })
          .onSuccess(inbound -> splice(inbound, response.netSocket()));
    } catch (RuntimeException failure) {
      close(handshake);
      throw failure;
    }
  }

  /** Both directions, and a close on either side closes the other. */
  private static void splice(NetSocket inbound, NetSocket outbound) {
    inbound.handler(outbound::write);
    outbound.handler(inbound::write);
    inbound.exceptionHandler(failure -> outbound.close());
    outbound.exceptionHandler(failure -> inbound.close());
    inbound.closeHandler(v -> outbound.close());
    outbound.closeHandler(v -> inbound.close());
  }

  /**
   * Give the upstream connection back to the pool the only way that is provably safe mid-upgrade:
   * by closing it. {@code reset} on an unconnected request, {@code close} on a live connection —
   * both end with the slot free.
   */
  private static void close(HttpClientRequest handshake) {
    try {
      if (handshake.connection() != null) {
        handshake.connection().close();
      } else {
        handshake.reset();
      }
    } catch (RuntimeException failure) {
      LOG.errorf(failure, "could not release an upstream connection after a failed upgrade");
    }
  }

  private static void refuse(HttpServerRequest request, int status) {
    // The read state is wherever the wrapper left it; released so keep-alive stays clean.
    request.resume();
    if (!request.response().ended() && !request.response().headWritten()) {
      request.response().setStatusCode(status).end();
    }
  }
}
