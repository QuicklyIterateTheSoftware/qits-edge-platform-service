# qits-platform-edge

**The platform's L7 edge.** It binds the host's only public port, reads the `Host` name of every
request, and streams the request unchanged to the service that name selects. An environment's own
name is a door: it answers `GET /` with a redirect to the projects host and serves nothing else.

A small Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. It holds no
browser session of its own: the browser gate reads idp's, caches what idp said, and forgets it.

## Deployment routes

`qits-deployments` publishes every successful deployment as a durable `DeploymentActive` event.
The edge consumes both the live stream and qits-events' catch-up log, then replaces that
application's snapshot in its own PostgreSQL database. A snapshot is three things:

- **endpoints** — `path`, `upstreamHost`, `upstreamPort`, in declaration order. The **first** is the
  application's primary route: the segment its SPA is served under, and the upstream its own name
  resolves to;
- **`browserHost`** — the DNS label the service answers to, `ci` for `ci.dev.example.com`. Absent
  until a service has been flipped, and that absence is what makes this release inert;
- **`navigation`** — placements, at most one per slot: `{"slot":"services.details","label":"CI",
  "position":2}`. The slot vocabulary is closed and lives in `EdgeRoutes.SLOTS`.

An **older frame** carried one `navigationLabel`/`navigationPosition` pair on an endpoint instead.
Every frame ever published is replayed on every start, so that shape still means what it meant: one
`system` placement, and no host — the edge never invents a public name for a service that has not
been flipped.

A newer event replaces the complete snapshot, so removing a route removes it rather than leaving a
stale endpoint live. An older event delivered late is ignored. The `/main-navigation` GET/HEAD
document is derived from the same snapshots, carries `Cache-Control: no-store`, and is never
proxied.

A frame is **refused whole** — logged and settled, routes unchanged — when it publishes a path
another application owns, a host another application owns, a host that is not a DNS label, a host
that is an environment name, an unknown slot, or a host that is also a configured
`qits.edge.apps.<app>` entry pointing somewhere else. The last one is a match rather than a ban:
`registry` is both a configured vhost and the name qits-artifacts publishes, and they are the same
service exactly when the configured pattern resolves to the address the deployment published.

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
| `prod.example.com`          | the `prod` **door** — `$env.$domain`, and it serves nothing |
| `registry.prod.example.com` | `prod`'s `registry` upstream — `$app.$env.$domain`  |
| `registry.dev.example.com`  | `dev`'s `registry` upstream — same entry, other tier |
| `registry.example.com`      | the **default** environment's `registry` — `$app.$domain`, see below |
| `example.com`               | the **default** environment's door                  |
| `staging.example.com`       | the **default** door (`staging` names no environment) |
| `localhost`, `127.0.0.1`, `[::1]` | the **default** door                          |
| no `Host` at all            | the **default** door                                |
| `ci.dev.example.com`, published by a deployment | `dev`'s ci service — its SPA at `/` and every route it owns |
| `mirror.dev.example.com`, `mirror` unconfigured and unpublished | **404** — see below |

Only the **first two labels** are read, which is why the domain itself is never configured: it may
be one label, two or three, and the edge does not have to know. An environment name at position 1
wins over one at position 0, so `staging.prod.example.com` is *application `staging` in environment
`prod`* — an application may be called anything, whereas a domain whose first label happens to be an
environment name is a coincidence nobody arranges.

**The environment label is optional for the default environment.** That environment's door is the
apex — it is where a browser lands — so `ci.example.com` is its ci service, `idp.example.com` is
where the login page is, and `ci.dev.example.com` stays the long spelling of the same place. Only a **known**
application label reads this way: configured in `qits.edge.apps`, or published by a deployment in
the default environment. The apex itself is never one, and an unknown first label is not either.

Precedence is therefore three readings and then the fallback, and an environment name still wins at
either position, so no tier can be hidden by an application that shares its spelling:
`$env.$domain`, `$app.$env.$domain`, `$app.$domain`, the default environment.

