package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Runtime ownership of the edge certificate; absent domain means no public TLS automation. */
@ConfigMapping(prefix = "qits.edge.acme")
public interface AcmeConfig {

  enum Mode {
    OFF,
    STAGING,
    PRODUCTION
  }

  @WithDefault("false")
  boolean enabled();

  @WithDefault("off")
  Mode mode();

  Optional<String> domain();

  Optional<String> email();

  Optional<String> hetznerToken();

  /**
   * The names this certificate must carry beyond the wildcard set the edge derives for itself.
   *
   * <p>A wildcard is leftmost-only, so {@code *.<domain>} answers for {@code editor.<domain>} and
   * for nothing under it, and {@code *.<env>.<domain>} only holds where that middle label is an
   * environment. The web editor lives at {@code editor.<project>.<domain>} — a middle label that is
   * a PROJECT — so no wildcard this platform can order reaches it and each such host has to be a
   * SAN of its own. The key is a list of NAMES and knows nothing about editors; the editor is
   * today's reason for it and will not be the last.
   *
   * <p>The bootstrap renders it, one name per project, whole or relative to the domain:
   *
   * <pre>
   * QITS_EDGE_ACME_ADDITIONAL_NAMES=editor.acme,editor.gizmo.wohlben.eu
   * </pre>
   *
   * <p>Absent is the ordinary platform and orders exactly the derived set. A name added here
   * reaches the certificate at the next order — the 12h reconcile, or a restart at once.
   */
  Optional<List<String>> additionalNames();

  /**
   * Docker/Kubernetes secret file; preferred over exposing the token in the process environment.
   */
  Optional<Path> hetznerTokenFile();

  @WithDefault("/work/.letsencrypt")
  Path directory();

  @WithDefault("30d")
  Duration renewBefore();

  @WithDefault("10m")
  Duration dnsTimeout();
}
