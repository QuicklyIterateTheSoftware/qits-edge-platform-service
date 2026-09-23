package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/**
 * The browser half of authentication, terminated at the edge — and the switch that keeps it dark.
 *
 * <p>{@link AuthConfig} gates MACHINES: a token or a client id and secret, per vhost. This group
 * gates PEOPLE, on the service hosts, because those are the names a browser types. A valid {@code
 * qits-session} cookie becomes the {@code X-Qits-User} / {@code X-Qits-User-Id} / {@code
 * X-Qits-Roles} headers every service already trusts; anything else is sent to the login page or
 * refused.
 *
 * <p><b>{@link #enabled()} is off, and that is the whole rollout plan.</b> The gate lands before
 * the identity provider can issue a session, so it ships inert and is flipped as a step of its own,
 * after idp and its pages are live. With the flag off nothing in a request's path changes, which is
 * what makes the release safe to make at any time.
 */
@ConfigMapping(prefix = "qits.edge.sessions")
public interface SessionsConfig {

  /**
   * Whether a service vhost demands a credential from a browser. OFF: see the class javadoc —
   * turning it on before idp issues sessions would answer every browser with a redirect to a page
   * that cannot log anybody in.
   */
  @WithDefault("false")
  boolean enabled();

  /** The cookie idp sets and this process reads. Opaque: 256 random bits, stored hashed at idp. */
  @WithDefault("qits-session")
  String cookieName();

  /**
   * A door, with no trailing path: {@code https://wohlben.eu}, {@code http://localhost:8080}.
   *
   * <p><b>It is also what a name that names no project falls back to</b> — see {@link
   * EnvironmentAuthority}, which reads this value by the same right-to-left grammar as a request's
   * own Host. WHICH door it names therefore decides what the apex can compose: {@code
   * https://qits.wohlben.eu} gives the apex the platform project's own tier to build application
   * names on, and the bare {@code https://wohlben.eu} leaves it a door that redirects nowhere,
   * because every application address carries a project label and that value carries none.
   *
   * <p><b>The default stays the apex, and must, on a clone.</b> With ACME off this value is also
   * where the stated domain comes from — {@code EdgeRouter.domain} — so a local {@code
   * http://qits.localhost:8080} would make {@code qits.localhost} the DOMAIN and the grammar would
   * read every name one tier out. On the platform the domain is {@code qits.edge.acme.domain} and
   * the two are independent, which is where naming the project's door is the useful spelling.
   *
   * <p>For the login page it is only the FALLBACK, used while no deployment has published a host
   * for {@link #loginPath()}'s owner.
   */
  @WithDefault("http://localhost:8080")
  String canonicalOrigin();

  /**
   * The path that serves login, and the one whose owner decides where the page is: it lives on the
   * host of whichever deployment owns this route — {@code https://idp.wohlben.eu/idp/login} once
   * qits-platform-idp publishes {@code idp}.
   *
   * <p>The origin is still never inferred from a request Host: a passkey is bound to one WebAuthn
   * origin, and a host header is caller input. It comes from the deployment projection or from
   * {@link #canonicalOrigin()}.
   */
  @WithDefault("/idp/login")
  String loginPath();

