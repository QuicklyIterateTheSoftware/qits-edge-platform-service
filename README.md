# qits-platform-edge

**The platform's L7 edge.** It binds the host's only public port, reads the `Host` name of every
request, and streams the request unchanged to whatever that name selects — an environment's gateway,
or one of that environment's services directly.

A small Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. It holds no
browser session of its own: the browser gate reads idp's, caches what idp said, and forgets it.

## Deployment routes

`qits-deployments` publishes every successful deployment as a durable `DeploymentActive` event.
The edge consumes both the live stream and qits-events' catch-up log, then replaces that
application's endpoint snapshot in its own PostgreSQL database. The canonical endpoint fields are
`path`, `upstreamHost`, `upstreamPort`, and, on one primary route at most, `navigationLabel` and
`navigationPosition`.

For an environment vhost, the longest matching active prefix is proxied straight to that upstream;
an unmatched path still goes to the environment gateway while the migration is in progress. A newer
event replaces the complete snapshot, so removing a route removes it rather than leaving a stale
endpoint live. An older event delivered late is ignored. The `/main-navigation` GET/HEAD document
is derived from the same active snapshot (`Home` plus labelled routes), carries `Cache-Control:
no-store`, and is never proxied.

The deployed edge therefore needs two provisioned PostgreSQL resources: `edge` for this projection
(`QITS_RESOURCE_EDGE_URL`, `_USERNAME`, `_PASSWORD`) and `eventstream` for the durable consumer's
claim ledger (the variables named by the qits-eventstream library). `qits.eventstream.enabled` is
left on in deployments; development and test profiles turn it off while Flyway still migrates both
stores.

### Startup is a rebuild, not a cache read

On every production start the edge resets the `edge-active-endpoints` consumer and replays its
`DeploymentActive` history from the epoch. It does this even when the eventstream claim ledger
survived: a durable watermark without the edge projection would otherwise make a freshly empty
database look caught up. Snapshot replacement is idempotent and last-writer-wins, so replaying over
a surviving projection is safe and there is no projection truncate.

Until qits-events explicitly marks the final catch-up page as its head, `/q/health/ready` is DOWN,
`/main-navigation` returns `503 Retry-After: 1`, and every non-`/q` request returns the same
retryable `503`. The edge therefore never presents a partial direct-routing or navigation view as
authoritative. An unavailable event log or a failed event handler leaves it down and retries after
`qits.edge.projection.catchup.retry`; a confirmed head enables admission. The ordinary generic
eventstream startup sweep is disabled here because this named, readiness-owning rebuild is the
startup path; scheduled sweeps remain the post-start safety net.

```
                          ┌────────────────────────────────────────┐
  client ──:8080──▶       │ qits-platform-edge  (the only          │
  Host: registry.dev.…    │ published port on the host)            │
                          │   Host name → upstream · idp · /q      │
                          └──┬──────────────┬──────────────┬───────┘
                             │ docker networks, nothing published
              dev            │      prod    │              │  dev's registry
        ┌────────────────────▼┐  ┌──────────▼─────────┐  ┌─▼──────────────────┐
        │ dev-qits-gateway    │  │ prod-qits-gateway  │  │ dev-qits-artifacts │
        │  :8080              │  │  :8080             │  │  :8080             │
        └─────────────────────┘  └────────────────────┘  └────────────────────┘
```

## The routing model

A `Host` name selects an environment, and optionally an application inside it:

| Host                        | Goes to                                             |
| --------------------------- | --------------------------------------------------- |
| `prod.example.com`          | the `prod` gateway — `$env.$domain`                 |
| `registry.prod.example.com` | `prod`'s `registry` upstream — `$app.$env.$domain`  |
| `registry.dev.example.com`  | `dev`'s `registry` upstream — same entry, other tier |
| `example.com`               | the **default** environment's gateway               |
| `staging.example.com`       | the **default** (`staging` names no environment)    |
| `localhost`, `127.0.0.1`, `[::1]` | the **default**                               |
| no `Host` at all            | the **default**                                     |
| `mirror.dev.example.com`, `mirror` unconfigured | **404** — see below             |

