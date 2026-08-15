package eu.wohlben.qits.edge.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/** Gives both Flyway lineages a real, independent PostgreSQL database during @QuarkusTest. */
public class EmbeddedPgConfigSource implements ConfigSource {

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.edge.jdbc.url", EmbeddedPg.url("edge_test"),
          "quarkus.datasource.edge.username", EmbeddedPg.USER,
          "quarkus.datasource.edge.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url("edge_eventstream_test"),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "edge-embedded-pg";
  }
}
