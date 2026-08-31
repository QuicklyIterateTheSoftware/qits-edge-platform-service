package eu.wohlben.qits.edge.acme;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record CertificateRequest(
    URI directory,
    String email,
    Set<String> identifiers,
    Path stateDirectory,
    Duration challengeTimeout) {

  public CertificateRequest {
    // Copied through a LinkedHashSet rather than Set.copyOf: the CA reads the first identifier as
    // the certificate's subject, and Set.copyOf iterates in an order salted per JVM — so the
    // identical name set would name a different subject after every restart.
    identifiers = Collections.unmodifiableSet(new LinkedHashSet<>(identifiers));
  }
}
