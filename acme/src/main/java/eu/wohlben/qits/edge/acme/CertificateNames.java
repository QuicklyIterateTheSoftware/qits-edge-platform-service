package eu.wohlben.qits.edge.acme;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The finite wildcard set that covers the edge's two public host-name shapes, plus the names that
 * shape cannot reach.
 *
 * <p><b>A wildcard is leftmost-only and covers exactly one label.</b> The derived set — the apex,
 * {@code *.<domain>} and {@code *.<environment>.<domain>} per environment — is every depth the
 * edge's Host reading has, so {@code *.<domain>} answers for {@code editor.<domain>} and for
 * nothing under it, and {@code *.<env>.<domain>} only holds where that middle label is an
 * environment. The web editor is served at {@code editor.<project>.<domain>}: depth three, under a
 * label that is a project rather than an environment, so no wildcard this platform can order will
 * ever reach it. Such a name has to be a SAN of its own, which is what {@code additional} carries.
 *
 * <p><b>The additional names are a list of NAMES</b> — "also these", not "also the editors". They
 * arrive from the bootstrap as {@code QITS_EDGE_ACME_ADDITIONAL_NAMES}, written whole or relative
 * to the domain, because one line per project is what a person writes: {@code editor.acme} and
 * {@code editor.acme.wohlben.eu} are the same name when the domain is {@code wohlben.eu}. Every one
 * of them ends up inside the domain, and that is not a courtesy — the edge answers its challenges
 * by writing records in this domain's own zone, so a name outside it is an order that cannot be
 * answered, and one such name fails the WHOLE order.
 *
 * <p>An empty list is the ordinary platform and leaves the derived set exactly as it was.
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

  private CertificateNames() {}

  public static Set<String> of(String domain, Collection<String> environments) {
    return of(domain, environments, List.of());
  }

  /**
   * The derived wildcard set, unioned with the configured additional names.
   *
   * <p>The answer keeps the order it was built in — derived names first, then the additional ones
   * as written — so the certificate's subject and SAN order is a property of the configuration
   * rather than of a hash seed that changes with every restart.
   *
   * @param additional whole names or names relative to {@code domain}; blanks are dropped and a
   *     name the derived set already holds is not carried twice
   */
  public static Set<String> of(
      String domain, Collection<String> environments, Collection<String> additional) {
    String root = domain.strip().toLowerCase(Locale.ROOT);
    if (!DNS_NAME.matcher(root).matches()) {
      throw new IllegalArgumentException("ACME domain is not a lowercase DNS name: " + domain);
    }

    LinkedHashSet<String> names = new LinkedHashSet<>();
    names.add(root);
    names.add("*." + root);
    for (String value : environments) {
      String environment = value.strip().toLowerCase(Locale.ROOT);
      if (!LABEL.matcher(environment).matches()) {
        throw new IllegalArgumentException("ACME environment is not a DNS label: " + value);
      }
      names.add("*." + environment + "." + root);
    }
    for (String value : additional) {
      for (String written : SEPARATOR.split(value)) {
        String name = normalize(written, root);
        if (name != null) {
          names.add(name);
        }
      }
    }
    return Collections.unmodifiableSet(names);
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
              + " and *.<env>."
              + root
              + " for itself, and a name at any other depth is spelled out whole or relative to "
              + "the domain.");
    }
    return absolute;
  }
}
