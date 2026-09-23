package eu.wohlben.qits.edge.acme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The finite wildcard set that covers the edge's public host-name shapes, plus the names that shape
 * cannot reach.
 *
 * <p><b>The grammar is read RIGHT TO LEFT, and every label is inside the one to its right:</b>
 * {@code <app>[.<env>].<project>.<domain>}. The domain holds projects, a project holds its
 * environments, an environment holds its apps. There is no unqualified application tier — a project
 * label is always there — and there is no environment tier at the top: {@code dev.wohlben.eu} is
 * not a name, because an environment only exists inside a project.
 *
 * <p><b>A wildcard is leftmost-only and covers exactly one label.</b> That is the whole arithmetic
 * of this class: a name is covered only when every label but the leftmost one is spelled out. The
 * derived set therefore has FOUR tiers, and each of them exists because the tier above it stops one
 * label short:
 *
 * <ul>
 *   <li>the apex — {@code wohlben.eu}, and nothing under it;
 *   <li>{@code *.<domain>} — every project's own door, {@code acme.wohlben.eu}, and only one label
 *       deep;
 *   <li>{@code *.<project>.<domain>} per known project — one name per project, and what it covers
 *       depends on whether that project has environments. For an ENV-LESS project it is the tier
 *       its applications are served on: {@code projects.qits.wohlben.eu}. For a project that DOES
 *       support environments it is the tier its environment doors are served on: {@code
 *       dev.acme.wohlben.eu}. Either way it is served, not merely resolved;
 *   <li>{@code *.<environment>.<project>.<domain>} per env-supporting project and environment — one
 *       application of one project in one environment, {@code ci.dev.acme.wohlben.eu}. An env-less
 *       project contributes none of these at all, because it has no middle label to spell out.
 * </ul>
 *
 * <p><b>Which projects have environments is a projection, not a guess.</b> The {@code projects}
 * argument is a map of slug to that flag, fed from the edge's own project projection, so an estate
 * of env-less projects costs {@code 2 + P} names where the old top-level environment tier and the
 * unconditional cross product cost {@code 2 + E + P + P·E}.
 *
 * <p><b>The project tiers retire the extra-SAN-per-project debt.</b> The editor host used to reach
 * this certificate only by being written out by hand, one {@code additional} name per project, in a
 * bootstrap key a person had to remember to extend — so a project created on Tuesday had no
 * certificate until somebody edited a deployment. The projects are now fed in from the {@code
 * ProjectCreated}/{@code ProjectDeleted} events qits-projects publishes, and a slug that is on this
 * list is covered at every depth it can be served on by construction.
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
 * #capped(String, Collection, Map, Collection)}: the set an over-large estate produces is short of
 * some projects, and it is still an orderable set, because the alternative — the refusal this class
 * used to raise — took the whole reconcile with it, expiry renewal included. The ceiling is further
 * off than it was — ninety-eight env-less projects rather than twenty-four on a three-environment
 * edge — and it is still reachable, so the drop policy stays exactly as it is.
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
   * Map, Collection)}. It used to refuse instead, on the reasoning that a local refusal is the
   * cheaper of the two failures; it is, but only when the alternative is the order. The refusal was
   * raised while BUILDING the name set, which every reconcile does before it knows whether it has
   * anything to order at all, so one project past the ceiling took every renewal with it — and a
   * certificate that quietly stops renewing expires ninety days later with nothing in the log but
   * one line per reconcile.
   */
  public static final int MAX_SANS = 100;

  private CertificateNames() {}

  /**
   * The derived set, and the projects that did not fit on it.
   *
   * @param names the SAN list to order, in the order it was built
   * @param droppedProjects the slugs whose tiers were left off to stay inside {@link #MAX_SANS}, in
   *     the order they were dropped — empty on every ordinary platform, and the alarm {@code
   *     EdgeCertificateManager} logs when it is not
   */
  public record Names(Set<String> names, List<String> droppedProjects) {}

  public static Set<String> of(String domain, Collection<String> environments) {
    return of(domain, environments, List.of());
  }

  /**
   * The derived set with no known projects: what an edge orders before it has read the log.
   *
   * <p>With no projects there is nothing for {@code environments} to be a tier INSIDE, so the
   * answer is the apex, {@code *.<domain>} and the additional names. The argument is still taken
   * and still validated, because a configured environment that is not a DNS label is a deployment
   * error worth refusing at the first reconcile rather than at the first project.
   */
  public static Set<String> of(
      String domain, Collection<String> environments, Collection<String> additional) {
    return capped(domain, environments, Map.of(), additional).names();
  }

  /**
   * The derived wildcard set for these environments and projects, unioned with the configured
   * additional names, capped at {@link #MAX_SANS} by dropping whole project tiers.
   *
   * <p>The answer keeps the order it was built in — apex, {@code *.<domain>}, the project-door tier
   * in the slugs' SORTED order, then the per-environment tier of the env-supporting projects in the
   * same order, then the additional names as written — so the certificate's subject and SAN order
   * is a property of the configuration and of the projection rather than of a hash seed that
   * changes with every restart. The project tiers are sorted here rather than taken in arrival
   * order, so the guarantee does not rest on the caller happening to hand them over sorted, nor on
   * the map it hands over having a stable iteration order.
   *
   * <p><b>What over-cap does, and why it is not a refusal.</b> {@code 2 + P + P'·E + A} passes 100
   * on an estate nobody decided to grow — an env-less estate reaches the ceiling at its
   * ninety-eighth project, and one where every project has three environments at its twenty-fourth.
   * Refusing there refused the whole derivation, which happens before the due-check — so an edge
   * one project over could no longer renew an EXPIRING certificate either, and the platform's only
   * TLS terminator went dark ninety days later. Projects are therefore dropped until the set fits:
   *
   * <ul>
   *   <li>in the slugs' sorted order, from the END, so the same estate always drops the same slugs
   *       and yesterday's certificate is not reshuffled by today's ordering;
   *   <li>a WHOLE project at a time — its {@code *.<slug>.<domain>} and every {@code
   *       *.<env>.<slug>.<domain>} go together, because half a project on a certificate is an
   *       origin that answers in one environment and fails the handshake in the next;
   *   <li>never the apex, {@code *.<domain>} or an additional name. Those are what the platform
   *       itself answers on, and they are configuration rather than a projection that grew — so
   *       when THEY alone exceed the cap this still throws, and that throw is a deployment to
   *       correct.
   * </ul>
   *
   * @param environments the environment labels this platform offers, which become a tier only
   *     underneath a project that supports environments
   * @param projects the projects this platform knows about, slug to whether that project has a tier
   *     of environments under it; each slug is a DNS label and a value that is not one is refused
   *     by name, exactly as an environment is
   * @param additional whole names or names relative to {@code domain}; blanks are dropped and a
   *     name the derived set already holds is not carried twice
   * @throws IllegalArgumentException when the names that are never dropped already exceed {@link
   *     #MAX_SANS}
   */
  public static Names capped(
      String domain,
      Collection<String> environments,
      Map<String, Boolean> projects,
      Collection<String> additional) {
    String root = domain.strip().toLowerCase(Locale.ROOT);
    if (!DNS_NAME.matcher(root).matches()) {
      throw new IllegalArgumentException("ACME domain is not a lowercase DNS name: " + domain);
    }
    LinkedHashSet<String> environmentLabels = labels(environments, "environment");
    LinkedHashMap<String, Boolean> projectLabels = projectLabels(projects);
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
    // have. A set rather than arithmetic because an additional name can spell out a name the
    // derived tiers already hold.
    LinkedHashSet<String> undroppable = new LinkedHashSet<>();
    undroppable.add(root);
    undroppable.add("*." + root);
    undroppable.addAll(extras);
    if (undroppable.size() > MAX_SANS) {
      throw tooManyFixedNames(undroppable.size(), extras.size());
    }

    List<String> ordered = new ArrayList<>(projectLabels.keySet());
    Collections.sort(ordered);
    LinkedHashMap<String, Boolean> kept = new LinkedHashMap<>();
    LinkedHashSet<String> counted = new LinkedHashSet<>(undroppable);
    List<String> dropped = new ArrayList<>();
    for (String project : ordered) {
      boolean supportsEnvironments = projectLabels.get(project);
      LinkedHashSet<String> attempt = new LinkedHashSet<>(counted);
      attempt.addAll(projectTiers(project, supportsEnvironments, environmentLabels, root));
      if (dropped.isEmpty() && attempt.size() <= MAX_SANS) {
        counted = attempt;
        kept.put(project, supportsEnvironments);
        continue;
      }
      // Once one slug has been dropped every later one goes too, which is what "drop from the end"
      // means: the kept set is a PREFIX of the sorted order, so it cannot depend on a cheaper slug
      // happening to sit behind an expensive one. It matters more now that projects cost different
      // amounts: without this an env-less project could slip past a dropped env-supporting one.
      dropped.add(project);
    }

    LinkedHashSet<String> names = new LinkedHashSet<>();
    names.add(root);
    names.add("*." + root);
    // `kept` was filled in the slugs' sorted order, and emitting from it rather than from the
    // caller's collection is what makes the ORDER as deterministic as the membership: two calls
    // with the same slugs in different arrival orders are the same SAN list, not merely the same
    // set. It was true in practice already — EdgeProjects sorts — and a guarantee that holds
    // because of somebody else's ORDER BY is not one.
    names.addAll(kept.keySet().stream().map(project -> "*." + project + "." + root).toList());
    for (Map.Entry<String, Boolean> project : kept.entrySet()) {
      if (!project.getValue()) {
        continue;
      }
      for (String environment : environmentLabels) {
        names.add("*." + environment + "." + project.getKey() + "." + root);
      }
    }
    names.addAll(extras);
    return new Names(Collections.unmodifiableSet(names), List.copyOf(dropped));
  }

  /**
   * Every tier of one project, which are on the certificate together or not at all.
   *
   * <p>One name for a project with no environments, and {@code 1 + E} for one that has them.
   */
  private static List<String> projectTiers(
      String project, boolean supportsEnvironments, Collection<String> environments, String root) {
    List<String> tiers = new ArrayList<>();
    tiers.add("*." + project + "." + root);
    if (supportsEnvironments) {
      for (String environment : environments) {
        tiers.add("*." + environment + "." + project + "." + root);
      }
    }
    return tiers;
  }

  /**
   * The one refusal left, with the arithmetic that produced the number spelled out.
   *
   * <p>A total is {@code 2 + P + P'·E + A}, and the terms that grow without anybody deciding to are
   * the project ones. Those are now dropped to fit, so reaching this message means the term a
   * PERSON wrote — the additional names — fills a certificate on its own, with no room for a single
   * project. That is a deployment to correct rather than a projection to wait out.
   */
  private static IllegalArgumentException tooManyFixedNames(int total, int additional) {
    return new IllegalArgumentException(
        "The edge would order "
            + total
            + " names before a single project tier, and a Let's Encrypt certificate carries at"
            + " most "
            + MAX_SANS
            + ": 2 (the apex and its wildcard) + "
            + additional
            + " additional = "
            + total
            + ". The project tiers are dropped to fit; these names are not, because they are what"
            + " the platform itself answers on — so this edge needs fewer additional names, or a"
            + " second certificate.");
  }

  /** The labels of one tier, lowercased and deduplicated, or a refusal naming the offender. */
  private static LinkedHashSet<String> labels(Collection<String> values, String tier) {
    LinkedHashSet<String> labels = new LinkedHashSet<>();
    for (String value : values) {
      labels.add(label(value, tier));
    }
    return labels;
  }

  /**
   * The project slugs, lowercased and deduplicated, each with whether it supports environments.
   *
   * <p>A {@link LinkedHashMap}, and never {@code Map.copyOf}: the copy factories salt their
   * iteration order per JVM. The slugs are sorted before any name is emitted, so the answer does
   * not depend on this order — but nothing downstream should have to know that.
   *
   * <p>A null flag reads as TRUE, the same compatibility rule the projection's column default
   * carries: absence means "has environments", which is what every project on the platform had
   * before the flag existed.
   */
  private static LinkedHashMap<String, Boolean> projectLabels(Map<String, Boolean> projects) {
    LinkedHashMap<String, Boolean> labels = new LinkedHashMap<>();
    for (Map.Entry<String, Boolean> project : projects.entrySet()) {
      labels.put(
          label(project.getKey(), "project"), project.getValue() == null || project.getValue());
    }
    return labels;
  }

  private static String label(String value, String tier) {
    String label = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    if (!LABEL.matcher(label).matches()) {
      throw new IllegalArgumentException("ACME " + tier + " is not a DNS label: " + value);
    }
    return label;
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
              + ", *.<project>."
              + root
              + " and *.<env>.<project>."
              + root
              + " for itself, and a name at any other depth is spelled out whole or relative to "
              + "the domain.");
    }
    return absolute;
  }
}