An unmatched name is **not an error**. Every one of them goes to the default environment, so a
mistyped URL reaches the platform's own page rather than a connection error.

**An app-shaped name is the one exception, and it is deliberate.** A first label in front of a
*known* environment was aimed at a service, and services are the names this edge authenticates —
falling through to the gateway would hand exactly those requests to the one hop that does not. So an
unconfigured app label is a **404**, not the gateway. Names that are not app-shaped are untouched by
the rule.

**A name reaches a service two ways.** `qits.edge.apps` is the configured one — the machine vhosts,
and the auth attributes that go with them. The deployment projection is the other: a service
publishes the name it answers to, and that name then serves its SPA at `/` and every wire route it
owns. The two are the same kind of vhost, so everything below treats them alike.

On such a name, **another application's PRIMARY route is still routed to that application**:
`/projects/api`, `/workspaces/container`, `/ci/api` work from every host, which is what keeps a
platform of a dozen SPAs same-origin with no CORS anywhere. The primary route is the segment an
application is *known* by, so it means the same thing on everybody's name.

**Its other routes do not travel.** `/v2`, `/git`, `/bootstrap-git` are wire protocols that several
services legitimately answer — qits-artifacts and the pull-through mirror both speak `/v2` — and
only one of them can own the path in a projection whose paths are unique per environment. Routing
it everywhere would send `mirror.dev/v2/` at the registry and break every pull through the mirror.
So on a service's own name a secondary route falls through to that service, exactly like a path
nobody declared: `mirror.dev/v2/…` reaches the mirror, `registry.dev/v2/` reaches artifacts because
that is its own host, `githost.dev/git/…` reaches the git host, and `ci.dev/git/…` reaches ci and
404s there — a clone URL names the environment origin, not a service's.

A bare `/` never travels either, whoever declared it: that is the catch-all of one application, and
on a service's own name the catch-all is that service.

Nothing in a request ever contributes a character to an address: a `Host` selects an *index into a
fixed list* or a *row of the projection*, which is the whole SSRF guard.

### The environment vhost is a door, and a door serves nothing

Every service is on its own name, so the environment's own name routes nothing: no `/<seg>`, no
`/<seg>/api`, no `/v2`, no `/git`, no `/idp/`. A second address for something that already has one
is a second origin, a second cookie scope and a second thing to keep in step.

What the door answers is:

- `GET /` (and `HEAD /`) — `302` to qits-projects' host once the projection names one: the `system`
  placement qits-projects publishes, or a host called `projects`. An anonymous visitor lands on the
  login through the host that owns it. `404` while no such host is known;
- `/q` and `/main-navigation` — the edge's own two surfaces;
- everything else — `404` in plain text, naming `<app>.<authority>`, logged at INFO so anything
  still dialling the door can be found.

There is no gate on the door, because there is nothing behind it to gate: a session cookie and a
machine token are both answered `404` like any other request.

## What it does not do — the non-goals, on purpose

