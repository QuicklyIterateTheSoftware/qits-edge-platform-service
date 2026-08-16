package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.acme.AcmeCertificateIssuer;
import eu.wohlben.qits.edge.acme.CertificateNames;
import eu.wohlben.qits.edge.acme.CertificateRequest;
import eu.wohlben.qits.edge.acme.DohDnsPropagation;
import eu.wohlben.qits.edge.acme.HetznerDnsChallengeProvider;
import eu.wohlben.qits.edge.acme.PemCertificateStore;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/** Reconciles the one certificate served by the edge; request handling never waits for it. */
@ApplicationScoped
public class EdgeCertificateManager {

  private static final Logger LOG = Logger.getLogger(EdgeCertificateManager.class);
  private static final URI STAGING =
      URI.create("https://acme-staging-v02.api.letsencrypt.org/directory");
  private static final URI PRODUCTION =
      URI.create("https://acme-v02.api.letsencrypt.org/directory");

  private final AcmeConfig acme;
  private final EdgeConfig edge;
  private final AcmeLease lease;
  private final AtomicBoolean running = new AtomicBoolean();

  EdgeCertificateManager(AcmeConfig acme, EdgeConfig edge, AcmeLease lease) {
    this.acme = acme;
    this.edge = edge;
    this.lease = lease;
  }

  void start(@Observes StartupEvent ignored) {
    CompletableFuture.runAsync(this::reconcileSafely);
  }

  @Scheduled(every = "12h", concurrentExecution = ConcurrentExecution.SKIP)
  void scheduledReconcile() {
    reconcileSafely();
  }

  void reconcileSafely() {
    if (!acme.enabled() || acme.mode() == AcmeConfig.Mode.OFF || acme.domain().isEmpty()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      reconcile();
    } catch (Exception failure) {
      LOG.error(
          "Edge certificate reconciliation failed; the current certificate remains active",
          failure);
    } finally {
      running.set(false);
    }
  }

  private void reconcile() throws Exception {
    String domain = acme.domain().orElseThrow().strip().toLowerCase(Locale.ROOT);
    String token = hetznerToken();
    Set<String> desired = CertificateNames.of(domain, edge.environments());
    PemCertificateStore store = new PemCertificateStore(acme.directory());
    var current = store.current();
    boolean staging =
        current
            .map(value -> value.issuer().toUpperCase(Locale.ROOT).contains("STAGING"))
            .orElse(false);
    boolean production =
        current
            .map(value -> value.issuer().toUpperCase(Locale.ROOT))
            .map(issuer -> !issuer.contains("STAGING") && issuer.contains("LET'S ENCRYPT"))
            .orElse(false);
    if (acme.mode() == AcmeConfig.Mode.STAGING && production) {
      LOG.info("Keeping the production edge certificate while ACME mode is staging");
      return;
    }
    boolean due =
        current.isEmpty()
            || !current.orElseThrow().dnsNames().containsAll(desired)
            || current.orElseThrow().expiresAt().isBefore(Instant.now().plus(acme.renewBefore()))
            || (acme.mode() == AcmeConfig.Mode.PRODUCTION && staging);
    if (!due) {
      return;
    }
    if (!lease.acquire(Duration.ofMinutes(30))) {
      LOG.info("Another edge task owns certificate reconciliation");
      return;
    }
    try {
      URI directory = acme.mode() == AcmeConfig.Mode.PRODUCTION ? PRODUCTION : STAGING;
      String email = acme.email().map(String::strip).orElse("hostmaster@" + domain);
      var issuer =
          new AcmeCertificateIssuer(
              new HetznerDnsChallengeProvider(token, domain), new DohDnsPropagation(), store);
      issuer.issue(
          new CertificateRequest(
              directory,
              email,
              desired,
              acme.directory().resolve("acme").resolve(acme.mode().name().toLowerCase(Locale.ROOT)),
              acme.dnsTimeout()));
      LOG.infof("Installed a new %s edge certificate for %d names", acme.mode(), desired.size());
    } finally {
      lease.release();
    }
  }

  private String hetznerToken() throws Exception {
    if (acme.hetznerTokenFile().isPresent()) {
      Path file = acme.hetznerTokenFile().orElseThrow();
      String token = Files.readString(file).strip();
      if (!token.isEmpty()) {
        return token;
      }
      throw new IllegalStateException("The configured Hetzner token secret file is empty");
    }
    return acme.hetznerToken()
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .orElseThrow(
            () -> new IllegalStateException("ACME is enabled but the Hetzner token is absent"));
  }
}
