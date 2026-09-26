# qits-edge-platform-service

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
- **`navigation`** — placements, at most one per `(slot, label)` pair:
  `{"slot":"services.details","label":"CI","position":2}`. The slot vocabulary is closed and lives
  in `EdgeRoutes.SLOTS`. **One application fills several entries of one slot** — qits-workspaces
  hangs `Workspaces` and `Editor` under `project.detail` from one container — so the slot alone was
  never the claim; the same pair twice is one row asked for twice and is refused. A repeated
  `position` is not: the document breaks that tie by label, whether the two entries belong to one
  application or to two.

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
that is an environment name, an unknown slot, the same `(slot, label)` placement twice, or a host
that is also a configured
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

A **second** consumer, `edge-project-sans`, rebuilds the project-slug projection, and
`ProjectSansBootstrap` is deliberately not folded into that barrier: holding the whole edge down
would refuse every request over a projection that decides two spellings out of four. It catches up
in the background and, once it reaches the head, requests one reconcile — which closes the boot race
where the certificate manager's own startup reconcile runs before any slug is known.

That set is a **routing** input as well as a certificate one, though, so being outside the barrier
is not being outside the problem. While it is behind, a name whose reading could still change once
more slugs arrive — a would-be project door, a would-be four-label name, the short spelling of
either — is answered `503 Retry-After: 1` instead of a 404 that would name the wrong host. Nothing
else is: a configured vhost, a published host, the apex and an environment's own door never consult
the set, and `edge_project` is persisted, so a restarted edge whose rows survived serves normally
throughout. The window is a genuinely behind projection — a wiped database, or a first boot.

**This is the only proxy tier there is.** There was a second one — `qits-gateway`, one per
environment, demultiplexing services by PATH inside its tier — and it is gone: every service is
reached on a name of its own now, so a per-environment hop would have been a second address for
something that already has one. What that repository knew came here with it, and the two places it
shows are `EdgeCacheControl` (a port of its class, restored after the retirement dropped it) and
the surefire split in `pom.xml` (its workaround for a WebSocket-after-restart harness bug).
Historical mentions of it below are exactly that; nothing on the platform runs one.

```
                          ┌────────────────────────────────────────┐
  client ──:8080──▶       │ qits-platform-edge  (the only          │
  Host: registry.dev.…    │ published port on the host, and the    │
                          │ only proxy tier)                       │
                          │   Host name → service · idp · /q       │
                          └──┬──────────────┬──────────────┬───────┘
                             │ docker networks, nothing published
       dev's registry        │  prod's registry            │  dev's ci, published
        ┌────────────────────▼┐  ┌──────────▼─────────┐  ┌─▼──────────────────┐
        │ dev-qits-artifacts  │  │ prod-qits-artifacts│  │ dev-qits-ci        │
        │  :8080              │  │  :8080             │  │  :8080             │
        └─────────────────────┘  └────────────────────┘  └────────────────────┘
         configured, qits.edge.apps                       deployment projection
```

## The routing model

A `Host` name selects an environment, and optionally a project and an application inside it:

| Host                        | Goes to                                             |
| --------------------------- | --------------------------------------------------- |
| `prod.example.com`          | the `prod` **door** — `$env.$domain`, and it serves nothing |
| `registry.prod.example.com` | `prod`'s `registry` upstream — `$app.$env.$domain`  |
| `registry.dev.example.com`  | `dev`'s `registry` upstream — same entry, other tier |
| `acme.prod.example.com`     | the `acme` **project's door** in `prod` — `$project.$env.$domain` |
| `editor.acme.prod.example.com` | the editor, for `acme`, in `prod` — `$app.$project.$env.$domain` |
| `example.com`               | the **default** environment's door — the apex, and the one name with no environment label |
| `localhost`, `127.0.0.1`, `[::1]` | the **default** door — one label, or an address |
| no `Host` at all            | the **default** door                                |
| `registry.dev.localhost`, `githost.dev.internal` | dev's registry and git host — a **machine name**, outside the domain |
| `mirror.localhost`          | the mirror in the **default** environment — a machine name with no env label |
| `ci.dev.localhost`, `ci` being published rather than configured | **404** — a machine name reaches configured applications only |
| `ci.dev.example.com`, published by a deployment | `dev`'s ci service — its SPA at `/` and every route it owns |
| `mirror.dev.example.com`, `mirror` unconfigured and unpublished | **404** — see below |
| `registry.example.com`, `acme.example.com`, `editor.acme.example.com`, `staging.example.com` | **404** naming the explicit spelling — see below |

