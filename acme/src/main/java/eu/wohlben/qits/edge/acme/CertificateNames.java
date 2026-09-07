package eu.wohlben.qits.edge.acme;

import java.util.ArrayList;
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
 *   <li>{@code *.<project>.<domain>} per known project — {@code editor.acme.wohlben.eu}, a name
 *       that RESOLVES AND VERIFIES AND IS NOT SERVED. It states no environment, so the router
 *       answers it 404 with the explicit spelling; it is on the certificate so that the caller
 *       still dialling it reads that sentence. The TLS handshake happens before any answer, so
 *       without this tier the same request ends in a certificate error, which is a browser page
 *       nobody can act on. One SAN per project buys a readable 404 for every retired short-form
 *       bookmark;
 *   <li>{@code *.<project>.<environment>.<domain>} per project and environment — the web editor's
 *       own origin, {@code editor.acme.dev.wohlben.eu}, and the tier that is actually served.
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
 * <p><b>Past the ceiling the project tiers are DROPPED, never the order.</b> See {@link
 * #capped(String, Collection, Collection, Collection)}: the set an over-large estate produces is
 * short of some projects, and it is still an orderable set, because the alternative — the refusal
 * this class used to raise — took the whole reconcile with it, expiry renewal included.
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
   * <p>So this class trims first, by whole project tiers — see {@link #capped(String, Collection,
   * Collection, Collection)}. It used to refuse instead, on the reasoning that a local refusal is
   * the cheaper of the two failures; it is, but only when the alternative is the order. The refusal
   * was raised while BUILDING the name set, which every reconcile does before it knows whether it
   * has anything to order at all, so one project past the ceiling took every renewal with it — and
   * a certificate that quietly stops renewing expires ninety days later with nothing in the log but
   * one line per reconcile.
   */
  public static final int MAX_SANS = 100;

  private CertificateNames() {}

  /**
   * The derived set, and the projects that did not fit on it.
   *
   * @param names the SAN list to order, in the order it was built
   * @param droppedProjects the slugs whose two tiers were left off to stay inside {@link
   *     #MAX_SANS}, in the order they were dropped — empty on every ordinary platform, and the
   *     alarm {@code EdgeCertificateManager} logs when it is not
   */
  public record Names(Set<String> names, List<String> droppedProjects) {}

  public static Set<String> of(String domain, Collection<String> environments) {
    return of(domain, environments, List.of());
  }

  /** The derived set with no known projects: what an edge orders before it has read the log. */
  public static Set<String> of(
      String domain, Collection<String> environments, Collection<String> additional) {
    return capped(domain, environments, List.of(), additional).names();
  }

  /**
   * The derived wildcard set for these environments and projects, unioned with the configured
   * additional names, capped at {@link #MAX_SANS} by dropping whole project tiers.
   *
   * <p>The answer keeps the order it was built in — apex, {@code *.<domain>}, the environment tier,
   * the project tier, the project×environment tier, then the additional names as written — so the
   * certificate's subject and SAN order is a property of the configuration and of the projection
   * rather than of a hash seed that changes with every restart.
   *
   * <p><b>What over-cap does, and why it is not a refusal.</b> {@code 2 + E + P + P·E + A} passes
   * 100 on an estate nobody decided to grow: {@code P} moves with ordinary project creation and
   * {@code P·E} is the term that runs, so a three-environment edge reaches the ceiling at its
   * twenty-fourth project. Refusing there refused the whole derivation, which happens before the
   * due-check — so an edge one project over could no longer renew an EXPIRING certificate either,
   * and the platform's only TLS terminator went dark ninety days later. Projects are therefore
   * dropped until the set fits:
   *
   * <ul>
   *   <li>in the slugs' sorted order, from the END, so the same estate always drops the same slugs
   *       and yesterday's certificate is not reshuffled by today's ordering;
   *   <li>a WHOLE project at a time — its {@code *.<slug>.<domain>} and every {@code
   *       *.<slug>.<env>.<domain>} go together, because half a project on a certificate is an
   *       origin that answers in one environment and fails the handshake in the next;
   *   <li>never the apex, {@code *.<domain>}, an environment tier or an additional name. Those are
   *       what the platform itself answers on, and they are configuration rather than a projection
   *       that grew — so when THEY alone exceed the cap this still throws, and that throw is a
   *       deployment to correct.
   * </ul>
   *
   * @param projects the slugs of the projects this platform knows about; each is a DNS label and a
   *     value that is not one is refused by name, exactly as an environment is
   * @param additional whole names or names relative to {@code domain}; blanks are dropped and a
   *     name the derived set already holds is not carried twice
   * @throws IllegalArgumentException when the names that are never dropped already exceed {@link
   *     #MAX_SANS}
   */
  public static Names capped(
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
    LinkedHashSet<String> extras = new LinkedHashSet<>();
    for (String value : additional) {
      for (String written : SEPARATOR.split(value)) {
        String name = normalize(written, root);
        if (name != null) {
          extras.add(name);
        }
      }
    }

    // Everything that is never dropped, counted first: what is left is the room the project tiers
    // have. A set rather than arithmetic because the tiers can overlap — a project sharing an
    // environment's spelling yields a name the environment tier already holds.
    LinkedHashSet<String> undroppable = new LinkedHashSet<>();
    undroppable.add(root);
    undroppable.add("*." + root);
    for (String environment : environmentLabels) {
      undroppable.add("*." + environment + "." + root);
    }
    undroppable.addAll(extras);
    if (undroppable.size() > MAX_SANS) {
      throw tooManyFixedNames(undroppable.size(), environmentLabels.size(), extras.size());
    }

    List<String> ordered = new ArrayList<>(projectLabels);
    Collections.sort(ordered);
    LinkedHashSet<String> kept = new LinkedHashSet<>();
    LinkedHashSet<String> counted = new LinkedHashSet<>(undroppable);
    List<String> dropped = new ArrayList<>();
    for (String project : ordered) {
      LinkedHashSet<String> attempt = new LinkedHashSet<>(counted);
      attempt.addAll(projectTiers(project, environmentLabels, root));
      if (dropped.isEmpty() && attempt.size() <= MAX_SANS) {
        counted = attempt;
        kept.add(project);
        continue;
      }
      // Once one slug has been dropped every later one goes too, which is what "drop from the end"
      // means: the kept set is a PREFIX of the sorted order, so it cannot depend on a cheaper slug
      // happening to sit behind an expensive one.
      dropped.add(project);
    }

    LinkedHashSet<String> names = new LinkedHashSet<>();
    names.add(root);
    names.add("*." + root);
    for (String environment : environmentLabels) {
      names.add("*." + environment + "." + root);
    }
    for (String project : projectLabels) {
      if (kept.contains(project)) {
        names.add("*." + project + "." + root);
      }
    }
    for (String project : projectLabels) {
      if (kept.contains(project)) {
        for (String environment : environmentLabels) {
          names.add("*." + project + "." + environment + "." + root);
        }
      }
    }
    names.addAll(extras);
    return new Names(Collections.unmodifiableSet(names), List.copyOf(dropped));
  }

  /** Both tiers of one project, which are on the certificate together or not at all. */
  private static List<String> projectTiers(
      String project, Collection<String> environments, String root) {
    List<String> tiers = new ArrayList<>();
    tiers.add("*." + project + "." + root);
    for (String environment : environments) {
      tiers.add("*." + project + "." + environment + "." + root);
    }
    return tiers;
  }

  /**
   * The one refusal left, with the arithmetic that produced the number spelled out.
   *
   * <p>A total is {@code 2 + E + P + P·E + A}, and the term that grows without anybody deciding to
   * is {@code P·E}: one project on a three-environment edge is four names, not one. That term is
   * now dropped to fit, so reaching this message means the terms a PERSON wrote — the environment
   * list and the additional names — fill a certificate on their own, with no room for a single
   * project. That is a deployment to correct rather than a projection to wait out.
   */
  private static IllegalArgumentException tooManyFixedNames(
      int total, int environments, int additional) {
    return new IllegalArgumentException(
        "The edge would order "
            + total
            + " names before a single project tier, and a Let's Encrypt certificate carries at"
            + " most "
            + MAX_SANS
            + ": 2 (the apex and its wildcard) + "
            + environments
            + " environment(s) + "
            + additional
            + " additional = "
            + total
            + ". The project tiers are dropped to fit; these names are not, because they are what"
            + " the platform itself answers on — so this edge needs fewer environments, fewer"
            + " additional names, or a second certificate.");
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
