package eu.wohlben.qits.edge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The whole of the edge's routing logic: a Host name in, an environment name out.
 *
 * <p>The convention is two spellings, both ending at the same environment:
 *
 * <pre>
 *   $app.$env.$domain      home.prod.example.com   -> prod
 *   $env.$domain           prod.example.com        -> prod
 * </pre>
 *
 * <p>Everything else — the apex domain, an unknown environment name, an IP address, {@code
 * localhost}, a missing Host header — resolves to the <b>default environment</b>. There is no
 * "unroutable" answer, because an edge that refuses a name it does not recognise is an edge that
 * answers a mistyped URL with a connection error instead of the platform's own page.
 *
 * <p><b>Only the first two labels are read</b>, which is what makes the domain itself irrelevant.
 * The edge is never told what {@code $domain} is: it may be one label ({@code prod.localhost}), two
 * ({@code example.com}) or three ({@code example.co.uk}), and stating it in configuration would be
 * a second thing to keep in step with DNS for no gain. Reading from the left needs no such
 * knowledge.
 *
 * <p><b>The tie-break is position 1 before position 0</b>, and it only matters when an environment
 * name is also an application name. {@code staging.prod.example.com} with both {@code staging} and
 * {@code prod} configured reads as <i>app {@code staging} in environment {@code prod}</i>, not as
 * <i>environment {@code staging} in the domain {@code prod.example.com}</i>. An application may be
 * called anything, whereas a domain whose first label happens to be an environment name is a
 * coincidence nobody arranges — so the three-label reading is the likelier one and wins.
 *
 * <p>Framework-free on purpose: this is the one piece of behaviour worth unit-testing without
 * booting an application, and {@code HostEnvironmentsTest} is where the edge cases live.
 */
public final class HostEnvironments {

  private final Set<String> environments;
  private final String defaultEnvironment;

  private HostEnvironments(Set<String> environments, String defaultEnvironment) {
    this.environments = environments;
    this.defaultEnvironment = defaultEnvironment;
  }

  /**
   * @param environments the routable environment names; blanks are dropped, case is not significant
   * @param defaultEnvironment where the apex and every unmatched name go; must be one of the above
   * @throws IllegalArgumentException on an empty list, an unusable name, or a default outside it
   */
  public static HostEnvironments of(Collection<String> environments, String defaultEnvironment) {
    Set<String> names = new LinkedHashSet<>();
    for (String environment : environments) {
      if (environment == null || environment.isBlank()) {
        continue;
      }
      String name = environment.strip().toLowerCase(Locale.ROOT);
      if (!isLabel(name)) {
        throw new IllegalArgumentException(
            "qits.edge.environments holds `"
                + environment
                + "`, which cannot be a DNS label — an environment name reaches DNS as part of its"
                + " gateway's host name, so it may hold only letters, digits and inner hyphens.");
      }
      names.add(name);
    }
    if (names.isEmpty()) {
      throw new IllegalArgumentException(
          "qits.edge.environments is empty — the edge would have nothing to forward to.");
    }
    if (defaultEnvironment == null || defaultEnvironment.isBlank()) {
      throw new IllegalArgumentException("qits.edge.default-environment is not set.");
    }
    String fallback = defaultEnvironment.strip().toLowerCase(Locale.ROOT);
    if (!names.contains(fallback)) {
      throw new IllegalArgumentException(
          "qits.edge.default-environment is `"
              + defaultEnvironment
              + "`, which is not in qits.edge.environments "
              + names
              + ". Every unmatched host goes to the default, so a default the edge cannot reach"
              + " would break most of its traffic rather than an edge case of it.");
    }
    return new HostEnvironments(Set.copyOf(names), fallback);
  }

  /** The routable environment names, lower case. */
  public Set<String> environments() {
    return environments;
  }

  /** Where the apex domain and every unmatched host go. */
  public String defaultEnvironment() {
    return defaultEnvironment;
  }

  /**
   * The environment a Host name names, or {@link #defaultEnvironment()} when it names none.
   *
   * @param host a Host header or HTTP/2 {@code :authority} value; a port suffix, a trailing dot and
   *     letter case are all tolerated, and {@code null} is the same as an unmatched name
   */
  public String resolve(String host) {
    String name = normalise(host);
    if (name.isEmpty() || isAddressLiteral(name)) {
      // An address literal carries no name to read. It is how the platform is reached before DNS
      // exists — a bootstrap curling the host's own port — and the default is the right answer.
      return defaultEnvironment;
    }
    String[] labels = name.split("\\.", -1);
    // Position 1 first: see the class javadoc for why the three-label reading wins a tie.
    if (labels.length > 1 && environments.contains(labels[1])) {
      return labels[1];
    }
    if (environments.contains(labels[0])) {
      return labels[0];
    }
    return defaultEnvironment;
  }

  /** Lower case, no surrounding space, no trailing root dot, no port suffix, no IPv6 brackets. */
  private static String normalise(String host) {
    if (host == null) {
      return "";
    }
    String name = host.strip().toLowerCase(Locale.ROOT);
    if (name.startsWith("[")) {
      // A bracketed IPv6 literal: `[::1]` or `[::1]:8080`. The brackets exist precisely so the
      // address' own colons cannot be read as a port separator, so unwrap before anything else.
      int end = name.indexOf(']');
      return end < 0 ? name.substring(1) : name.substring(1, end);
    }
    int colon = name.lastIndexOf(':');
    if (colon >= 0 && name.indexOf(':') == colon) {
      // Exactly one colon, so it is a port separator rather than an unbracketed IPv6 address.
      name = name.substring(0, colon);
    }
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }

  /**
   * Whether a name is an IP address rather than a domain name. IPv4 is checked by shape rather than
   * by range: a label may legally be all digits, so {@code 127.0.0.1} would otherwise be split and
   * compared against environment names, and an environment called {@code 0} would start capturing
   * loopback traffic. IPv6 is anything left holding a colon after {@link #normalise}.
   */
  private static boolean isAddressLiteral(String name) {
    if (name.indexOf(':') >= 0) {
      return true;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c != '.' && (c < '0' || c > '9')) {
        return false;
      }
    }
    return true;
  }

  /** The DNS label charset: letters, digits and hyphens, never leading or trailing. */
  private static boolean isLabel(String name) {
    if (name.isEmpty() || name.length() > 63 || name.startsWith("-") || name.endsWith("-")) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
      if (!ok) {
        return false;
      }
    }
    return true;
  }
}
