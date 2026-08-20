package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two predicates, away from a proxy. {@code EdgeRoutingTest} proves the rewrite reaches a real
 * response; these prove the edges of what it decides on, which are cheaper to state here than to
 * stage an upstream for.
 */
class EdgeCacheControlTest {

  @Test
  void onlyTheUntouchedQuarkusDefaultIsRewritable() {
    assertTrue(EdgeCacheControl.isStaticDefault("public, immutable, max-age=86400"));
    // Header values are case-insensitive in practice and a container may normalise them.
    assertTrue(EdgeCacheControl.isStaticDefault("Public, Immutable, Max-Age=86400"));
    assertTrue(EdgeCacheControl.isStaticDefault(" public, immutable, max-age=86400 "));

    assertFalse(EdgeCacheControl.isStaticDefault(null));
    // Every one of these is somebody's decision, and the edge does not overrule decisions.
    assertFalse(EdgeCacheControl.isStaticDefault("no-store"));
    assertFalse(EdgeCacheControl.isStaticDefault("no-cache"));
    assertFalse(EdgeCacheControl.isStaticDefault("public, max-age=86400"));
    assertFalse(EdgeCacheControl.isStaticDefault("public, immutable, max-age=3600"));
  }

  @Test
  void aContentHashedNameIsAngularsOutputShape() {
    assertTrue(EdgeCacheControl.isContentHashed("/observability/main-4RS6EA47.js"));
    assertTrue(EdgeCacheControl.isContentHashed("/observability/styles-AB12CD34.css"));
    assertTrue(EdgeCacheControl.isContentHashed("/deep/path/chunk-XK9Q2M1P.js"));
    assertTrue(EdgeCacheControl.isContentHashed("/media/logo-ABCD1234.svg"));
  }

  @Test
  void everythingElseRevalidates() {
    assertFalse(EdgeCacheControl.isContentHashed(null));
    assertFalse(EdgeCacheControl.isContentHashed("/observability/"));
    // The document itself, which is the whole reason this class exists.
    assertFalse(EdgeCacheControl.isContentHashed("/observability/index.html"));
    assertFalse(EdgeCacheControl.isContentHashed("/observability/main.js"));
    assertFalse(EdgeCacheControl.isContentHashed("/observability/favicon.ico"));
    // Eight characters but no digit: an all-caps word, not a hash. Erring here merely revalidates.
    assertFalse(EdgeCacheControl.isContentHashed("/observability/user-SETTINGS.js"));
    assertFalse(EdgeCacheControl.isContentHashed("/assets/logo-DOWNLOAD.png"));
  }
}