Only the **first two labels** are read, which is why the domain itself is never configured: it may
be one label, two or three, and the edge does not have to know. An environment name at position 1
wins over one at position 0, so `staging.prod.example.com` is *application `staging` in environment
`prod`* — an application may be called anything, whereas a domain whose first label happens to be an
environment name is a coincidence nobody arranges.

An unmatched name is **not an error**. Every one of them goes to the default environment, so a
mistyped URL reaches the platform's own page rather than a connection error.

**An app-shaped name is the one exception, and it is deliberate.** A first label in front of a
*known* environment was aimed at a service, and services are the names this edge authenticates —
falling through to the gateway would hand exactly those requests to the one hop that does not. So an
unconfigured app label is a **404**, not the gateway. Names that are not app-shaped are untouched by
the rule.

The upstream is `<env>-qits-gateway:8080` for a gateway and the app's own pattern for an
application. Nothing in a request ever contributes a character to an address: a `Host` selects an
*index into a fixed list*, which is the whole SSRF guard.

## What it does not do — the non-goals, on purpose

- **No hand-maintained path table.** Direct prefixes are deployment facts consumed from the durable
  event log, never an enum or an edge environment variable. The auth gate remains per vhost — per
  vhost and *method*, never per path — and direct proxying preserves the request path unchanged.
  `/token` remains the single path this process claims on an application vhost.
- **No login page and no session of its own.** The edge *reads* a session — it introspects the
  `qits-session` cookie at idp and turns it into identity headers (see *Browser sessions* below) —
  but it issues none, stores none, and serves no page. Registration, login and logout are
  qits-platform-idp's, reached through the anonymous `/idp/` prefix like any other path.
- **No header stripping or injection beyond `X-Forwarded-*` and `X-Qits-*`.** The reserved prefix is
  stripped, and the three identity headers are asserted, only on the environment vhost and only
  while the session gate is on. The environment gateway still does its own hygiene and has to: a
  request can reach it from qits-net without passing this process. `Authorization`, `Cookie` and
  every custom header pass through untouched.
- **No UI, no SPA, no landing page, no `/api`.** The paths this process answers are `/q` and, on an
  application vhost only, `/token`.
- **No TLS of its own to configure.** The image carries a Let's Encrypt certificate *slot* and
  nothing in it (see below). With no keystore from the deployment the edge speaks plain HTTP, and a
  terminator in front of it stays a deployment choice; see `X-Forwarded-Proto` below.

## What it does do

- **Streams.** Request and response bodies are never buffered, so SSE channels, `git clone`, OCI
  layer pushes and chunked responses all pass through. `EdgeRoutingTest` times the first chunk of a
  slow response, which is the only assertion that catches a buffering regression.
- **Forwards WebSocket upgrades**, which is what carries the platform's interactive PTY terminals.
- **Keeps the client's `Host`.** `vertx-http-proxy` leaves a proxied request's authority unset and
  the client then fills `Host` in from the socket it opened, so without the fix in `EdgeHeaders`
  every request would reach the gateway claiming to be for `prod-qits-gateway:8080`. Redirects,
  cookie domains and absolute URLs are all built from that name.
- **Adds `X-Forwarded-For` / `-Host` / `-Proto`, only when absent.** The edge is not always the
  outermost hop: a TLS terminator in front of it is the only thing that can tell the truth about
  `https`, so overwriting would replace a true value with a false one. Consequently **nothing may
  make a trust decision on these three**; they are diagnostics and link generation.
- **Answers `/q/health/{live,ready}` itself**, never proxied, whatever the `Host` says. Readiness
  reports the resolved environment → upstream map as health data and stays DOWN until the
  deployment projection has reached qits-events' confirmed head.