  /**
   * Browser-facing authorities that may receive a person after login. The bootstrap supplies the
   * apex plus its environment host in domain mode, and localhost:port locally. This is an
   * allow-list, not a parent-domain suffix check.
   *
   * <p><b>An entry may be {@code *.<authority>}</b>, which matches ONE extra label in front of that
   * authority — {@code *.dev.acme.example.com} covers {@code ci.dev.acme.example.com} and refuses
   * {@code a.b.dev.acme.example.com}. Every service is its own browser host, so listing them here
   * would be a second copy of the deployment's app list; the wildcard is one line that follows it.
   * The port is part of the authority on both sides, so a name reached on another port matches
   * nothing.
   *
   * <p><b>One label, and only one.</b> The editor is one shared container for the whole platform on
   * an ordinary app vhost, {@code editor.dev.acme.example.com}, so the same wildcard covers it like
   * any other service. {@code evil.co.dev.acme.example.com} is covered by nothing.
   *
   * <p><b>The names are read right to left now</b> — {@code <app>[.<env>].<project>.<domain>} — so
   * an entry carries a project label, and a wildcard sits in front of the innermost door: {@code
   * *.<project>.<domain>} for an env-less project's applications, {@code
   * *.<env>.<project>.<domain>} for an env-supporting one's. A project label is live data and this
   * list is configuration, so a deployment states the projects whose applications a person may
   * return to; that is the whole point of an allow-list, and widening it to a parent-suffix check
   * would accept every name any caller could invent under the domain.
   *
   * <p><b>The default is the names a clone serves for the platform's own project</b>, which is
   * called {@code qits} and is a project like any other: its door, its applications either way its
   * {@code supportsEnvironments} flag stands — {@code *.qits.localhost:8080} while it has no
   * environments, {@code *.prod.qits.localhost:8080} while it has — and the apex, which is in the
   * list because startup demands the canonical origin be in it.
   */
  @WithDefault(
      "localhost:8080,qits.localhost:8080,*.qits.localhost:8080,*.prod.qits.localhost:8080")
  List<String> browserHosts();

  /**
   * The path prefixes served without any credential at all. {@code /idp/} wholesale, and one prefix
   * rather than a list of assets is the point: the login and register pages need their SPA files,
   * the protocol endpoints authenticate their own callers, and {@code /idp/api/*} guards itself. An
   * asset-path list would drift the first time the SPA renames a bundle.
   *
   * <p>These are paths on the OWNING service's own host — {@code idp[.<env>].<project>.<domain>},
   * where the login page lives. Nowhere else: a prefix served on every name would open one
   * service's routes on all of them, so every other host still refuses {@code /idp/}.
   */
  @WithDefault("/idp/")
  List<String> anonymousPrefixes();

  /**
   * How long an introspected session is believed without asking idp again, in milliseconds — and
   * therefore how long a logout or a revocation lingers. Thirty seconds rather than the Basic
   * cache's five minutes: a machine credential is revoked by rotating a secret, which is rare and
   * planned, whereas a person pressing "log out" expects the door to shut while they are still
   * looking at it.
   */
  @WithDefault("30000")
  long cacheTtlMs();

  /**
   * The most sessions held at once. A bound rather than a tuning knob, the same reason as {@link
   * AuthConfig#basicCacheSize()}: the key comes from a caller, so an unbounded map is a
   * caller-sized allocation.
   */
  @WithDefault("1024")
  int cacheSize();

  /**
   * How long past its freshness a cached session still answers while idp cannot be reached, in
   * milliseconds.
   *
   * <p><b>The lesson this exists for</b> is the one the token broker paid for on 2026-08-14: idp is
   * a container like any other and is redeployed like any other, and for a few seconds its name
   * refuses, drops or accepts-and-never-answers. A machine retries a push; a person watching a page
   * go blank sees the platform log them out. So a session idp has already vouched for outlives a
   * cutover — the session's own expiry is still honoured, so this widens no door that was open.
   */
  @WithDefault("60000")
  long staleGraceMs();

  /**
   * The edge's own static idp client id, for introspection. {@code {env}-qits-edge} on the
   * platform, seeded by the bootstrap.
   *
   * <p><b>No default, and that is deliberate.</b> A credential is a deployment fact — the bootstrap
   * injects {@code QITS_EDGE_SESSIONS_CLIENT_ID} and {@code QITS_EDGE_SESSIONS_CLIENT_SECRET}, and
   * those two spellings are a contract with cli/qits-cli-bootstrap. Absent while {@link #enabled()}
   * is off is the ordinary state and costs nothing; absent while it is ON fails at STARTUP — see
   * {@link EdgeSessions}, because the alternative is an edge that refuses every browser for a
   * reason only a stack trace holds.
   */
  Optional<String> clientId();

  /**
   * The secret half of {@link #clientId()}. Never logged, never cached, never sent anywhere but
   * idp's introspection endpoint.
   */
  Optional<String> clientSecret();
}
