package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The drift guard on this process' OTel export — configuration only.
 *
 * <p><b>The behaviour is proven elsewhere, on purpose.</b> qits-events' {@code OtelLogBridgeTest}
 * takes an unchanged {@code org.jboss.logging.Logger} call all the way to a decoded {@code
 * ExportLogsServiceRequest}, and its {@code PackagedLogBridgeIT} repeats that against the packaged
 * artifact. This repo inherits that evidence rather than re-running it: the extension is the same
 * {@code quarkus-opentelemetry}, the Quarkus pin is the same, and the keys below are the whole of
 * what a service configures.
 *
 * <p>What is NOT inherited is this repository's own configuration, which nothing else can see. The
 * failure this exists for is silent: a Quarkus upgrade that flips a default, a merge that drops a
 * line, a property renamed under it, and the edge simply stops shipping telemetry — with a green
 * build, a healthy process and no error anywhere. It costs more here than in a service behind the
 * gateway, because the edge is the outermost hop: a request it answered itself reached no other
 * process, so its telemetry is the only record that the exchange happened at all.
 *
 * <p><b>It carries {@link StubGateways} although it starts no exchange</b>, and that is not
 * leftover. A {@code @QuarkusTest} whose configuration differs from the class before it RESTARTS
 * Quarkus, and a WebSocket upgrade through {@code vertx-http-proxy} only survives the first start
 * in a JVM — so a second configuration in this suite is how {@code EdgeRoutingTest}'s socket test
 * starts failing for no visible reason. One resource, one configuration, one start. See {@code
 * EdgeRoutingTest}'s javadoc for the whole story.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class OtelLogConfigTest {

  @Inject Config config;

  private String value(String key) {
    return config.getValue(key, String.class);
  }

  @Test
  void logExportIsEnabledThroughTheHandlerAndTheSharedExporter() {
    assertEquals("true", value("quarkus.otel.logs.enabled"));
    // The JBoss Log Manager handler: without it the other keys describe a pipe nothing enters.
    assertEquals("true", value("quarkus.otel.logs.handler.enabled"));
    // `cdi` routes records at the exporter configured below, not at a second, separate one.
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
  }

  @Test
  void theOutboundFloorIsInfoAndNotQuarkusAllDefault() {
    // The one deliberate narrowing: Quarkus exports every record the log manager creates. Losing
    // this line does not break export, it multiplies it — which is why it is asserted rather than
    // left to the default it is not.
    assertEquals("INFO", value("quarkus.otel.logs.level"));
  }

  @Test
  void metricsLeaveToo() {
    // Off by default in Quarkus, so this is the line that makes the third signal exist.
    assertEquals("true", value("quarkus.otel.metrics.enabled"));
  }

  @Test
  void theExporterAimsAtTheQitsReceiverOverHttpProtobuf() {
    // The receiver is an HTTP resource; the SDK default is gRPC to localhost:4317.
    assertEquals("http/protobuf", value("quarkus.otel.exporter.otlp.protocol"));
    // The exporter appends /v1/<signal> to this base. Asserted EXPANDED —
    // qits.observability.url is the one key a deployment moves, and this is what it resolves to
    // when it does not.
    assertEquals(
        "http://qits-observability:8080/observability/api/otel",
        value("quarkus.otel.exporter.otlp.endpoint"));
  }
}