### The one known gap: `Host` on a WebSocket handshake

`vertx-http-proxy` short-circuits an upgrade before installing its interceptor chain and rebuilds
the handshake with the client's own `Host` dropped, and there is no hook before it. So an upstream
reads a socket's original host name from **`X-Forwarded-Host`**, which the edge does set on that
path, and not from `Host`. It costs nothing today, because a handshake's `Host` is a protocol
formality rather than something an environment gateway routes on — but if that ever changes, this is
where to look.

## Configuration

Every key is overridable by environment variable, so a deployment declares the whole surface without
a file.

| Key | Env | Default | What it is |
| --- | --- | --- | --- |
| `qits.edge.environments` | `QITS_EDGE_ENVIRONMENTS` | `prod` | The routable environment names, comma separated |
| `qits.edge.default-environment` | `QITS_EDGE_DEFAULT_ENVIRONMENT` | `prod` | Where the apex and every unmatched host go. **Must be in the list** |
| `qits.edge.upstream-host-pattern` | `QITS_EDGE_UPSTREAM_HOST_PATTERN` | `{env}-qits-gateway` | `{env}` is the only placeholder |
| `qits.edge.upstream-port` | `QITS_EDGE_UPSTREAM_PORT` | `8080` | The port every environment gateway listens on |
| `qits.edge.upstream-hosts.<env>` | `QITS_EDGE_UPSTREAM_HOSTS_<ENV>` | — | Per-environment override, `host` or `host:port` |
| `qits.edge.apps.<app>.host-pattern` | `QITS_EDGE_APPS_<APP>_HOST_PATTERN` | — | **Required per app.** `{env}` is the only placeholder; a platform service names none |
| `qits.edge.apps.<app>.port` | `QITS_EDGE_APPS_<APP>_PORT` | `8080` | The port that application listens on |
| `qits.edge.apps.<app>.hosts.<env>` | `QITS_EDGE_APPS_<APP>_HOSTS_<ENV>` | — | Per-environment override, `host` or `host:port` |
| `qits.edge.projection.catchup.required` | `QITS_EDGE_PROJECTION_CATCHUP_REQUIRED` | `true` | Requires a complete deployment-history rebuild before the edge is ready; turn off only in an intentionally offline test/dev setup |
| `qits.edge.projection.catchup.retry` | `QITS_EDGE_PROJECTION_CATCHUP_RETRY` | `PT1S` | Delay before retrying an incomplete, failed, or unavailable deployment-history read |
| `qits.idp.url` | `QITS_IDP_URL` | `http://qits-platform-idp:8080/idp` | The issuer. `/jwks` and `/token` are derived from it, never configured |
| `qits.edge.auth.enforce-on-apps` | `QITS_EDGE_AUTH_ENFORCE_ON_APPS` | `true` | Application vhosts require a valid idp token |
| `qits.edge.auth.enforce-on-environments` | `QITS_EDGE_AUTH_ENFORCE_ON_ENVIRONMENTS` | `false` | Environment vhosts do not — **flipping it is a step of its own** |
| `qits.edge.auth.anonymous-read-apps` | `QITS_EDGE_AUTH_ANONYMOUS_READ_APPS` | — | App labels whose `GET` and `HEAD` are open; every other method on them still needs a token |
| `qits.edge.auth.audience-pattern` | `QITS_EDGE_AUTH_AUDIENCE_PATTERN` | `{env}-qits-artifacts` | The audience a token must name; `{env}` is resolved per request, a value without it is a literal |
| `qits.edge.auth.clock-skew-seconds` | `QITS_EDGE_AUTH_CLOCK_SKEW_SECONDS` | `30` | How far this clock and idp's may disagree about `exp` |
| `qits.edge.auth.jwks-refresh-cooldown-ms` | `QITS_EDGE_AUTH_JWKS_REFRESH_COOLDOWN_MS` | `5000` | Shortest gap between two JWKS fetches |
| `qits.edge.auth.basic-cache-ttl-ms` | `QITS_EDGE_AUTH_BASIC_CACHE_TTL_MS` | `300000` | Ceiling on how long a validated HTTP Basic credential is believed; the minted token's own life is the other half |
| `qits.edge.auth.basic-cache-size` | `QITS_EDGE_AUTH_BASIC_CACHE_SIZE` | `1024` | The most validated credentials held at once, least-recently-used |
| `qits.edge.auth.idp-retry-window-ms` | `QITS_EDGE_AUTH_IDP_RETRY_WINDOW_MS` | `45000` | How long a redeploying idp is waited out before the edge answers an error |
| `qits.edge.auth.idp-call-timeout-ms` | `QITS_EDGE_AUTH_IDP_CALL_TIMEOUT_MS` | `5000` | How long ONE call to idp may take, connection included — **what makes an answer certain** |
| `qits.edge.sessions.enabled` | `QITS_EDGE_SESSIONS_ENABLED` | `false` | Whether a browser needs a session on the environment vhost — **the rollout flag** |
| `qits.edge.sessions.cookie-name` | `QITS_EDGE_SESSIONS_COOKIE_NAME` | `qits-session` | The cookie idp sets and this process reads |
| `qits.edge.sessions.canonical-origin` | `QITS_EDGE_SESSIONS_CANONICAL_ORIGIN` | `http://localhost:8080` | The sole WebAuthn/login origin; domain bootstrap sets the apex HTTPS origin |
| `qits.edge.sessions.login-path` | `QITS_EDGE_SESSIONS_LOGIN_PATH` | `/idp/login` | Where a navigation with no session is sent |
| `qits.edge.sessions.browser-hosts` | `QITS_EDGE_SESSIONS_BROWSER_HOSTS` | `localhost:8080` | Exact browser return authorities; machine vhosts must not appear |
| `qits.edge.sessions.anonymous-prefixes` | `QITS_EDGE_SESSIONS_ANONYMOUS_PREFIXES` | `/idp/` | Path prefixes served with no credential at all |
| `qits.edge.sessions.cache-ttl-ms` | `QITS_EDGE_SESSIONS_CACHE_TTL_MS` | `30000` | How long an introspected session is believed — and how long a logout lingers |
| `qits.edge.sessions.cache-size` | `QITS_EDGE_SESSIONS_CACHE_SIZE` | `1024` | The most sessions held at once, least-recently-used |
| `qits.edge.sessions.stale-grace-ms` | `QITS_EDGE_SESSIONS_STALE_GRACE_MS` | `60000` | How long a cached session outlives an **unreachable** idp |
| `qits.edge.sessions.client-id` | `QITS_EDGE_SESSIONS_CLIENT_ID` | — | The edge's own idp client (`{env}-qits-edge`), for introspection |
| `qits.edge.sessions.client-secret` | `QITS_EDGE_SESSIONS_CLIENT_SECRET` | — | Its secret. Both are seeded by the bootstrap |
| `qits.observability.url` | `QITS_OBSERVABILITY_URL` | `http://qits-observability:8080` | Where telemetry goes; the OTLP endpoint is derived from it |