- **No hand-maintained path table.** Direct prefixes are deployment facts consumed from the durable
  event log, never an enum or an edge environment variable. The auth gate is per vhost and per
  *credential*, never per path, and proxying preserves the request path unchanged — the door's `GET
  /` answers rather than rewrites. `/token` remains the single path this process claims on a
  configured application vhost.
- **No login page and no session of its own.** The edge *reads* a session — it introspects the
  `qits-session` cookie at idp and turns it into identity headers (see *Browser sessions* below) —
  but it issues none, stores none, and serves no page. Registration, login and logout are
  qits-platform-idp's, reached through the anonymous `/idp/` prefix on idp's own host.
- **No header stripping or injection beyond `X-Forwarded-*` and `X-Qits-*`.** The reserved prefix is
  stripped, and the three identity headers are asserted, only while the session gate is on and only
  where a session was actually used: a service vhost a browser reached with its cookie. A service
  still does its own hygiene and has to — a request can reach it from qits-net without passing this
  process. `Authorization`, `Cookie` and every custom header pass through untouched.
- **No UI, no SPA, no landing page, no `/api`.** The paths this process answers are `/q`,
  `/main-navigation`, and, on a configured application vhost only, `/token`. It knows nothing about
  projects or repositories: a navigation slot says WHERE the shell hangs an entry, and the shell
  decides what hangs there.
- **No TLS of its own to configure.** The image carries a Let's Encrypt certificate *slot* and
  nothing in it (see below). With no keystore from the deployment the edge speaks plain HTTP, and a
  terminator in front of it stays a deployment choice; see `X-Forwarded-Proto` below.

## What it does do

- **Streams.** Request and response bodies are never buffered, so SSE channels, `git clone`, OCI
  layer pushes and chunked responses all pass through. `EdgeRoutingTest` times the first chunk of a
  slow response, which is the only assertion that catches a buffering regression.
- **Forwards WebSocket upgrades**, which is what carries the platform's interactive PTY terminals.
  Upgrades are the edge's **own** path, `EdgeWebSocketUpgrade`, not `vertx-http-proxy`'s built-in
  one: under Quarkus the inbound request reaches the proxy already read, its upgrade path then
  crashed mid-handshake — after the upstream had accepted — and every attempt leaked one upstream
  pool connection, neither closed nor returned. At the pool's 64 the whole origin hung, plain GETs
  included, with nothing logged. The edge's path never registers a body handler on the handshake
  (a WebSocket client sends nothing before the `101`), closes the upstream connection on every
  failure after acquisition, and answers a refused upgrade with the upstream's own status.
  Saturation is bounded and visible now, whatever causes it: the per-origin pool (64) fronts a
  bounded wait queue (256) and every origin acquisition carries a 30 s bound, so exhaustion answers
  fast — 503 on the upgrade path, 502 through the proxy — with a WARN line naming the origin,
  instead of queueing forever with nothing logged.
- **Keeps the client's `Host`.** `vertx-http-proxy` leaves a proxied request's authority unset and
  the client then fills `Host` in from the socket it opened, so without the fix in `EdgeHeaders`
  every request would reach the gateway claiming to be for `prod-qits-gateway:8080`. Redirects,
  cookie domains and absolute URLs are all built from that name.
- **Adds `X-Forwarded-For` / `-Host` / `-Proto`, only when absent.** The edge is not always the
  outermost hop: a TLS terminator in front of it is the only thing that can tell the truth about
  `https`, so overwriting would replace a true value with a false one. Consequently **nothing may
  make a trust decision on these three**; they are diagnostics and link generation.
- **Corrects the SPA cache header.** Every service serves its SPA with the Quarkus static default,
  `Cache-Control: public, immutable, max-age=86400`. That is right only where the name changes with
  the content, so `EdgeCacheControl` rewrites it to `no-cache` on every path whose filename is not
  content-hashed — above all each `index.html`, the mutable pointer naming the hashed bundles and so
  the file that decides which version of an application a returning browser runs. **Only that exact
  default is touched**: a header a handler chose is a decision, and a blanket rewrite would weaken
  this process' own `no-store` routes. qits-gateway did this and the edge did not when it replaced
  it, which is how a green, correctly deployed release could stay invisible for a day and then come
  right on its own — a cache reading as flakiness.
- **Serves `/main-navigation` on every vhost**, from the same snapshots, and writes every origin in
  the SHORT form for the default environment — `https://example.com` and `https://ci.example.com`,
  whichever spelling the request itself used. Other environments keep their label, and a one-label
  apex (`dev.localhost:8080`) keeps its environment because `localhost` alone names them all. The
  document is `environment`, `origin` and `slots`, and nothing else. `origin` is the door, which
  names the environment and serves nothing else: every slot of the closed
  vocabulary (empty ones included, so a shell iterates the document rather than a copy of the
  vocabulary), and one entry per placement with the application, the label, the host, that host's
  origin, the application's primary route `path` and the position. `host` is null until that
  application is flipped, and `path` is present either way — it is what a shell renders an unflipped
  application under, so nothing leaves the sidebar during a rollout. There is no flat list and no
  synthesized `Home`: the environment's own door is qits-projects' `system` entry, a deployment fact
  like every other entry here.