Only the **first three labels** are read, which is why the domain itself is never configured: it may
be one label, two or three, and the edge does not have to know. An environment name at position 1
wins over everything, so `staging.prod.example.com` is *application `staging` in environment
`prod`* — an application may be called anything, whereas a domain whose first label happens to be an
environment name is a coincidence nobody arranges. At position 0 a configured **service** beats a
project of the same name; the platform refuses both collisions on its own side rather than relying
on either tie-break.

**The environment label is not optional.** It used to be, for the default environment: the apex is
that environment's door, so `ci.example.com` was its ci service. The **project tier** ended that.
With a project label in the middle, `editor.acme.example.com` and `editor.acme.prod.example.com`
would be one place whose middle label is read as a project in one spelling and an environment in the
other, and `acme.example.com` would be a project door or an application vhost depending on which set
a label happened to be in. So a name of more than one label that does not state its environment is
**404, with the spelling that works in the body**, and the miss is logged at INFO once per request so
the callers that have not moved can be found. There is no redirect, deliberately: a short name is a
bookmark, a link or a hard-coded string, and a 302 would keep every one of them working and
invisible.

**The apex is a door like the others, and the one that can compose nothing.** `example.com` is the
name that IS the stated domain, so it is recognised positionally like every other. It carries no
project label, and every application address does — so its `GET /` redirects nowhere and it answers
the grammar instead, unless `canonical-origin` names a project's door (`https://qits.example.com`),
in which case that project's tier is what the apex composes on.

**A name outside the stated domain is a MACHINE NAME, and it is read rather than refused.** The
platform's own machine vhosts are docker network aliases of this container — qits-bootstrap-cli's
`ComposeTemplate` renders `registry.<env>.localhost`, `mirror.<env>.localhost`,
`githost.<env>.localhost` and `githost.<env>.internal` — and they are deliberately not under the
public domain: every image reference, every maven resolution and every clone from a container goes
through one of them. Such a name is read as `<app>[.<env>].<machine-suffix>`, with the leftmost
label joined against `qits.edge.apps` exactly as an app label under the domain is, an optional
environment label behind it, and a suffix nobody enumerates — `localhost`, `internal`, whatever else
this container is aliased as. A name outside the domain that was answered as a door instead took
docker pulls, dependency resolution and container git down together, which is the incident this
reading exists for.

It is a **separate, explicit** reading and not a revival of the tie-breaks the positional grammar
retired: under the stated domain the project label stays mandatory and `registry.dev.<domain>` is
still a 404. A machine name carries **no project** — there is no project tier outside the domain and
there never can be — so it composes no application address, a leading `landing` label is a 404
rather than a door, and a leftmost label no *configured* application claims is a 404 that the
deployment projection is never asked about. A machine name of a single label (`localhost`) carries
no app label at all and is a door, as an address literal and a missing `Host` are.

**A project's slug is a label the edge learns from the event stream.** `EdgeProjects` projects
qits-projects' `ProjectCreated`/`ProjectDeleted` into the set that both certificate names and these
two readings are built from, so a project created on Tuesday is routable on Tuesday. A slug nobody
has created is just another label, and its name states no environment.

**An app-shaped name is 404 rather than a fall-through, and it is deliberate.** A first label in
front of a *known* environment — or in front of a known project in front of one — was aimed at a
service, and services are the names this edge authenticates, so falling it through to anything else
would hand exactly those requests to a hop that does not check them. An unconfigured, unpublished
app label is therefore a **404**, and the answer names the environment and project it did read.
`SessionlessWallIT` and the door story are what pin it.

**A project's own name is a door too.** `acme.dev.example.com` answers `GET /` with the same
redirect the environment door gives — to qits-projects' host — and 404s every other path, naming
`<app>.acme.dev.example.com`. The one thing decided outside `HostEnvironments` is which of the two a
single label in front of an environment is: a **published service claims it first**, and the door is
what is left, because a slug and a published browser host are the same shape and only the deployment
projection knows the second set.

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
  every request would reach its service claiming to be for `prod-qits-projects:8080`. Redirects,
  cookie domains and absolute URLs are all built from that name. `VhostRoutingIT` asserts it from
  the receiving end, on two services at once.
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
  right on its own — a cache reading as flakiness. `SpaFreshnessIT` is the story: all three shapes
  served by a real upstream, through a launched edge, with the two that are corrected and the two
  that are not side by side.
