package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The {@code host[:port]} parse, the one place a configured address becomes an origin. */
class UpstreamTest {

  @Test
  void aBareHostTakesTheConfiguredPort() {
    assertEquals(
        new Upstream("prod-qits-gateway", 8080), Upstream.parse("prod-qits-gateway", 8080));
  }

  @Test
  void anExplicitPortWins() {
    assertEquals(new Upstream("localhost", 8000), Upstream.parse("localhost:8000", 8080));
  }

  @Test
  void caseAndSurroundingSpaceAreTolerated() {
    assertEquals(
        new Upstream("prod-qits-gateway", 8080), Upstream.parse(" PROD-Qits-Gateway ", 8080));
  }

  @Test
  void anUnparseableAddressIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> Upstream.parse("localhost:http", 8080));
    assertThrows(IllegalArgumentException.class, () -> Upstream.parse("localhost:0", 8080));
    assertThrows(IllegalArgumentException.class, () -> Upstream.parse("", 8080));
    assertThrows(IllegalArgumentException.class, () -> Upstream.parse(null, 8080));
  }

  @Test
  void itPrintsAsAnAddress() {
    assertEquals("prod-qits-gateway:8080", Upstream.parse("prod-qits-gateway", 8080).toString());
  }
}
