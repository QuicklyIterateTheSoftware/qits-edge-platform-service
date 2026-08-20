package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Quarkus static-resource default, {@code public, immutable, max-age=86400}, leaves the edge
 * only on files whose <b>name</b> is content-hashed. Everything else that carries it — the {@code
 * index.html} that names the bundles, favicons, logos, i18n files — goes out as {@code
 * Cache-Control: no-cache} instead.
 *
 * <p><b>Why.</b> Every service serves its SPA with that blanket default, and it is right exactly
 * where the name changes with the content: a hash-named bundle can be kept forever because a new
 * build names a new file. On everything else the same header made a browser keep yesterday's file
 * for a day — most visibly the SPA document itself, which decides which version of the application
 * a returning browser runs. A correct, green, deployed release then does not appear, and nothing on
 * screen says why; it comes right roughly a day later, which reads as flakiness rather than as a
 * cache. {@code no-cache} keeps conditional revalidation; a 304 costs one round trip.
 *
 * <p><b>This is a port, not a new idea.</b> qits-gateway carried the same class and the same
 * reasoning, and it was the only hop that did. When the gateway was retired in favour of this
 * process the rewrite was not carried across, so every platform SPA went back to shipping an
 * immutable document — the defect this restores the fix for. The regressions are in {@code
 * EdgeRoutingTest}; keep them if this class is ever moved again.
 *
 * <p><b>Why only the known default is rewritten.</b> A header a handler chose is a decision, and
 * the edge does not overrule decisions: this process' own routes say {@code no-store} (which a
 * blanket rewrite would <i>weaken</i>), the git smart-HTTP protocol sets its own caching, and an
 * upstream that deliberately marks a response cacheable stays marked. The default is the one value
 * that is known to be nobody's decision, so it is the one value the edge may correct. The string is
 * Quarkus' (read off the platform's pin, not assumed) — re-check it when the Quarkus pin moves.
 *
 * <p><b>What "content-hashed" means</b> is {@link #isContentHashed}: Angular's output shape, a
 * {@code -}-separated final segment of eight uppercase-alphanumeric characters before the
 * extension, at least one of them a digit. Both misses are the safe direction — a hash that happens
 * to be all letters (rare) merely revalidates — while the digit requirement keeps an all-caps word
 * in a filename from being kept for a day.
 *
 * <p><b>The response direction, on the same chain as {@link EdgeHeaders}.</b> That interceptor
 * states it touches nothing but the request, and it still does; this is a second interceptor rather
 * than a fourth job inside it, so neither class has to qualify what it says about itself. A
 * WebSocket upgrade short-circuits inside {@code vertx-http-proxy} before the chain is installed
 * and so never reaches this — correctly, a handshake carries no cache header to correct.
 *
 * <p>Stateless, but constructed per proxy like {@link EdgeHeaders}, so the two are wired the same
 * way at both call sites in {@link EdgeRouter}.
 */
final class EdgeCacheControl implements ProxyInterceptor {

  /** Exactly what Quarkus puts on a static resource when nobody said anything. */
  static final String STATIC_DEFAULT = "public, immutable, max-age=86400";

  /** What replaces it: still cacheable, but never used without asking first. */
  static final String REVALIDATE = "no-cache";

  /**
   * A content-hashed filename, as Angular emits them: {@code main-4RS6EA47.js}. The hash segment is
   * eight uppercase base-36 characters; the digit requirement is what separates a hash from an
   * all-caps word.
   */
  private static final Pattern CONTENT_HASHED =
      Pattern.compile(".*-(?=[A-Z0-9]*[0-9])[A-Z0-9]{8}\\.[A-Za-z0-9]+$");

  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    ProxyResponse response = context.response();
    String cacheControl = response.headers().get(HttpHeaders.CACHE_CONTROL);
    // The PATH the browser asked for, not the upstream's own view of it: the name is the cache key
    // a browser holds, and the name changing with the content is the entire justification for
    // immutable. The edge rewrites no paths, so the two agree — reading the inbound one keeps that
    // true if it ever stops agreeing.
    if (isStaticDefault(cacheControl)
        && !isContentHashed(context.request().proxiedRequest().path())) {
      response.headers().set(HttpHeaders.CACHE_CONTROL, REVALIDATE);
    }
    return context.sendResponse();
  }

  /** Whether the header is the untouched Quarkus default — tolerant of case, nothing else. */
  static boolean isStaticDefault(String cacheControl) {
    return cacheControl != null
        && cacheControl.trim().toLowerCase(Locale.ROOT).equals(STATIC_DEFAULT);
  }

  /**
   * Whether a request path names a content-hashed file. The path is what is checked, not the
   * response: the name is the cache key a browser holds, and the name changing with the content is
   * the entire justification for {@code immutable}.
   */
  static boolean isContentHashed(String path) {
    return path != null && CONTENT_HASHED.matcher(path).matches();
  }
}
