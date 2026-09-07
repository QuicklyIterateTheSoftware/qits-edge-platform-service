package eu.wohlben.qits.edge.acme;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The finite wildcard set that covers the edge's public host-name shapes, plus the names that shape
 * cannot reach.
 *
 * <p><b>A wildcard is leftmost-only and covers exactly one label.</b> That is the whole arithmetic
 * of this class: a name is covered only when every label but the leftmost one is spelled out. The
 * derived set therefore has FOUR tiers, and each of them exists because the tier above it stops one
 * label short:
 *
 * <ul>
 *   <li>the apex and {@code *.<domain>} — {@code idp.wohlben.eu}, and nothing under it;
 *   <li>{@code *.<environment>.<domain>} per environment — {@code ci.dev.wohlben.eu}, and only
 *       where that middle label is an environment;
 *   <li>{@code *.<project>.<domain>} per known project — {@code editor.acme.wohlben.eu}, the web
 *       editor's own origin, under a label that is a PROJECT rather than an environment;
 *   <li>{@code *.<project>.<environment>.<domain>} per project and environment — the same origin at
 *       depth four, {@code editor.acme.dev.wohlben.eu}.
 * </ul>
 *
 * <p><b>The project tiers retire the extra-SAN-per-project debt.</b> The editor host used to reach
 * this certificate only by being written out by hand, one {@code additional} name per project, in a
 * bootstrap key a person had to remember to extend — so a project created on Tuesday had no
 * certificate until somebody edited a deployment. The projects are now fed in from the {@code
 * ProjectCreated}/{@code ProjectDeleted} events qits-projects publishes, and a slug that is on this
 * list is covered at both depths by construction, for the editor and for anything else the platform
 * ever puts on a project's own name.
 *
 * <p><b>The additional names remain a list of NAMES</b> — "also these", not "also the editors".
 * They arrive from the bootstrap as {@code QITS_EDGE_ACME_ADDITIONAL_NAMES}, written whole or
 * relative to the domain, because one line is what a person writes: {@code editor.acme} and {@code
 * editor.acme.wohlben.eu} are the same name when the domain is {@code wohlben.eu}. Every one of
 * them ends up inside the domain, and that is not a courtesy — the edge answers its challenges by
 * writing records in this domain's own zone, so a name outside it is an order that cannot be
 * answered, and one such name fails the WHOLE order.
 *
 * <p>Empty projects and an empty additional list are the ordinary platform and leave the derived
 * set exactly as it was.
 */
public final class CertificateNames {

