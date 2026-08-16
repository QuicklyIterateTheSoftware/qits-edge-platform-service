package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.nio.file.Path;
import java.time.Duration;
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