- **Answers `/q/health/{live,ready}` itself**, never proxied, whatever the `Host` says. Readiness
  reports the resolved environment → upstream map as health data and stays DOWN until the
  deployment projection has reached qits-events' confirmed head.

### The one known gap: `Host` on a WebSocket handshake

The upgrade path rebuilds the handshake with the client's own `Host` dropped — deliberately kept
from `vertx-http-proxy`'s behaviour when `EdgeWebSocketUpgrade` replaced it, so upstreams see no
change. An upstream therefore reads a socket's original host name from **`X-Forwarded-Host`**,
which the edge does set on that path, and not from `Host`. It costs nothing today, because a
handshake's `Host` is a protocol formality rather than something an environment gateway routes on —
but if that ever changes, this is where to look.

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
| `qits.edge.auth.enforce-on-apps` | `QITS_EDGE_AUTH_ENFORCE_ON_APPS` | `true` | Service vhosts require a valid idp token |
| `qits.edge.auth.anonymous-read-apps` | `QITS_EDGE_AUTH_ANONYMOUS_READ_APPS` | — | App labels whose `GET` and `HEAD` are open; every other method on them still needs a token |
| `qits.edge.auth.audience-pattern` | `QITS_EDGE_AUTH_AUDIENCE_PATTERN` | `{env}-qits-artifacts` | The audience a token must name; `{env}` is resolved per request, a value without it is a literal |
| `qits.edge.auth.clock-skew-seconds` | `QITS_EDGE_AUTH_CLOCK_SKEW_SECONDS` | `30` | How far this clock and idp's may disagree about `exp` |
| `qits.edge.auth.jwks-refresh-cooldown-ms` | `QITS_EDGE_AUTH_JWKS_REFRESH_COOLDOWN_MS` | `5000` | Shortest gap between two JWKS fetches |
| `qits.edge.auth.basic-cache-ttl-ms` | `QITS_EDGE_AUTH_BASIC_CACHE_TTL_MS` | `300000` | Ceiling on how long a validated HTTP Basic credential is believed; the minted token's own life is the other half |
| `qits.edge.auth.basic-cache-size` | `QITS_EDGE_AUTH_BASIC_CACHE_SIZE` | `1024` | The most validated credentials held at once, least-recently-used |
| `qits.edge.auth.idp-retry-window-ms` | `QITS_EDGE_AUTH_IDP_RETRY_WINDOW_MS` | `45000` | How long a redeploying idp is waited out before the edge answers an error |
| `qits.edge.auth.idp-call-timeout-ms` | `QITS_EDGE_AUTH_IDP_CALL_TIMEOUT_MS` | `5000` | How long ONE call to idp may take, connection included — **what makes an answer certain** |
| `qits.edge.sessions.enabled` | `QITS_EDGE_SESSIONS_ENABLED` | `false` | Whether a browser needs a session on a service vhost — **the rollout flag** |
| `qits.edge.sessions.cookie-name` | `QITS_EDGE_SESSIONS_COOKIE_NAME` | `qits-session` | The cookie idp sets and this process reads |
| `qits.edge.sessions.canonical-origin` | `QITS_EDGE_SESSIONS_CANONICAL_ORIGIN` | `http://localhost:8080` | The environment **door**: what the default environment's names are derived from, and the login origin's fallback |
| `qits.edge.sessions.login-path` | `QITS_EDGE_SESSIONS_LOGIN_PATH` | `/idp/login` | Where a navigation with no session is sent — on the host of whichever deployment owns this route |
| `qits.edge.sessions.browser-hosts` | `QITS_EDGE_SESSIONS_BROWSER_HOSTS` | `localhost:8080` | Browser return authorities. An entry may be `*.<authority>`, which matches exactly ONE extra label — `*.dev.example.com` covers every service's own name and refuses `a.b.dev.example.com` |
| `qits.edge.sessions.anonymous-prefixes` | `QITS_EDGE_SESSIONS_ANONYMOUS_PREFIXES` | `/idp/` | Path prefixes served with no credential at all — on the owning service's own host, nowhere else |
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
`Host` name already resolved to — a configured entry or a published host — so it reaches service
vhosts only, and a label nothing claims is still a `404` rather than an open door.

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

