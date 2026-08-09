package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import java.net.URL;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The drift guard on the Let's Encrypt certificate slot — the four build-time keys, and the two
 * things they must not change.
 *
 * <p><b>The feature itself is not tested here, because it is dormant here.</b> This application is
 * no ACME client: the flags add an HTTP-01 challenge route to the main listener and the
 * challenge-management endpoints to the management interface, and the {@code quarkus tls
 * lets-encrypt} CLI runs the protocol from outside. No keystore is configured in this repository —
 * that is a deployment's business — so the management endpoint answers 503 and nothing is ever
 * requested or renewed. That 503 is asserted below: it is the proof that these flags ship inert to
 * a node with no public name.
 *
 * <p><b>What is worth a test is what enabling the management interface almost broke.</b> Quarkus
 * moves {@code /q/health} onto the management interface by default, and the bootstrap and the
 * deployer both poll {@code :8080/q/health/ready}. The failure would be silent in both directions:
 * the process healthy, the poll unanswerable, nothing logged. So health is asserted PRESENT on the
 * main listener and ABSENT from the management one — the second half is the one that catches a
 * changed default, because a repository that never enabled management would pass the first.
 *
 * <p><b>It carries {@link StubGateways} although it proxies nothing</b>, for the reason {@code
 * OtelLogConfigTest} states: a {@code @QuarkusTest} whose configuration differs from the class
 * before it RESTARTS Quarkus, and a WebSocket upgrade through {@code vertx-http-proxy} only
 * survives the first start in a JVM. One configuration for the whole suite, one start, and {@code
 * EdgeRoutingTest}'s socket tests keep working.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class LetsEncryptConfigTest {

  /**
   * The challenge-management endpoint, on the management interface and nowhere else. {@code
   * management = true} is what resolves the second server's port and root path, so the URL this
   * test uses is the one the extension registered rather than one spelled here.
   */
  @TestHTTPResource(value = "lets-encrypt/challenge", management = true)
  URL challengeAdmin;

  /** Where health would have moved to, and must not have. */
  @TestHTTPResource(value = "health/ready", management = true)
  URL healthOnTheManagementInterface;

  @Inject Config config;

  private String value(String key) {
    return config.getValue(key, String.class);
  }

  // --- the four keys ---------------------------------------------------------------------------

  @Test
  void theCertificateSlotIsEnabledAtBuildTime() {
    // Build time, so an image either has the challenge routes or it does not — a deployment cannot
    // add them. Measured against Quarkus 3.34.6: LetsEncryptBuildTimeConfig.enabled defaults false.
    assertEquals("true", value("quarkus.tls.lets-encrypt.enabled"));
  }

  @Test
  void theManagementInterfaceIsOnAndBindsEveryInterfaceInsideTheContainer() {
    // The challenge-management endpoint is UNAUTHENTICATED. On the main listener — the
    // host's one published port — anyone on the internet could complete their own ACME
    // order for our domain, so it belongs on a server no deployment has to publish.
    assertEquals("true", value("quarkus.management.enabled"));
    // Not the same default as quarkus.http.host: management binds localhost in dev and test, and
    // 0.0.0.0 only in prod. The issuing CLI runs outside the container, so this is stated.
    assertEquals("0.0.0.0", value("quarkus.management.host"));
  }

  @Test
  void healthIsKeptOffTheManagementInterface() {
    // The one key here that undoes a Quarkus default rather than setting one:
    // SmallRyeHealthBuildTimeConfig.managementEnabled is true in 3.34.6, and true moves
    // /q/health to port 9000, where nothing on the platform polls it.
    assertEquals("false", value("quarkus.smallrye-health.management.enabled"));
  }

  // --- and what they must not change -----------------------------------------------------------

  @Test
  void theChallengeRouteAnswersOnTheMainListenerAndIsNeverProxied() {
    // Route precedence, against a Host whose upstream is live: the extension registers its route
    // ahead of EdgeRouter's catch-all, so an ACME validator reaches THIS process rather than an
    // environment gateway that knows nothing about the certificate. Order alone would be invisible
    // in a passing assertion, so the proof is the absence of the stub's marker: every stub gateway
    // names itself in every answer, and this answer names nobody.
    try (EdgeClient client = new EdgeClient(RestAssured.port)) {
      EdgeClient.Answer answer =
          client.get("dev.example.com", "/.well-known/acme-challenge/anything");

      // 404 because no challenge has been set — the extension answered, which is the whole point.
      assertEquals(404, answer.status());
      assertNull(
          answer.line("upstream"),
          "a stub gateway answered the ACME challenge path, so the catch-all won: "
              + answer.body());
      assertNull(
          answer.headers().get("x-upstream"),
          "the challenge path was proxied to " + answer.headers().get("x-upstream"));
    }
  }

  @Test
  void theChallengeManagementEndpointIsDormantWithoutAKeystore() {
    // 503, and "No keystore configured in quarkus.tls..key-store" in the log. The endpoint
    // exists — proving the extension put it on the management interface — and it can do
    // nothing, because this repository configures none. Both halves of "shipped on,
    // switched off" in one status code.
    RestAssured.given().when().get(challengeAdmin).then().statusCode(503);
  }

  @Test
  void healthStillAnswersOnTheMainPortAndNotOnTheManagementOne() {
    // The regression the management interface would have caused, from both sides. The bootstrap and
    // the deployer poll these two paths on :8080 and nothing polls 9000.
    RestAssured.when().get("/q/health/live").then().statusCode(200);
    RestAssured.when().get("/q/health/ready").then().statusCode(200);
    RestAssured.given().when().get(healthOnTheManagementInterface).then().statusCode(404);
  }
}
