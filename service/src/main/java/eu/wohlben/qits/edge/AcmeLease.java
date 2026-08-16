package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/** A bounded database lease prevents rolling edge tasks from placing competing ACME orders. */
@ApplicationScoped
public class AcmeLease {

  private final DataSource dataSource;
  private final String owner = UUID.randomUUID().toString();

  AcmeLease(@io.quarkus.agroal.DataSource("edge") DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public boolean acquire(Duration duration) throws Exception {
    String sql =
        """
        INSERT INTO edge_acme_lease (lease_name, owner_id, expires_at)
        VALUES ('certificate', ?, ?)
        ON CONFLICT (lease_name) DO UPDATE
          SET owner_id = EXCLUDED.owner_id, expires_at = EXCLUDED.expires_at
          WHERE edge_acme_lease.expires_at < CURRENT_TIMESTAMP
             OR edge_acme_lease.owner_id = EXCLUDED.owner_id
        """;
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, owner);
      statement.setTimestamp(2, Timestamp.from(Instant.now().plus(duration)));
      return statement.executeUpdate() == 1;
    }
  }

  public void release() {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "DELETE FROM edge_acme_lease WHERE lease_name = 'certificate' AND owner_id = ?")) {
      statement.setString(1, owner);
      statement.executeUpdate();
    } catch (Exception ignored) {
      // The lease expires by itself; release must never turn a successful issuance into a failure.
    }
  }
}