## Browser sessions — on every service's own name

Machine credentials are the section above. A **person** carries neither a token nor a client secret,
so every service's own name gates a `qits-session` cookie instead. The bootstrap enables
`qits.edge.sessions.enabled` only after it has seeded the edge's introspection credential and the
IdP's browser SSO settings. The environment vhost is the door and serves nothing, so it gates
nothing.

**The login page lives on idp's own name, not on the door.** The origin is read off the deployment
projection per request: whoever owns `login-path` and publishes a host. `canonical-origin` cannot
follow it, because it is also the authority every default-environment name is derived from — so it
stays the door, and is the fallback while no deployment has published a host for the login path.
idp is a platform service deployed once, so an environment that owns no route for the path asks the
default environment before falling back.

### A service's own name, gated per request

`ci.dev.example.com` is one service and one name, and both a person and a machine type it. So the
gate on a **service vhost** — a published host, or a configured application vhost, which are now the
same thing — is decided per request rather than per plane. Every inbound `X-Qits-*` header is
dropped first, whatever the outcome: the reserved prefix is what a hop *asserts*.

1. a `Bearer` or `Basic` **machine credential** takes the machine path above, checked in full, and
   is proxied with no identity headers and with the browser cookie removed — a machine's identity is
   in its token;
2. otherwise, with the session gate on, a `qits-session` **cookie** is introspected at idp and
   becomes `X-Qits-User`, `X-Qits-User-Id` and `X-Qits-Roles` (comma-separated — a role never holds
   a comma). **The cookie is kept**: the service behind the name is an ordinary qits service and the
   browser's next request carries it too;
3. a caller holding neither still gets that app's **anonymous reads** (`anonymous-read-apps`), so
   `docker pull`, `npm install` and `git clone` work on exactly the names they work on today — and,
   on the name that **owns** them, the `anonymous-prefixes`, which is what serves `/idp/login` on
   `idp.dev.example.com`. Every other host refuses that prefix: it opens one service, not a path on
   every name;
4. anything left is refused in the shape the caller can act on: a navigation gets the login
   redirect, and everything else gets the `WWW-Authenticate` challenge — `docker` on `/v2/` above
   all, which acts on the realm and would give up without it.

With the session gate off, and for a configured application with no published host, this is the gate
exactly as it stood.

The cookie is removed from every request that did **not** use it, which is what keeps a
parent-domain session out of registry, mirror and git-host traffic. Unrelated cookies and all
machine credentials pass through unchanged.

**The return target is two allow-lists, not a reflected URL.** The edge accepts only a configured
browser authority and a single-slash path; the IdP validates the same authority once more before
the SPA navigates. `//host`, `/\host`, an absolute URL, a control character, or an unlisted host
lands at the apex front door.

An entry may be `*.<authority>`, and it matches **exactly one extra label** in front of that
authority, port included. Every service of an environment is its own browser host now, so listing
them would be a second copy of the deployment's application list; the wildcard is one line that
follows it. It is not a suffix check: `a.b.dev.example.com` is a different site to a browser and is
refused.

**A dead cookie still reaches `/idp/`** on idp's own host. The prefix answers every caller with no
usable credential, not only the ones carrying none — otherwise a browser holding a revoked session
would be redirected to a login page it is refused at, forever. This is the one place the order
differs from the plan's, and the reason is that loop. The refused cookie does not travel: the
request is not using it.

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
