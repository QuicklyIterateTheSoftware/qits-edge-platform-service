package eu.wohlben.qits.edge;

import java.util.Objects;

/** One direct path route in the active deployment projection. */
public record EdgeEndpoint(
    String environment,
    String application,
    String path,
    Upstream upstream,
    String navigationLabel,
    Integer navigationPosition) {

  public EdgeEndpoint {
    environment = required(environment, "environment");
    application = required(application, "application");
    path = path(path);
    upstream = Objects.requireNonNull(upstream, "upstream");
    if ((navigationLabel == null) != (navigationPosition == null)) {
      throw new IllegalArgumentException(
          "A navigation endpoint needs both navigationLabel and navigationPosition.");
    }
    if (navigationLabel != null && navigationLabel.isBlank()) {
      throw new IllegalArgumentException("A navigation label is blank.");
    }
  }

  /** True for this prefix itself and its path children, never for a merely similar prefix. */
  public boolean matches(String requestPath) {
    return path.equals("/") || requestPath.equals(path) || requestPath.startsWith(path + "/");
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("An endpoint " + name + " is blank.");
    }
    return value.strip();
  }

  private static String path(String value) {
    if (value == null || value.isBlank() || !value.startsWith("/")) {
      throw new IllegalArgumentException("An endpoint path must start with `/`.");
    }
    String normal = value.strip();
    while (normal.length() > 1 && normal.endsWith("/")) {
      normal = normal.substring(0, normal.length() - 1);
    }
    return normal;
  }
}
