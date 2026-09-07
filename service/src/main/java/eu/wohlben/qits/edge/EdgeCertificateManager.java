package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.acme.AcmeCertificateIssuer;
import eu.wohlben.qits.edge.acme.AuthoritativeDnsPropagation;
import eu.wohlben.qits.edge.acme.CertificateNames;
import eu.wohlben.qits.edge.acme.CertificateRequest;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Reconciles the one certificate served by the edge; request handling never waits for it.
 *
 * <p>Three inputs decide the names on it: the configured environments, the projects {@link
 * EdgeProjects} has learnt from the log, and the additional names a deployment spells out. Two of
 * them are static and the third moves on its own, which is why this manager has a trigger as well
 * as a schedule — see {@link #requestReconcile()}.
 */
@ApplicationScoped
public class EdgeCertificateManager {

  private static final Logger LOG = Logger.getLogger(EdgeCertificateManager.class);
  private static final URI STAGING =
      URI.create("https://acme-staging-v02.api.letsencrypt.org/directory");
  private static final URI PRODUCTION =
      URI.create("https://acme-v02.api.letsencrypt.org/directory");

  /**
   * The point at which the SAN list is close enough to Let's Encrypt's hard ceiling to say so.
   *
   * <p>Ten names of headroom, which on a two-environment edge is three more projects. The CAP
   * itself lives in {@code CertificateNames}, which drops whole project tiers past it; this is the
   * line that appears while there is still time to do something other than read the drop after the
   * fact.
   */
  private static final int NAMES_WARNING_THRESHOLD = 90;

  private final AcmeConfig acme;
  private final EdgeConfig edge;
  private final AcmeLease lease;
  private final EdgeProjects projects;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean pending = new AtomicBoolean();

  EdgeCertificateManager(AcmeConfig acme, EdgeConfig edge, AcmeLease lease, EdgeProjects projects) {
    this.acme = acme;
    this.edge = edge;
    this.lease = lease;
    this.projects = projects;
  }

  void start(@Observes StartupEvent ignored) {
    CompletableFuture.runAsync(this::reconcileSafely);
  }

  /**
   * Asks for a reconcile because the desired name set may have grown, after a debounce.
   *
   * <p><b>Debounced, and that is the whole reason this method exists</b> rather than a direct call.
   * The projection that feeds it is filled by epoch replay, so a fresh edge learns about every
   * project ever created within a second or two: the first announcement would otherwise order a
   * certificate missing all the others, and the correction would spend one of the five duplicate
   * certificates Let's Encrypt allows per week. The first request opens a window of {@link
   * AcmeConfig#reconcileDebounce()}; everything arriving inside it rides the same order.
   *
   * <p><b>It never runs inline.</b> The caller is the event funnel's delivery thread, and the work
   * behind a reconcile is an ACME order with DNS propagation waits in it — minutes, in the worst
   * case, during which no other frame would be delivered. The delayed executor guarantees the
   * separation: this method schedules and returns.
   *
   * <p>The pending flag is cleared before the work rather than after, so a project announced while
   * an order is in flight opens a fresh window instead of being swallowed by the one running.
   *
   * <p><b>A request that loses to a run already in flight RE-ARMS.</b> It used to be dropped, and
   * the boot it was written for is exactly where that cost the most: the startup reconcile holds
   * the guard for as long as an ACME order takes — up to {@link AcmeConfig#dnsTimeout()} — and
   * {@code ProjectSansBootstrap}'s post-catch-up request lands inside that window, so the first
   * certificate carried no project SANs at all until the 12h sweep. The re-arm is bounded by the
   * pending flag rather than a queue: at most one request is ever outstanding, and it retries one
   * debounce window at a time until it actually runs.
   */
  public void requestReconcile() {
    if (!pending.compareAndSet(false, true)) {
      return;
    }
    CompletableFuture.runAsync(
        () -> {
          pending.set(false);
          if (!reconcileSafely()) {
            LOG.infof(
                "a certificate reconcile lost to one already running; asking again in %s",
                acme.reconcileDebounce());
            requestReconcile();
          }
        },
        CompletableFuture.delayedExecutor(
            acme.reconcileDebounce().toMillis(), TimeUnit.MILLISECONDS));
  }

  @Scheduled(every = "12h", concurrentExecution = ConcurrentExecution.SKIP)
  void scheduledReconcile() {
    reconcileSafely();
  }

  /**
   * @return whether this call did the deciding — true when it ran a reconcile, and true for an edge
   *     with no ACME to do, because there is nothing there for a caller to ask again for. False
   *     means only that another thread held the guard, which is the one outcome a debounced request
   *     must not treat as done.
   */
  boolean reconcileSafely() {
    if (!acme.enabled() || acme.mode() == AcmeConfig.Mode.OFF || acme.domain().isEmpty()) {
      return true;
    }
    if (!running.compareAndSet(false, true)) {
      return false;
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
    return true;
  }

  /** Package-visible so {@code EdgeCertificateDebounceTest} can hold the real guard down. */
  void reconcile() throws Exception {
    String domain = acme.domain().orElseThrow().strip().toLowerCase(Locale.ROOT);
    String token = hetznerToken();
    CertificateNames.Names derived =
        CertificateNames.capped(
            domain,
            edge.environments(),
            projects.slugs(),
            acme.additionalNames().orElseGet(List::of));
    Set<String> desired = derived.names();
    if (!derived.droppedProjects().isEmpty()) {
      // ERROR, on EVERY reconcile, for as long as anything is dropped: this is the alarm. The
      // certificate is orderable and renewable, and some project's names are not on it — a
      // condition nothing else in the platform will ever surface, because every name that IS on
      // the certificate keeps working perfectly.
      LOG.errorf(
          "The edge certificate cannot carry every project: %d of %d project(s) are left off it to"
              + " stay inside Let's Encrypt's %d names, and their hosts will fail the TLS"
              + " handshake. Dropped: %s. The remedy is fewer environments on this domain or a"
              + " second certificate; the tiers cost 1 + %d name(s) per project.",
          derived.droppedProjects().size(),
          projects.slugs().size(),
          CertificateNames.MAX_SANS,
          String.join(", ", derived.droppedProjects()),
          edge.environments().size());
    }
    if (desired.size() > NAMES_WARNING_THRESHOLD) {
      LOG.warnf(
          "The edge certificate is at %d of the %d names Let's Encrypt issues per certificate;"
              + " %d project(s) across %d environment(s) is what the project tiers cost. Past the"
              + " ceiling the order is refused and the current certificate stays installed.",
          desired.size(),
          CertificateNames.MAX_SANS,
          projects.slugs().size(),
          edge.environments().size());
    }
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
              new HetznerDnsChallengeProvider(token, domain),
              new AuthoritativeDnsPropagation(),
              store);
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
