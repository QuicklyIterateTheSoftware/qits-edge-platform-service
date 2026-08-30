package eu.wohlben.qits.edge;

import java.util.Locale;

/**
 * One environment gateway's address. Framework-free so the {@code host[:port]} parse is unit
 * testable without booting an application — the same shape, and the same reason, as qits-gateway's
 * {@code GatewayRoute}.
 */
public record Upstream(String host, int port) {

  public Upstream {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("An upstream needs a host.");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Not a port: " + port);
    }
  }

  /**
   * Parse a configured {@code host} or {@code host:port} value.
   *
   * @param defaultPort used when the value names no port — {@code qits.edge.apps.<app>.port} for a
   *     configured vhost, and the port a deployment published for a projected one
   */
  public static Upstream parse(String value, int defaultPort) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("An upstream address is empty.");
    }
    String address = value.strip().toLowerCase(Locale.ROOT);
    int colon = address.lastIndexOf(':');
    if (colon < 0) {
      return new Upstream(address, defaultPort);
    }
    String host = address.substring(0, colon);
    String port = address.substring(colon + 1);
    try {
      return new Upstream(host, Integer.parseInt(port));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("`" + value + "` is not host or host:port.", e);
    }
  }

  @Override
  public String toString() {
    return host + ":" + port;
  }
}
