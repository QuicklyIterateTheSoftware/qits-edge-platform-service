# qits-platform-edge

**The platform's L7 edge.** It binds the host's only public port, reads the `Host` name of every
request, and streams the request unchanged to whatever that name selects — an environment's gateway,
or one of that environment's services directly.

A small, stateless Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. No
database, no ORM, no REST layer, no client, no session.

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

- **No path knowledge in the routing decision.** The app label picks a whole upstream and the auth
  gate is per vhost — per vhost and *method*, where a deployment opened reads, but never per path;
  no prefix matching, no rewriting. `/token` is the single path this process claims, and only on an
  application vhost.
- **No login, no session, no browser flow.** The edge validates a machine credential; the browser
  half of authentication terminates at the environment gateway, which already does it.
- **No header stripping or injection beyond `X-Forwarded-*`.** `X-Qits-*` hygiene belongs to the
  component that *asserts* those headers — the environment gateway. A second implementation here
  would put one security contract in two repositories, and the copy that is not next to the
  injection is the copy that rots. `Authorization`, `Cookie` and every custom header pass through
  untouched.
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
  reports the resolved environment → upstream map as health data.

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
| `qits.observability.url` | `QITS_OBSERVABILITY_URL` | `http://qits-observability:8080` | Where telemetry goes; the OTLP endpoint is derived from it |

Four things fail **at startup** rather than per request, deliberately: an environment or application
name that could not be a DNS label, a default that is not in the list, and an application that
shares an environment's name (the tie-break would make that environment unreachable). All would
otherwise be a 502, a 404 or a connection error with nothing to read.

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

### TLS: the Let's Encrypt certificate slot

Four build-time keys, and **inert until a deployment supplies a keystore** — this repository supplies
none, so nothing is requested and nothing renews. The edge is no ACME client:
`quarkus.tls.lets-encrypt.enabled` adds an HTTP-01 challenge route,
`/.well-known/acme-challenge/:token`, to the main listener and the challenge-management endpoints to
the management interface. The host-side `quarkus tls lets-encrypt` CLI runs the protocol against
them, writes the PEMs where the TLS registry reads them, and the registry hot-reloads.

Two consequences are worth knowing before touching any of it:

- **The challenge-management endpoint is unauthenticated**, which is the only reason the management
  interface is on. On the main listener — the host's one published port — anyone on the internet
  could complete their own ACME order for the platform's domain. Port 9000 is published to loopback,
  or not at all.
- **Enabling the management interface moves `/q/health` onto it by default**, and the bootstrap and
  the deployer both poll `:8080/q/health/ready`. `quarkus.smallrye-health.management.enabled=false`
  keeps health where they look. `LetsEncryptConfigTest` pins the four keys, proves the challenge
  route beats the catch-all without reaching an upstream, and asserts health answers on the main port
  and 404s on the management one.

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

Everything end to end lives in **one** `@QuarkusTest` class, and it has to stay that way. A
WebSocket upgrade through `vertx-http-proxy` only survives the **first** Quarkus start in a JVM;
after a restart it silently degrades to a plain proxied GET, so the handshake fails with nothing
logged anywhere. It is a property of the test harness, not of this code (qits-gateway paid for
finding it, and works around it with a second surefire execution). A restart happens when a test
class needs a different configuration from the one before it — so one class, one test resource, one
start is the cheapest immunity. **Splitting `EdgeRoutingTest` is how the socket tests start failing
for no visible reason.**

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