- **Serves `/main-navigation` on every vhost**, from the same snapshots, and writes every origin
  in the grammar the router reads — `<app>[.<env>].<project>.<domain>`, spelled forwards.
  `https://dev.acme.example.com` and `https://ci.dev.acme.example.com` for a project with
  environments, `https://gizmo.example.com` and `https://ci.gizmo.example.com` for one without,
  whichever of its names the request itself used. A one-label domain (`dev.acme.localhost:8080`) is
  the same rule rather than an exception to one. The document is `environment`, `origin`,
  `projectOrigin`, `slots` and `applications`, and nothing else. `origin` is the innermost door —
  the environment's inside a project that has them, the project's own otherwise;
  `projectOrigin` is the authority a client puts `<app>.` in front of to reach that application for
  this project (ONE label: the project is in the authority already), and it exists because the
  client side derived that name itself and shipped two domain-derivation bugs doing it. On a name
  inside no project — the apex, an address literal — an entry's origin is null, because there is no
  application address to compose. Then every
  slot of the closed
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
handshake's `Host` is a protocol formality rather than something a service routes on one hop
further in — but if that ever changes, this is where to look.

`StreamingPassthroughIT` asserts the gap **both ways round** — `X-Forwarded-Host` is the vhost and
`Host` is not — so it stays a known gap rather than quietly becoming a surprise. A story that
pinned only the half that works is how it would stop being documented.

## Configuration

Every key is overridable by environment variable, so a deployment declares the whole surface without
a file.

