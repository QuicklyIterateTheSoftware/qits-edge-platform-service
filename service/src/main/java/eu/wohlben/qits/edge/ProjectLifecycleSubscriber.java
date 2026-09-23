package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Durable projection of qits-projects' project lifecycle, kept for the certificate.
 *
 * <p>A project's slug is a label in two SAN tiers the edge orders, so the set of projects is part
 * of the set of names. This listener is the only thing that moves it: {@code ProjectCreated} adds a
 * slug, {@code ProjectChanged} restates one, {@code ProjectDeleted} tombstones one, and {@link
 * EdgeProjects} decides which of two frames for one slug is the newer.
 *
 * <p><b>Only a GROWN slug set asks for a certificate order</b>, which is a create and nothing else.
 * A grown desired set is a name the installed certificate is missing, which is an origin that does
 * not answer until it is ordered. A SHRUNK set is not: every name on the certificate still resolves
 * and still verifies, so the wildcard for a deleted project simply ages out at the next renewal.
 * Ordering on a delete would spend one of Let's Encrypt's five duplicate certificates a week to
 * remove a name nobody is asking for — and a {@code ProjectChanged} that only flips {@code
 * supportsEnvironments} would spend one to install the names that are already installed.
 */
@ApplicationScoped
public class ProjectLifecycleSubscriber implements QitsDurableEventListener {

  static final String CREATED = "ProjectCreated";
  static final String CHANGED = "ProjectChanged";
  static final String DELETED = "ProjectDeleted";
  static final String CONSUMER_ID = "edge-project-sans";

  private static final Logger LOG = Logger.getLogger(ProjectLifecycleSubscriber.class);

  /**
   * Private wire DTO rather than a dependency on qits-projects' event jar. The contract is
   * cross-repository JSON and additions must not force the edge to wait for a Maven release, so
   * unknown fields are ignored — {@code createdAt}, {@code changedAt}, {@code deletedAt} and the
   * project's display name among them. The time this projection orders by is the FRAME's, which
   * every consumer sees identically, and the display name is nothing a certificate can be built
   * from.
   *
   * <p><b>It holds THREE of the payload's fields, not all of them</b>, and that is what a private
   * wire DTO is for. The publisher spells the display name {@code projectName} rather than {@code
   * name}, because a record component called {@code name} collides with the QitsEvent envelope's
   * {@code @JsonIgnore} mixin there and is silently dropped — a rule their contract test pins. A
   * copy of that field here would be a second place to get it wrong for a value this consumer has
   * no use for, so there is still none, even though the field names are transcribed by hand and
   * copying it would have cost one line.
   *
   * <p>{@code slug} is the load-bearing one. It is a DNS label by construction on the publisher's
   * side and is nevertheless checked here: it becomes a certificate name, and one bad name fails
   * the whole ACME order for every other project too.
   *
   * <p><b>{@code supportsEnvironments} is a boxed {@code Boolean} on purpose, and the canonical
   * constructor is where its absence is decided.</b> A {@code boolean} component would deserialise
   * a missing key as {@code false} — and missing is the common case, because this consumer replays
   * from the epoch and every {@code ProjectCreated} published before the field existed has no such
   * key. That would have the edge quietly assert that every historical project has no environments,
   * which is the opposite of the truth. Null normalises to {@link Boolean#TRUE} here rather than at
   * each use, so there is one place to get it right and no reader downstream holding a null.
   */
  record ProjectLifecyclePayload(String projectId, String slug, Boolean supportsEnvironments) {

    ProjectLifecyclePayload {
      supportsEnvironments = supportsEnvironments == null ? Boolean.TRUE : supportsEnvironments;
    }
  }

  @Inject EdgeProjects projects;
  @Inject EdgeCertificateManager certificates;

  ProjectLifecycleSubscriber() {}

