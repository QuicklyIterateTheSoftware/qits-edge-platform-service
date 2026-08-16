package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * The one place this process asks qits-platform-idp for a token, and the patience it asks with.
 *
 * <p><b>Why patience is a feature and not a nicety.</b> idp is a container like any other and is
 * redeployed like any other: for a few seconds its name resolves to an address that refuses, drops
 * or accepts-and-never-answers. Every one of those seconds used to be an outage of the edge's whole
 * auth surface — on 2026-08-14 a deploy push died with "the identity provider could not be reached"
 * because a grant landed inside that window. A client that is told "no" retries the WHOLE push; a
 * client that is made to wait two seconds does not notice.
 *
 * <p><b>Bounded on both sides.</b> Each attempt carries a timeout, so an address that accepts a
 * connection and then says nothing — what a service VIP does while its task is starting — fails
 * rather than waiting with nothing to end it, and the whole sequence is bounded by a window, so a
 * caller always gets an answer. Without the per-attempt timeout there is no answer at all: no
 * status and no body until the inbound connection's own idle timeout closes it an hour later, and a
 * docker client has no timeout of its own on a realm call — it waits.
 *
 * <p><b>An ANSWER is never retried.</b> Only the connection is. A 400 or a 401 from idp is idp
 * deciding, and repeating the question would turn one refusal into a burst of them.
 */
@ApplicationScoped
public class IdpGrants {

  private static final Logger LOG = Logger.getLogger(IdpGrants.class);

  /** The first wait between two attempts. It doubles from here. */
  static final long FIRST_BACKOFF_MS = 200;

  /** The longest wait between two attempts, so a long window is still a series of tries. */
  static final long BACKOFF_CAP_MS = 2000;

  /** What idp said. A grant that arrived, whatever its status — a failure is a failed future. */
  public record Grant(int status, String body) {}

  @Inject Vertx vertx;

  @Inject AuthConfig config;

  @Inject Idp idp;

  private HttpClient client;

  @PostConstruct
  void open() {
    // Its own client, not the proxy's: that one is tuned for 64 concurrent layer pushes with no
    // idle timeout, which is the opposite of a small JSON POST that must fail fast and be retried.
    client = vertx.createHttpClient();
  }

  /**
   * Exchange a caller's HTTP Basic credential for an idp token, waiting out an identity provider
   * that is on its way back.
   *
   * @param authorization the {@code Authorization} header value to relay, verbatim
   * @return idp's answer, or a failed future once the window has run out
   */
  public Future<Grant> grant(String authorization) {
    return attempt(authorization, System.currentTimeMillis() + config.idpRetryWindowMs(), 0);
  }

  private Future<Grant> attempt(String authorization, long deadlineMillis, int made) {
    return post(authorization)
        .recover(
            failure -> {
              long backoff = backoffMs(made);
              if (!connectionClassed(failure)
                  || System.currentTimeMillis() + backoff >= deadlineMillis) {
                LOG.errorf(failure, "could not reach %s", idp.tokenEndpoint());
                return Future.failedFuture(failure);
              }
              LOG.warnf(
                  "%s did not answer (%s); trying again in %dms",
                  idp.tokenEndpoint(), failure.toString(), backoff);
              Promise<Grant> next = Promise.promise();
              vertx.setTimer(
                  backoff, id -> attempt(authorization, deadlineMillis, made + 1).onComplete(next));
              return next.future();
            });
  }

  private Future<Grant> post(String authorization) {
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.POST)
            .setAbsoluteURI(idp.tokenEndpoint())
            // BOTH halves of the wait, stated. The connect timeout bounds a dropped SYN — a swarm
            // VIP exists before any task behind it does — and the request timeout bounds the far
            // worse case: a connection that was accepted and will never be answered.
            .setConnectTimeout(config.idpCallTimeoutMs())
            .setTimeout(config.idpCallTimeoutMs());
    return client
        .request(options)
        .compose(
            request -> {
              request.putHeader(HttpHeaders.AUTHORIZATION, authorization);
              request.putHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
              request.putHeader(HttpHeaders.ACCEPT, "application/json");
              // NO `audience` parameter, which asks idp for the client's whole allowed list. The
              // alternative — naming the audience here — makes a client that lacks it fail at idp
              // with an invalid_target the caller cannot read. Asking for everything and checking
              // the audience on the way back in puts the refusal where the reason is known.
              return request.send("grant_type=client_credentials");
            })
        .compose(
            response ->
                response.body().map(body -> new Grant(response.statusCode(), body.toString())));
  }

  /** {@link #FIRST_BACKOFF_MS} doubling to {@link #BACKOFF_CAP_MS}. */
  static long backoffMs(int attemptsMade) {
    long backoff = FIRST_BACKOFF_MS << Math.min(attemptsMade, 16);
    return Math.min(backoff, BACKOFF_CAP_MS);
  }

  /**
   * Whether this failure is the network rather than an answer — a refused connection, a name that
   * does not resolve yet, a dropped SYN, a socket that was accepted and then closed or went quiet.
   * Those are the shapes a redeploying container takes, and every one of them is safe to repeat: no
   * grant was issued, so nothing is done twice.
   *
   * <p>Package-private and static so the classification can be asserted without a network.
   */
  static boolean connectionClassed(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof IOException || cause instanceof TimeoutException) {
        return true;
      }
      String message = cause.getMessage();
      if (message != null) {
        String said = message.toLowerCase(Locale.ROOT);
        // Vert.x reports both of these as a plain VertxException with no cause, so the type checks
        // above miss them: a peer that closed mid-exchange, and its own request timeout.
        if (said.contains("connection was closed")
            || said.contains("connection reset")
            || said.contains("timeout")) {
          return true;
        }
      }
      if (cause.getCause() == cause) {
        return false;
      }
    }
    return false;
  }
}