| Key | Env | Default | What it is |
| --- | --- | --- | --- |
| `qits.edge.environments` | `QITS_EDGE_ENVIRONMENTS` | `prod` | The routable environment names, comma separated |
| `qits.edge.default-environment` | `QITS_EDGE_DEFAULT_ENVIRONMENT` | `prod` | Where the apex and every unmatched host go. **Must be in the list** |
| `qits.edge.apps.<app>.host-pattern` | `QITS_EDGE_APPS_<APP>_HOST_PATTERN` | — (`mirror`: `{env}-qits-platform-mirror`) | **Required per app.** `{env}` is the only placeholder, and every application names it. The one shipped entry is `mirror`, whose value is the platform's own pull-through cache rather than a decision |
| `qits.edge.apps.<app>.port` | `QITS_EDGE_APPS_<APP>_PORT` | `8080` | The port that application listens on |
| `qits.edge.apps.<app>.hosts.<env>` | `QITS_EDGE_APPS_<APP>_HOSTS_<ENV>` | — | Per-environment override, `host` or `host:port` |
| `qits.edge.apps.<app>.audience-pattern` | `QITS_EDGE_APPS_<APP>_AUDIENCE_PATTERN` | `qits-platform` | The audience this app's own vhost accepts, next to the platform audience. An app such as githost or the editor names its own resource pattern (`{env}-qits-githost`); an app with none opens with roles alone |
| `qits.edge.projection.catchup.required` | `QITS_EDGE_PROJECTION_CATCHUP_REQUIRED` | `true` | Requires a complete deployment-history rebuild before the edge is ready; turn off only in an intentionally offline test/dev setup |
| `qits.edge.projection.catchup.retry` | `QITS_EDGE_PROJECTION_CATCHUP_RETRY` | `PT1S` | Delay before retrying an incomplete, failed, or unavailable deployment-history read |
| `qits.idp.url` | `QITS_RESOURCE_IDP_URL`, then `QITS_IDP_URL` | `http://qits-platform-idp:8080/idp` | The issuer. `/jwks` and `/token` are derived from it, never configured. `QITS_RESOURCE_IDP_URL` is the `idp:client` resource a deployment may declare (service-client-identity-plan.md, C4/D6/D7); the older name keeps working unchanged until it is declared |
| `qits.edge.auth.enforce-on-apps` | `QITS_EDGE_AUTH_ENFORCE_ON_APPS` | `true` | Service vhosts require a valid idp token |
| `qits.edge.auth.anonymous-read-apps` | `QITS_EDGE_AUTH_ANONYMOUS_READ_APPS` | — | App labels whose `GET` and `HEAD` are open; every other method on them still needs a token |
| `qits.edge.auth.audience-pattern` | `QITS_EDGE_AUTH_AUDIENCE_PATTERN` | `qits-platform` | The audience a token must name; `{env}` is resolved per request, a value without it is a literal. The shipped default is the same literal as the platform audience below, so a freshly configured vhost opens with roles alone; an explicitly configured pattern (today's `{env}-qits-artifacts`, or an app's own) keeps working unchanged |
| `qits.edge.auth.platform-audience` | `QITS_EDGE_AUTH_PLATFORM_AUDIENCE` | `qits-platform` | One audience that opens every gated vhost, next to the vhost's own. No `{env}`: roles are the permission. Empty switches it off |
| `qits.edge.auth.clock-skew-seconds` | `QITS_EDGE_AUTH_CLOCK_SKEW_SECONDS` | `30` | How far this clock and idp's may disagree about `exp` |
| `qits.edge.auth.jwks-refresh-cooldown-ms` | `QITS_EDGE_AUTH_JWKS_REFRESH_COOLDOWN_MS` | `5000` | Shortest gap between two JWKS fetches |
| `qits.edge.auth.basic-cache-ttl-ms` | `QITS_EDGE_AUTH_BASIC_CACHE_TTL_MS` | `300000` | Ceiling on how long a validated HTTP Basic credential is believed; the minted token's own life is the other half |
| `qits.edge.auth.basic-cache-size` | `QITS_EDGE_AUTH_BASIC_CACHE_SIZE` | `1024` | The most validated credentials held at once, least-recently-used |
| `qits.edge.auth.idp-retry-window-ms` | `QITS_EDGE_AUTH_IDP_RETRY_WINDOW_MS` | `45000` | How long a redeploying idp is waited out before the edge answers an error |
| `qits.edge.auth.idp-call-timeout-ms` | `QITS_EDGE_AUTH_IDP_CALL_TIMEOUT_MS` | `5000` | How long ONE call to idp may take, connection included — **what makes an answer certain** |
| `qits.edge.sessions.enabled` | `QITS_EDGE_SESSIONS_ENABLED` | `false` | Whether a browser needs a session on a service vhost — **the rollout flag** |
| `qits.edge.sessions.cookie-name` | `QITS_EDGE_SESSIONS_COOKIE_NAME` | `qits-session` | The cookie idp sets and this process reads |
| `qits.edge.sessions.canonical-origin` | `QITS_EDGE_SESSIONS_CANONICAL_ORIGIN` | `http://localhost:8080` | A **door**, and the one name a deployment always states: what a name inside no project falls back to (read by the same right-to-left grammar, so naming a project's door is what lets the apex compose application names), the stated domain while ACME is off, the login origin's fallback, and — with `qits.edge.acme.domain` — what the browser return authorities are **derived** from, since there is no list to configure any more |
| `qits.edge.sessions.login-path` | `QITS_EDGE_SESSIONS_LOGIN_PATH` | `/idp/login` | Where a navigation with no session is sent — on the host of whichever deployment owns this route |
| `qits.edge.sessions.anonymous-prefixes` | `QITS_EDGE_SESSIONS_ANONYMOUS_PREFIXES` | `/idp/` | Path prefixes served with no credential at all — on the owning service's own host, nowhere else |
| `qits.edge.sessions.cache-ttl-ms` | `QITS_EDGE_SESSIONS_CACHE_TTL_MS` | `30000` | How long an introspected session is believed — and how long a logout lingers |
| `qits.edge.sessions.cache-size` | `QITS_EDGE_SESSIONS_CACHE_SIZE` | `1024` | The most sessions held at once, least-recently-used |
| `qits.edge.sessions.stale-grace-ms` | `QITS_EDGE_SESSIONS_STALE_GRACE_MS` | `60000` | How long a cached session outlives an **unreachable** idp |
| `qits.edge.sessions.client-id` | `QITS_RESOURCE_IDP_CLIENT_ID`, then `QITS_EDGE_SESSIONS_CLIENT_ID` | — | The edge's own idp client, for introspection. Today the bootstrap seeds `{env}-qits-edge` under the older name; `QITS_RESOURCE_IDP_CLIENT_ID` is the `idp:client` resource a deployment may declare instead (service-client-identity-plan.md, C4/D6/D7), and it becomes `qits-platform-edge` at the edge's own cutover (D2) |
| `qits.edge.sessions.client-secret` | `QITS_RESOURCE_IDP_CLIENT_SECRET`, then `QITS_EDGE_SESSIONS_CLIENT_SECRET` | — | Its secret, same fallback. Neither pair set is still "no credential", which fails startup exactly as before if the gate is on |
| `qits.observability.url` | `QITS_OBSERVABILITY_URL` | `http://qits-observability:8080` | Where telemetry goes; the OTLP endpoint is derived from it |

Five things fail **at startup** rather than per request, deliberately: an environment or application
name that could not be a DNS label, a default that is not in the list, an application that shares an
environment's name (the tie-break would make that environment unreachable), and the session gate
turned on with no client id and secret to introspect with. All would otherwise be a 502, a 404, a
connection error or a 401 on every request, with nothing to read.

The applications map is shipped with **one** entry, `mirror`, and every other application entry is
the on-switch it always was: a name only reaches an environment's service when a deployment names
it. A deployment declares those without a file:

```
QITS_EDGE_APPS_REGISTRY_HOST_PATTERN={env}-qits-artifacts
QITS_EDGE_APPS_GITHOST_HOST_PATTERN={env}-qits-githost
```

`{env}` is what keeps a tier's services separate, and **every** application entry names it now.
There used to be a second kind that did not: a platform service was one process for the whole
estate, addressed bare, so its entry carried no placeholder. That plane is deleted — the mirror is
an ordinary application in the one tier — and the placeholder is no longer something an entry can
be an exception to.

`mirror` is nonetheless the entry this file ships, and the reason is revocability rather than
spelling. A map entry cannot be unset by a later config source, only overridden, so shipping one
costs the ability to revoke it — and there is nothing to revoke: the pull-through cache is the
platform's own, one per tier, and a tier running a different one would not be this platform. The
environment variable that used to carry it only respelled that in a second place. An environment's
own application stays out, because which tiers publish a machine vhost is a deployment's decision
and has to remain revocable.

`{env}` is the **edge's own** placeholder throughout this family, substituted per request in
`EdgeRouter.appUpstream` from the environment the Host name named. It is not Quarkus property
expansion: a `${QITS_ENVIRONMENT:dev}` here would be resolved once at startup and would name the
container's own tier rather than the requested one.

`qits.edge.apps.<app>.hosts.<env>` exists for the two topologies a pattern cannot describe — a
developer running one service on `localhost:8000`, and this repository's own tests, where the
upstreams are stand-in servers on ephemeral ports (`StubGateways` for the suites, `StoryUpstream`
for the userflow catalogue). Prefer the pattern: an override is a second place an address is
written, and a stale one sends a whole tier's traffic to the wrong process. Note also that a
`@ConfigMapping` map key cannot be **unset** by a later config source, only overridden, which is why
none is shipped in `application.properties`.

There is **no** `qits.edge.upstream-host-pattern`, `qits.edge.upstream-port` or
`qits.edge.upstream-hosts`. Those were the per-environment gateway's address, and there is no
per-environment gateway: an upstream is an application entry or a host a deployment published, and
nothing else.

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
`exp` within the skew, and the demanded audience or the platform audience in `aud`.

**The audience is derived per request**, from `qits.edge.auth.audience-pattern` — or an app's own
`qits.edge.apps.<app>.audience-pattern`, when it names one — with `{env}` filled in from the
environment the vhost named, the same placeholder as the host patterns above. idp's audience values
are env-prefixed, so a **configured** pattern is what keeps the tiers apart: a token minted for
`registry.dev.…` does not open `registry.prod.…`, from one entry. A pattern with no placeholder is a
literal audience, for a single-audience deployment.

**One audience opens every vhost.** A token whose `aud` names `qits.edge.auth.platform-audience`
(`qits-platform`) passes on every gated vhost and on every path: Bearer, Git's
`Basic oauth2:<token>`, and Basic client credentials. A person's command-line tool gets this token.
The audience has no `{env}`, on purpose: it only says the token is for this platform, and the
token's roles are what each service checks. An empty value switches the rule off.

**The shipped default of `audience-pattern` is the same literal, `qits-platform`** — both the
global key and an app entry's own — so a vhost nobody has configured a tier-scoped pattern for opens
with roles alone (service-client-identity-plan.md, C4). This is additive: today's live extras still
set `QITS_EDGE_AUTH_AUDIENCE_PATTERN={env}-qits-artifacts` and the githost and editor entries' own
`*_AUDIENCE_PATTERN`, and an explicitly configured value always wins over the default — nothing about
a gated vhost changes until that entry is deleted.

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
follow it, because it is also what a name inside no project falls back to — so it stays a door, and
is the fallback while no deployment has published a host for the login path. idp is a platform
service deployed once, so an environment that owns no route for the path asks the default
environment before falling back.

**The return host is the name the person was on**, when it is a name under the stated domain. That
check is what stops the platform's own login becoming a redirector for somebody else's site, and it
is **derived rather than configured**: the domain itself, plus one wildcard in front of it admitting
up to three labels — `<app>[.<env>].<project>.<domain>` is the deepest the grammar goes. One
wildcard therefore covers every project and every environment, and there is no key to fall behind
the project set. The security property is the **domain anchor**, not the label count: every name it
admits is under the domain this deployment states, which is its own. The depth is bounded at the
grammar's own depth anyway, so a return host is still a name the grammar could have produced and a
suffix test's `<domain>.evil.example` is refused. A name outside all of that falls back to the door,
which is a person landing somewhere they did not ask for; that is the symptom to look for if the
**idp's** own `QITS_IDP_BROWSER_SSO_BROWSER_HOSTS` has not learnt the same shape, because it
validates the same value one hop later.

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
whose names are **derived**, in four tiers, because a wildcard covers exactly one label:

| tier | shape | reaches |
| --- | --- | --- |
| apex | `wohlben.eu`, `*.wohlben.eu` | `idp.wohlben.eu`, and nothing under it |
| environment | `*.<env>.<domain>` | `ci.dev.wohlben.eu` |
| project | `*.<slug>.<domain>` | `editor.acme.wohlben.eu` |
| project × environment | `*.<slug>.<env>.<domain>` | `editor.acme.dev.wohlben.eu` |

The third tier is now a name that **resolves and verifies and is not served**: `editor.acme.wohlben.eu`
states no environment, so the routing model above answers it 404 with the explicit spelling. It stays
on the certificate deliberately — the TLS handshake happens before that answer, and a name whose
refusal arrives as a certificate error is a browser page nobody can read. It costs one SAN per
project against the ceiling below.

**The project tiers are fed by events, not by configuration.** qits-projects publishes
`ProjectCreated` and `ProjectDeleted`; `ProjectLifecycleSubscriber` projects them into `edge_project`
(a durable, replay-from-epoch consumer like the routing one, tombstoned so a late create cannot
resurrect a deleted project) and `EdgeProjects.slugs()` is what the name set is built from. A
project created on Tuesday is on the certificate on Tuesday — which is what retired the older
arrangement, where the editor host reached the certificate only if somebody remembered to add it to
a bootstrap key.

A create **requests** a reconcile rather than performing one: the request is debounced by
`qits.edge.acme.reconcile-debounce` (30s), because epoch replay delivers every `ProjectCreated` ever
published in one burst and an order per frame would spend Let's Encrypt's five duplicate
certificates a week within seconds. A **delete orders nothing at all** — every name on the installed
certificate still answers, so the surplus wildcard ages out at the next renewal.

**`QITS_EDGE_ACME_ADDITIONAL_NAMES` remains, for names no tier describes:**

```
QITS_EDGE_ACME_ADDITIONAL_NAMES=editor.acme,editor.gizmo.wohlben.eu
```

Whole or relative to the domain; `editor.acme` is `editor.acme.<domain>`. It is a list of **names**
and knows nothing about projects. The bootstrap owns the list (`QITS_ACME_EXTRA_SANS`) and checks it
before the run, because the edge answers its challenges in this domain's own zone and one name
outside it fails the whole order. Unset orders exactly the derived set; a name added reaches the
certificate at the next order — the 12h reconcile, or a restart at once.

**The ceiling is 100 names**, which Let's Encrypt refuses past rather than trims. `CertificateNames`
caps first, by dropping whole **project** tiers — from the end of the slugs' sorted order, a project
wholly on or wholly off, until the set fits — and the manager logs an `ERROR` naming every dropped
slug on every reconcile while any are dropped. The arithmetic is `2 + E + P + P·E + A`, and the term
that grows on its own is `P·E`: one project on a three-environment edge is four names, so a
three-environment edge reaches the ceiling at its twenty-fourth project.

It used to **refuse** there instead, which read as the cheaper failure and was the more expensive
one: the refusal was raised while building the name set, which every reconcile does before it knows
whether it has anything to order, so one project past the ceiling stopped **expiry renewals** too
and the platform's only TLS terminator would have gone dark ninety days later with one log line per
reconcile to say so. The apex, `*.<domain>`, the environment tiers and the additional names are
never dropped — when they alone exceed 100 the derivation still throws, because that is a
deployment to correct rather than a projection that grew. The manager still warns at 90.

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

`.config/qits/deployments.yml` makes this a **platform** service, deployed into the platform
environment alone. There is no branch to deploy from: a deploy pulls the version coordinate the
release published.
One edge exists because there is one host port to bind, and it fronts every environment's services,
so it belongs to no tier. The target is also what makes the routing work: a platform service joins
every environment's per-application networks, so `<env>-qits-artifacts`, `<env>-qits-projects` and
every other pattern in `qits.edge.apps` resolves for every name in `qits.edge.environments`. An
environment service would reach only its own tier and answer 502 for the rest — which is exactly
the shape `UpstreamOutageIT`'s third beat drives on purpose.

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

**The integration half is a different posture entirely** and is not in `./mvnw verify` by default:
the story catalogue (`*IT`) runs the **packaged** artifact as a launched process, with the browser
gate ON, against stand-ins on real sockets. That is where the forward-auth claim stops being an
in-JVM assertion about a stub and becomes a fact about the bytes a service would have believed. See
[Userflows](#userflows--the-proofs-that-double-as-documentation) for the class list and the command.

## Userflows — the proofs that double as documentation

The catalogue lives in `service/src/test/java/eu/wohlben/qits/edge/*IT.java` and emits under
`service/target/userstories/`, one directory per story with `userflow.json`, `user-story.md` and a
self-contained `index.html`, plus a site index carrying the aggregate network of all of them.
The verify step of the release-request phase — `.config/qits/release.yml` declares the
`java-service` archetype and overrides no slot — publishes the bundle once per release-request fold
as `@userflows/qits-edge-platform-service` (the repository's own name, which is what
`userflows: true` means). That step **gates**, like every step of the composed pipeline: a red story
is a red verdict for the whole fold and holds the request.

**Nine stories, one launched artifact, one `StoryProfile`.** A `@TestProfile` is what failsafe
launches a process for, so a second profile would be a second front door; every story class names
that one, `ForwardAuthBootstrapIT` included.

| Class | Story | Edges |
| --- | --- | --- |
| `ForwardAuthBootstrapIT` | A forged identity never reaches the service, and the one idp vouched for does | 7 |
| `ForwardAuthBootstrapIT` | The door serves nothing, and a health probe is never gated | 6 |
| `VhostRoutingIT` | One name, one service: the Host header is the whole routing decision | 7 |
| `SessionlessWallIT` | The 401 wall: nothing a stranger sends leaves this process | 5 |
| `AnonymousReadIT` | An open read is still not a forged identity | 5 |
| `SpaFreshnessIT` | A released SPA is on screen at once: the edge unfreezes the document that names the bundles | 9 |
| `UpstreamOutageIT` | A service that is not there is an answer, and never a wait | 6 |
| `StreamingPassthroughIT` | An interactive terminal crosses the edge, and it crosses it stripped | 4 |
| `StreamingPassthroughIT` | A long answer arrives as it is produced, not when it is finished | 3 |

**Every diagram is observed, never narrated**, and every one of its ends is somebody else's
recording:

- **In**, tapped inside `EdgeClient` — the framework's shipped rest-assured tap cannot be used here
  for the reason that class exists at all: this service routes on `Host`, and rest-assured derives
  that header from the URL it was given. **The label carries the vhost**, because on this service
  the name IS the routing decision and two of `VhostRoutingIT`'s three requests are otherwise
  identical.
- **Out**, from `StoryUpstream` — a Vert.x recorder standing in for `qits-projects`, `qits-docs` and
  `qits-platform-mirror`, and qits-service-mock's `MockService` standing in for
  `qits-platform-idp`. The split is not stylistic: the applications need a chosen response header
  (the cache rewrite), a non-JSON body (an `index.html`), an answer written over time (the
  streaming claim), an outage arm and a WebSocket upgrade, and canned JSON can do none of the five.
  idp needs exactly canned JSON, which is what that library is for.

**What only a far-side recording can say** is most of this catalogue: the reserved `X-Qits-*`
namespace arriving empty on an anonymous read, the vouched-for identity arriving in its place on a
session, a person's cookie NOT arriving on a machine vhost, which of two services received an
identical request, and a request that arrived and was then dropped mid-answer.

**And what only a negative assertion can say** is the sharpest claim of all. `SessionlessWallIT`
ends with `assertNoEdgesFrom(qits-platform-edge)` — *nothing left this process* — with all four far
sides up and answering 200s in the stories either side of it. `assertEdgeCount` on every story is
what catches a refused request quietly starting to be forwarded.

**Running them:**

```bash
./mvnw -pl service -am -DskipITs=false verify \
  -Dit.test=ForwardAuthBootstrapIT,AnonymousReadIT,SessionlessWallIT,SpaFreshnessIT,StreamingPassthroughIT,UpstreamOutageIT,VhostRoutingIT
```

`skipITs` stays `true` in `service/pom.xml` — see the comment there — so the class list is named on
the command line and in `.config/qits/userflow-stories`, one class name per line, which the
archetype turns into the verify step's `-Dit.test`. **A new story class goes into that file in the
same commit that adds it**, or it is written and never run. Class order is topological:
every story carries `@UserflowRunsAfter(ForwardAuthBootstrapIT.class)` so the oldest class owns
whatever a boot produces, and `UserflowClassOrderer` is registered as junit's *secondary* orderer in
`src/test/resources/application.properties` — the one seam quarkus-junit permits, because it ships
its own `junit-platform.properties` and surefire hard-fails on a local override.

Two mechanics worth knowing before they cost an afternoon:

- `StoryUpstream`'s recording is **cumulative for the whole run**, with the framework's per-source
  cursor deciding which slice belongs to which story. So `onlyRequestTo(path)` can only mean
  "exactly one" on a path exactly one story drives, which is why several stories own a path of their
  own (`/projects/api/version`, `/projects/api/repositories`, `/projects/settings`).
- Each story uses a **cookie value of its own**. `EdgeSessions` caches a belief against a
  fingerprint of the cookie, so two stories sharing one would make the second's introspection edge
  depend on how long the first took.

## Relationship to qits-gateway

**There is no qits-gateway any more.** It was the per-environment proxy that demultiplexed services
by PATH inside one tier, and it was retired when every service gained a public name of its own — a
second hop would have been a second address, a second origin and a second cookie scope for something
that already had one. This process is the only proxy tier on the platform.

Two things it left behind are still load-bearing here and are worth knowing the provenance of:
`EdgeCacheControl` is a port of its class (and of the defect that appeared when the retirement
dropped it), and the second surefire execution in `pom.xml` is its workaround for a Quarkus harness
bug where a WebSocket upgrade only survives the first application start in a JVM. Anything else in
this file that names it is history.

### The Hetzner token, deployer-era (2026-08-31)

The deployer-managed edge container has no swarm secrets — qits-deployments'
extras grammar carries env, mounts, publishes, aliases and groups, and nothing
else — so `QITS_EDGE_ACME_HETZNER_TOKEN_FILE` pointing at
`/run/secrets/qits-dns-hetzner-token` names a file that exists only in the
bootstrap's seed stack. The live arrangement is the **by-value arm**:
`env.QITS_EDGE_ACME_HETZNER_TOKEN` in the edge's config-host extras, beside
`QITS_EDGE_SESSIONS_CLIENT_SECRET`, which already established that a secret may
ride there. The file arm wins when both are set, so the file entry must stay
deleted until a deployer `secrets[i]` facility exists — that facility is the
structural fix, and the day it lands this section inverts.