  ProjectLifecycleSubscriber(EdgeProjects projects, EdgeCertificateManager certificates) {
    this.projects = projects;
    this.certificates = certificates;
  }

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(CREATED, CHANGED, DELETED);
  }

  /**
   * The projection has no other seed. A new edge must learn every project ever announced, rather
   * than starting at today's head and ordering a certificate that covers only the projects created
   * after it booted.
   */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public void onFrame(EventFrame frame) {
    ProjectLifecyclePayload payload = decode(frame);
    if (payload == null) {
      return;
    }
    // A change is a project that exists, exactly as a create is: the two differ only in whether the
    // edge had heard of the slug before, which is EdgeProjects' question and not this one's.
    boolean present = CREATED.equals(frame.name()) || CHANGED.equals(frame.name());
    if (!present && !DELETED.equals(frame.name())) {
      LOG.warnf(
          "%s %s is none of %s, %s or %s; it is settled unhandled",
          frame.name(), frame.id(), CREATED, CHANGED, DELETED);
      return;
    }
    String slug = payload.slug().strip().toLowerCase(Locale.ROOT);
    boolean changed;
    try {
      changed =
          projects.apply(
              slug,
              payload.projectId() == null ? "" : payload.projectId().strip(),
              present,
              // A delete carries no such field, so the DTO's normalisation hands over TRUE — the
              // neutral value a tombstone is written with. See EdgeProjects#apply.
              payload.supportsEnvironments(),
              frame.id(),
              frame.occurredAt());
    } catch (IllegalArgumentException poison) {
      // Settled rather than thrown: a throw pins this consumer's watermark, and a frame refused
      // for its own content is refused identically on every later catch-up — so the certificate
      // would stop learning about every project published after it, forever.
      LOG.warnf(
          "%s %s carries an unusable project: %s; it is settled without changing the SAN set",
          frame.name(), frame.id(), poison.getMessage());
      return;
    }
    if (!changed) {
      // The frame was still recorded when it was the newest — a ProjectChanged that only flips
      // supportsEnvironments lands here, having written the flag and moved no name.
      LOG.debugf(
          "%s %s left the served slug set unchanged; `%s` is still %s",
          frame.name(), frame.id(), slug, present ? "served" : "absent");
      return;
    }
    LOG.infof(
        "%s the project slug `%s` from %s %s; %d slug(s) are now on the certificate",
        present ? "added" : "retired", slug, frame.name(), frame.id(), projects.slugs().size());
    if (present) {
      // Only a GROWN set: the certificate is short a name it has to answer on, and nothing else
      // this listener sees is. A flag-only change never reaches here, by way of `changed`.
      certificates.requestReconcile();
    }
  }

  /**
   * The payload of a frame that can move the projection, or null when it cannot.
   *
   * <p>Three ways to be unusable and one answer to all of them, because they share a property: they
   * are permanent. A payload that will not parse, a payload with no slug and a slug that is not a
   * DNS label are exactly as bad on the next catch-up as on this one, so each is WARNed and settled
   * — never thrown, which would pin this consumer's watermark on a frame that can never pass. What
   * is never allowed is for such a slug to reach {@code CertificateNames}, where one bad name
   * refuses the order for every good project too.
   */
  private static ProjectLifecyclePayload decode(EventFrame frame) {
    ProjectLifecyclePayload payload;
    try {
      payload = CanonicalJson.payloadTo(frame.payload(), ProjectLifecyclePayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable project payload: %s; it is settled unhandled",
          frame.name(), frame.id(), unreadable.getMessage());
      return null;
    }
    if (payload == null || payload.slug() == null || payload.slug().isBlank()) {
      LOG.warnf("%s %s carries no slug; it is settled unhandled", frame.name(), frame.id());
      return null;
    }
    if (!HostEnvironments.isLabel(payload.slug().strip().toLowerCase(Locale.ROOT))) {
      LOG.warnf(
          "%s %s carries `%s`, which cannot be a DNS label and so cannot be a certificate name; it"
              + " is settled unhandled",
          frame.name(), frame.id(), payload.slug());
      return null;
    }
    return payload;
  }
}