Five things fail **at startup** rather than per request, deliberately: an environment or application
name that could not be a DNS label, a default that is not in the list, an application that shares an
environment's name (the tie-break would make that environment unreachable), and the session gate
turned on with no client id and secret to introspect with. All would otherwise be a 502, a 404, a
connection error or a 401 on every request, with nothing to read.

The applications map is shipped **empty**, and an application entry is the on-switch: a name only
reaches a service when a deployment names it. A deployment declares the whole set without a file:

```
QITS_EDGE_APPS_REGISTRY_HOST_PATTERN={env}-qits-artifacts
QITS_EDGE_APPS_GITHOST_HOST_PATTERN={env}-qits-githost
QITS_EDGE_APPS_MIRROR_HOST_PATTERN=qits-platform-mirror
```

`{env}` is what keeps a tier's services separate, and its absence is what marks a **platform**
service — there is one qits-platform-mirror for the whole host, so its entry carries no placeholder
while an environment's registry carries one and serves every tier from a single line.

`qits.edge.upstream-hosts` exists for the two topologies the pattern cannot describe — a developer
running one gateway on `localhost:8000`, and this repository's own suite, where the gateways are
stub servers on ephemeral ports. Prefer the pattern: an override is a second place an environment's
address is written, and a stale one sends a whole tier's traffic to the wrong process. Note also
that a `@ConfigMapping` map key cannot be **unset** by a later config source, only overridden, which
is why none is shipped in `application.properties`.

