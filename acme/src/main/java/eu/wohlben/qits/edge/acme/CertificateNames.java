package eu.wohlben.qits.edge.acme;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The finite wildcard set that covers the edge's two public host-name shapes. */
public final class CertificateNames {

  private static final Pattern DNS_NAME =
      Pattern.compile(
          "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private CertificateNames() {}

  public static Set<String> of(String domain, Collection<String> environments) {
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
    return Set.copyOf(names);
  }
}
