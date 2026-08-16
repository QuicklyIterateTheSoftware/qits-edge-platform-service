package eu.wohlben.qits.edge.testdb;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** One real PostgreSQL instance for the edge test JVM, with named databases per datasource. */
public final class EmbeddedPg {

  public static final String USER = "postgres";
  public static final String PASSWORD = "embedded";
  // Outside qits.edge: that prefix is a strict @ConfigMapping, so a test-only system property
  // below it is (correctly) rejected as a misspelled production setting.
  private static final String PORT_PROPERTY = "qits.test.embedded-pg.port";
  private static EmbeddedPostgres started;

  private EmbeddedPg() {}

  public static synchronized String url(String database) {
    String url = "jdbc:postgresql://localhost:" + port() + "/" + database;
    ensureDatabase(database);
    return url;
  }

  private static int port() {
    String existing = System.getProperty(PORT_PROPERTY);
    if (existing != null) {
      return Integer.parseInt(existing);
    }
    try {
      started = EmbeddedPostgres.builder().start();
      System.setProperty(PORT_PROPERTY, String.valueOf(started.getPort()));
      Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPg::stop, "edge-embedded-pg-stop"));
      return started.getPort();
    } catch (Exception failure) {
      throw new IllegalStateException("could not start embedded PostgreSQL", failure);
    }
  }

  private static void ensureDatabase(String database) {
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port() + "/postgres", USER, PASSWORD);
        Statement sql = connection.createStatement()) {
      try (ResultSet exists =
          sql.executeQuery("select 1 from pg_database where datname = '" + database + "'")) {
        if (exists.next()) {
          return;
        }
      }
      sql.execute("create database " + database);
    } catch (Exception failure) {
      throw new IllegalStateException("could not create edge test database " + database, failure);
    }
  }

  private static synchronized void stop() {
    if (started != null) {
      try {
        started.close();
      } catch (Exception ignored) {
        // The JVM is already stopping.
      }
      started = null;
    }
  }
}