## Authentication — terminated here, on the first node

The edge sees every request before anything else does and already reads the `Host` header, so it is
the right and cheapest place to gate. What it gates is a **vhost**, never a path: an application
vhost fronts a service with no external auth of its own.

A request to an application vhost must carry an idp credential: an access token, or the client id
and secret it is minted from (see *HTTP Basic* below). A token is validated **offline**
against the keys fetched from `qits.idp.url/jwks` and cached — idp is overlay-only, so a host client
cannot reach it, and keeping it off the per-pull path is worth more than the freshness a call-out
would buy. An unknown `kid` buys **one** refresh, behind a cooldown, so a made-up kid cannot turn
into a request per request at the identity provider. The checks are RS256 only, exact `iss`, live
`exp` within the skew, and the demanded audience in `aud`.

**The audience is derived per request**, from `qits.edge.auth.audience-pattern` with `{env}` filled
in from the environment the vhost named — the same placeholder as the host patterns above. idp's
audience values are env-prefixed, so this is what keeps the tiers apart: a token minted for
`registry.dev.…` does not open `registry.prod.…`, from one configuration entry. A pattern with no
placeholder is a literal audience, for a single-audience deployment.

### Anonymous reads, named per app

`qits.edge.auth.anonymous-read-apps` lists app labels whose **`GET` and `HEAD` pass without a
credential**. Every other method on those same names keeps the whole check, so this opens reads and
never a service.

The reads are the bootstrap steps: pulling a base image onto a fresh node, cloning a repository,
fetching a dependency from the mirror — each happens *before* there is anything to hold a token, and
each is what a gated vhost breaks first. A push, a tag delete, a `receive-pack` is never a bootstrap
step.

The list is empty by default, which is full enforcement. It is matched against the app label the
`Host` name already resolved to, so it reaches app vhosts only — the environment vhost is untouched
by any value here, and an unconfigured label is still a `404` rather than an open door.

### The docker flow

`docker login` stores a password and resends it forever, while an idp token lives ~300 seconds and
cannot be refreshed. The Distribution spec's own answer is the Bearer token-endpoint flow, and it is
what this implements:

1. docker asks for something and gets `401` with
   `WWW-Authenticate: Bearer realm="http://<vhost>/token",service="<vhost>"`;
2. docker GETs that realm with HTTP **Basic** — an idp **client id and client secret**, which is the
   durable credential a user stores with `docker login`;
3. the edge brokers a `client_credentials` grant to idp over qits-net and answers
   `{"token": …, "access_token": …, "expires_in": …}`;
4. docker retries with `Authorization: Bearer …`, and re-fetches when it expires.

The grant asks idp for **no specific audience**, which gets the client's whole allowed list; naming
one here would fail at idp with an `invalid_target` the caller cannot read, whereas checking `aud`
on the way back in puts the refusal where the reason is known. docker's `service` and `scope` query
parameters are read and dropped: the audience the token carries *is* the permission, and
per-repository grants would be a change to the platform's claim model rather than to this process.

### HTTP Basic, for the clients that cannot do the dance

