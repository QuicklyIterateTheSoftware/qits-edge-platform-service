package eu.wohlben.qits.edge.acme;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public record CertificateRequest(
    URI directory,
    String email,
    Set<String> identifiers,
    Path stateDirectory,
    Duration challengeTimeout) {

  public CertificateRequest {
    identifiers = Set.copyOf(identifiers);
  }
}