  private static final Pattern DNS_NAME =
      Pattern.compile(
          "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /**
   * Commas and whitespace both, because a value written into a {@code .env} line and also onto a
   * command line meets both habits — and a config source that has already split on commas hands
   * over the halves either way.
   */
  private static final Pattern SEPARATOR = Pattern.compile("[,\\s]+");

  /**
   * Let's Encrypt issues at most 100 names per certificate, and refuses the order rather than
   * trimming it.
   *
   * <p>This class refuses first, which is deliberately the cheaper of the two failures. A throw
   * here surfaces as one logged reconcile failure in {@code EdgeCertificateManager} — the current
   * certificate stays installed and every name already on it keeps answering — whereas an order
   * that reaches Let's Encrypt and is refused there has already burnt a rate-limited request and
   * leaves the same certificate in place anyway, with the reason a level further away.
   */
  public static final int MAX_SANS = 100;

  private CertificateNames() {}

  public static Set<String> of(String domain, Collection<String> environments) {
    return of(domain, environments, List.of());
  }

  /** The derived set with no known projects: what an edge orders before it has read the log. */
  public static Set<String> of(
      String domain, Collection<String> environments, Collection<String> additional) {
    return of(domain, environments, List.of(), additional);
  }

  /**
   * The derived wildcard set for these environments and projects, unioned with the configured
   * additional names.
   *
   * <p>The answer keeps the order it was built in — apex, {@code *.<domain>}, the environment tier,
   * the project tier, the project×environment tier, then the additional names as written — so the
   * certificate's subject and SAN order is a property of the configuration and of the projection
   * rather than of a hash seed that changes with every restart.
   *
   * @param projects the slugs of the projects this platform knows about; each is a DNS label and a
   *     value that is not one is refused by name, exactly as an environment is
   * @param additional whole names or names relative to {@code domain}; blanks are dropped and a
   *     name the derived set already holds is not carried twice
   * @throws IllegalArgumentException when the built set exceeds {@link #MAX_SANS}
   */
  public static Set<String> of(
      String domain,
      Collection<String> environments,
      Collection<String> projects,
      Collection<String> additional) {
    String root = domain.strip().toLowerCase(Locale.ROOT);
    if (!DNS_NAME.matcher(root).matches()) {
      throw new IllegalArgumentException("ACME domain is not a lowercase DNS name: " + domain);
    }
    LinkedHashSet<String> environmentLabels = labels(environments, "environment");
    LinkedHashSet<String> projectLabels = labels(projects, "project");

    LinkedHashSet<String> names = new LinkedHashSet<>();
    names.add(root);
    names.add("*." + root);
    for (String environment : environmentLabels) {
      names.add("*." + environment + "." + root);
    }
    for (String project : projectLabels) {
      names.add("*." + project + "." + root);
    }
    for (String project : projectLabels) {
      for (String environment : environmentLabels) {
        names.add("*." + project + "." + environment + "." + root);
      }
    }
    int derived = names.size();
    for (String value : additional) {
      for (String written : SEPARATOR.split(value)) {
        String name = normalize(written, root);
        if (name != null) {
          names.add(name);
        }
      }
    }
    if (names.size() > MAX_SANS) {
      throw tooManyNames(
          names.size(), environmentLabels.size(), projectLabels.size(), names.size() - derived);
    }
    return Collections.unmodifiableSet(names);
  }

  /**
   * The refusal, with the arithmetic that produced the number spelled out.
   *
   * <p>A total is {@code 2 + E + P + P·E + A}, and the term that grows without anybody deciding to
   * is {@code P·E}: one project on a three-environment edge is four names, not one. The message
   * says which term is the large one so the remedy is visible from the log line alone.
   */
  private static IllegalArgumentException tooManyNames(
      int total, int environments, int projects, int additional) {
    return new IllegalArgumentException(
        "The edge would order "
            + total
            + " names and a Let's Encrypt certificate carries at most "
            + MAX_SANS
            + ": 2 (the apex and its wildcard) + "
            + environments
            + " environment(s) + "
            + projects
            + " project(s) + "
            + (projects * environments)
            + " project×environment + "
            + additional
            + " additional = "
            + total
            + ". Nothing is dropped to fit — a silently trimmed order is an origin that stops"
            + " answering — so this edge needs fewer environments, fewer projects on this domain,"
            + " or a second certificate for the project tiers.");
  }

  /** The labels of one tier, lowercased and deduplicated, or a refusal naming the offender. */
  private static LinkedHashSet<String> labels(Collection<String> values, String tier) {
    LinkedHashSet<String> labels = new LinkedHashSet<>();
    for (String value : values) {
      String label = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
      if (!LABEL.matcher(label).matches()) {
        throw new IllegalArgumentException("ACME " + tier + " is not a DNS label: " + value);
      }
      labels.add(label);
    }
    return labels;
  }

  /**
   * One written name, resolved against the domain — or null when there was nothing written.
   *
   * <p>A name that is not already inside the domain is read as relative to it, which is the
   * spelling the knob is documented in. The one mistake that rule cannot see is a whole name for a
   * DIFFERENT domain, which becomes a relative one; telling the two apart needs a public suffix
   * list, and the bootstrap prints the resolved names instead.
   */
  private static String normalize(String written, String root) {
    String name = written.strip().toLowerCase(Locale.ROOT);
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    if (name.isEmpty()) {
      return null;
    }
    String absolute = name.equals(root) || name.endsWith("." + root) ? name : name + "." + root;
    if (!DNS_NAME.matcher(absolute).matches()) {
      throw new IllegalArgumentException(
          "ACME additional name is not a DNS name under "
              + root
              + ": "
              + written
              + " (resolved to "
              + absolute
              + "). Wildcards are not written here — the edge derives "
              + root
              + ", *."
              + root
              + ", *.<env>."
              + root
              + ", *.<project>."
              + root
              + " and *.<project>.<env>."
              + root
              + " for itself, and a name at any other depth is spelled out whole or relative to "
              + "the domain.");
    }
    return absolute;
  }
}