maven, npm and git send `Authorization: Basic` and nothing else — none of them reads a Bearer
challenge, fetches a token and retries — so a gated request carrying **Basic** is accepted on its
own terms. The edge validates it the only way a client id and secret can be validated: it spends
them at idp, exactly as `/token` does, and then treats the token that comes back as if the caller
had presented it. Same issuer, same `exp`, same signature, same demanded audience — a commissioned
client opens precisely the vhosts its audiences name.

A credential that is not base64 of `<id>:<secret>` is refused **here**, without a call: there is
nothing to ask idp about, and asking would hold the caller for the whole retry window below.

The result is cached against a **SHA-256 of the credential** — never the credential — for the
shorter of the minted token's life and `qits.edge.auth.basic-cache-ttl-ms`, in a bounded LRU. Without
it, a Basic client resends its credential on every request, so every dependency fetch would put an
idp round trip on the path. What is cached is the credential's own soundness plus the audiences it
carried; the demanded audience is still answered per request, so one cached validation cannot cross
tiers. **Refusals are not cached**: a rotated secret must start working the moment it is right.

The `401` stays the Bearer challenge whatever the credential was — docker is the client that reads
it — and carries `error="invalid_token"` when a credential was presented and refused.

### An identity provider that is not there

idp is a container like any other and is redeployed like any other. For a few seconds its name
refuses, drops, or accepts a connection and never answers, and on 2026-08-14 a deploy push died with
"the identity provider could not be reached" for landing inside that window.

Every dial at idp — the `/token` broker, the Basic validation, the JWKS fetch — is therefore

- **bounded per attempt** by `qits.edge.auth.idp-call-timeout-ms`, connection included, and
- **retried** on connection-classed failures with a doubling backoff until
  `qits.edge.auth.idp-retry-window-ms` runs out.

An **answer** from idp is never retried: a `401` is idp deciding, and repeating the question would
turn one refusal into a burst of them. Only the network is.

The per-attempt timeout is the load-bearing half. A Vert.x client is built with no request timeout
and no idle timeout, so an idp that accepts a connection and then says nothing leaves the call
outstanding with nothing to end it — no status, no body, until the inbound connection's own idle
timeout closes it an hour later. A docker client has no timeout of its own on a realm call, so what
that looks like from the outside is a `docker push` that hangs rather than fails.

## Browser sessions — canonical at the apex

Machine credentials are the section above. A **person** carries neither a token nor a client secret,
so the environment vhost — the one name a browser ever types — gates a `qits-session` cookie
instead. The bootstrap enables `qits.edge.sessions.enabled` only after it has seeded the edge's
introspection credential and the IdP's browser SSO settings.

With the flag on, a request to `$env.$domain` is decided like this:

1. every inbound `X-Qits-*` header is dropped — the reserved prefix is what a hop *asserts*, so
   nothing a client sends under it may survive;
2. an `Authorization: Bearer` or `Basic` takes the machine path above, checked in full, and is
   proxied with **no** identity headers — a machine's identity is in its token;
3. a `qits-session` cookie is introspected at idp and becomes `X-Qits-User`, `X-Qits-User-Id` and
   `X-Qits-Roles` (comma-separated — a role never holds a comma, and one that did is dropped);
4. a path under `/idp/` is proxied anonymously, because the login page has to be reachable by
   somebody who cannot log in yet;
5. anything else is refused: a **navigation** (`Sec-Fetch-Mode: navigate`, or a `GET` accepting
   `text/html`) gets `302` to the canonical apex `/idp/login`, carrying its configured return host
   and path; everything else gets `401`.

Application vhosts never accept a browser session as authentication. A parent-domain cookie is
removed before registry, mirror, and git-host traffic is proxied; unrelated cookies and all machine
credentials pass through unchanged.

**The return target is two allow-lists, not a reflected URL.** The edge accepts only a configured
browser authority and a single-slash path; the IdP validates the same authority once more before
the SPA navigates. `//host`, `/\host`, an absolute URL, a control character, or an unlisted host
lands at the apex front door.

**A dead cookie still reaches `/idp/`.** The prefix answers every caller with no usable credential,
not only the ones carrying none — otherwise a browser holding a revoked session would be redirected
to a login page it is refused at, forever. This is the one place the order differs from the plan's,
and the reason is that loop.

**A 401 here carries no `WWW-Authenticate`**, unlike an application vhost's. A `Basic` challenge
pops the browser's own credential dialog on every background fetch a logged-out tab makes, and the
credential a browser holds is a cookie. The JSON body names the login page instead, so an SPA can
send the user there itself.

### Introspection, and the cache in front of it

The cookie is **opaque** — 256 random bits, stored hashed at idp — so this process cannot decide
anything about it alone: it `POST`s `<qits.idp.url>/api/sessions/introspect` with its own client id
and secret in HTTP Basic and reads `{userId, username, roles, expiresAt}`. A non-200 is a refusal.
The alternative, a signed cookie verified offline against the JWKS already held here, would cost
revocation — a logout would be a row idp changed and nobody read.

The dial is bounded exactly like every other call at idp (`idp-call-timeout-ms` per attempt,
connection-classed retries inside `idp-retry-window-ms`, an **answer** never retried). The result is
cached against a **SHA-256 of the cookie** — never the cookie — in a bounded LRU, for
`cache-ttl-ms`. Refusals are not cached: a browser that has just logged in must not keep being
refused.

`stale-grace-ms` is the lesson the token broker paid for on 2026-08-14: idp is redeployed like any
other container, and for a few seconds its name refuses or never answers. A machine retries a push
and nobody notices; a person is logged out mid-click. So a session idp has already vouched for
answers for that much longer while idp is **unreachable** — never when idp *answers* no, and never
past the session's own `expiresAt`, so it widens no door that was open.

**Revocation lags by `cache-ttl-ms`** (30 seconds by default). Stated so nobody files it as a bug.

### The rollout switch

Application vhosts enforce from their first request — nothing reached them before, so there is no
"before" to stay compatible with. The **environment vhost does not**, and
`qits.edge.auth.enforce-on-environments` is off: the platform's whole existing traffic comes through
that path and authenticates one hop further in, at the environment gateway. Flipping it before that
termination has moved out here would answer every browser, SPA and API client with a `401` it cannot
act on. It moves when the gateway's auth moves, as a step of its own.

### Telemetry

Traces, logs and metrics leave over OTLP `http/protobuf` to qits-observability, the same block every
qits service carries. One key names the receiver — `qits.observability.url`, host and port with no
path — and the ingest path is derived from it, because that path belongs to the receiver rather than
to the deployment. `OtelLogConfigTest` pins the endpoint and the four log keys so a changed Quarkus
default cannot switch log export off with a green build.

The SDK is **disabled under `%dev` and `%test`**: a clone-alone `./mvnw verify` has no receiver to
reach, and an exporter retrying against an unresolvable name turns the suite into a wall of export
failures. Telemetry is real in a deployment.

### TLS: wildcard certificates through DNS-01

The `acme/` module is the edge's ACME client. For a configured apex it orders one SAN certificate
covering the apex, `*.<domain>`, and `*.<environment>.<domain>` for every configured environment.
That covers platform names such as `idp.wohlben.eu` and project names such as
`qits.dev.wohlben.eu` without issuing one certificate per hostname.

The manager writes short-lived `_acme-challenge` TXT values through Hetzner's Cloud API, waits until
both Cloudflare and Google public DNS-over-HTTPS resolvers observe them, and removes only the value
it created. ACME account state and certificates persist on the TLS volume. Successful certificates
are installed in immutable version directories and an atomic `current` symlink switch lets Quarkus'
TLS registry reload them without restarting the edge.

Set `QITS_EDGE_ACME_MODE=staging` until the whole DNS path works, then switch to `production`. A
non-expiring production certificate is never replaced by staging. `QITS_DNS_HETZNER_TOKEN` is a
secret and must be supplied by deployment configuration, never committed or logged. Replicas use a
database lease so only one of them can place an order or renew at a time.

### Deployment

The deployer must publish the port — this is the one container on the host reached from outside
docker:

```
-p 8080:8080
```

`.config/qits/deployments.yml` makes this a **platform** service, deploying from `environment/prod`.
One edge exists because there is one host port to bind, and it fronts every environment's gateway,
so it belongs to no tier. The target is also what makes the routing work: a platform service joins
every environment's per-application networks, so `<env>-qits-gateway` resolves for every name in
`qits.edge.environments`. An environment service would reach only its own tier's gateway and answer
502 for the rest.

## Building

```bash
./mvnw verify                       # unit tests + the end-to-end proxy suite (no docker, no network)
./mvnw package                      # JVM build -> target/quarkus-app/
./mvnw quarkus:dev                  # dev mode on :8100
sdk env && ./mvnw package -Dnative  # native binary -> target/qits-platform-edge (no docker)
./mvnw test -Dtest=HostEnvironmentsTest

docker build -t qits/platform-edge:latest -f docker/Dockerfile .
```

**A clone of this repository alone builds and tests green** — no monorepo, no submodule, no docker,
no network, no credentials. That is why the pom duplicates the platform's Quarkus and JDK versions
instead of inheriting them.

`.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a `native-image` and the compile runs
in-process. **A missing GraalVM does not fail the build**: Quarkus logs `Cannot find the
native-image ... Attempting to fall back to container build` and shells docker with a Mandrel image.
Green either way, so recognise the fallback by the image pull. Leave it working — it is what a
GraalVM-less CI gets — but it is not the declared path.

Spotless (google-java-format) runs at `process-sources`, so formatting is never a review topic.

## The suite

Everything end to end lives in **one** `@QuarkusTest` class per JVM, and it has to stay that way. A
WebSocket upgrade through `vertx-http-proxy` only survives the **first** Quarkus start in a JVM;
after a restart it silently degrades to a plain proxied GET, so the handshake fails with nothing
logged anywhere. It is a property of the test harness, not of this code (qits-gateway paid for
finding it, and works around it with a second surefire execution). A restart happens when a test
class needs a different configuration from the one before it — so one class, one test resource, one
start is the cheapest immunity. **Splitting `EdgeRoutingTest` is how the socket tests start failing
for no visible reason.**

`EdgeSessionGateTest` is the one exception and it pays the fare: the session gate is a boot-time
flag, so proving both of its states needs two starts. It runs in a **second surefire execution**,
which forks a second JVM in which its application is the first start — the same workaround
qits-gateway uses, spelled out in `pom.xml`. `EdgeRoutingTest` keeps the flag **off**, which is what
makes it the proof that off changes nothing: it is unchanged.

Two harness details worth knowing before they cost an afternoon:

- `EdgeClient` uses Vert.x's `RequestOptions.setServer` to say a name it does not resolve. Vert.x
  **ignores** that on `HttpClient.webSocket` and resolves for real, so the socket tests use the JDK
  client instead — which needs `-Djdk.httpclient.allowRestrictedHeaders=host`, set on surefire's
  `argLine` in `pom.xml`.
- `quarkus.http.test-port=0`. On the deployment host 8081 is the platform's own npm registry, so the
  Quarkus default fails with a bind error that reads like a flake.
- The **management interface starts with the suite** now, on Quarkus' default management test port,
  9001. It stays a fixed port because `@TestHTTPResource(management = true)` has to be able to name
  it. A bind error there is a busy 9001, not a flake either.

## Relationship to qits-gateway

They are the same idea one hop apart, and the split is deliberate: this one demultiplexes
**environments by host name**, the other demultiplexes **services by path** inside one environment.
Keep the Quarkus platform version and the JDK release in step with it.
