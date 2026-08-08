# qits-platform-edge

**The platform's L7 edge.** It binds the host's only public port, reads the `Host` name of every
request, and streams the request unchanged to that environment's gateway. Nothing else.

A small, stateless Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary**. No
database, no ORM, no REST layer, no client, no session, no authentication.

```
                          ┌────────────────────────────────────────┐
  client ──:8080──▶       │ qits-platform-edge  (the only          │
  Host: home.prod.…       │ published port on the host)            │
                          │   Host name → environment · /q         │
                          └──────┬───────────────────┬─────────────┘
                                 │ docker networks, nothing published
                   prod          │                   │        dev
                  ┌──────────────▼─────┐   ┌─────────▼──────────┐
                  │ prod-qits-gateway  │   │ dev-qits-gateway   │
                  │  :8080             │   │  :8080             │
                  └────────────────────┘   └────────────────────┘
```

## The routing model

Two host spellings, both ending at the same environment:

| Host                    | Environment              |
| ----------------------- | ------------------------ |
| `home.prod.example.com` | `prod` — `$app.$env.$domain` |
| `prod.example.com`      | `prod` — `$env.$domain`  |
| `example.com`           | the **default**          |
| `staging.example.com`   | the **default** (`staging` is not configured) |
| `localhost`, `127.0.0.1`, `[::1]` | the **default** |
| no `Host` at all        | the **default**          |

Only the **first two labels** are read, which is why the domain itself is never configured: it may
be one label, two or three, and the edge does not have to know. An environment name at position 1
wins over one at position 0, so `staging.prod.example.com` is *application `staging` in environment
`prod`* — an application may be called anything, whereas a domain whose first label happens to be an
environment name is a coincidence nobody arranges.

An unmatched name is **not an error**. Every one of them goes to the default environment, so a
mistyped URL reaches the platform's own page rather than a connection error.

The upstream is `<env>-qits-gateway:8080`, derived from the environment name. Nothing in a request
ever contributes a character to an address: a `Host` selects an *index into a fixed list*, which is
the whole SSRF guard.

## What it does not do — the non-goals, on purpose

- **No authentication.** It does not challenge, does not read a token, does not terminate a login.
  Authentication terminates at the environment gateway, which already does it and is already tested
  for it.
- **No header stripping or injection beyond `X-Forwarded-*`.** `X-Qits-*` hygiene belongs to the
  component that *asserts* those headers — the environment gateway. A second implementation here
  would put one security contract in two repositories, and the copy that is not next to the
  injection is the copy that rots. `Authorization`, `Cookie` and every custom header pass through
  untouched.
- **No path knowledge.** No route table beyond the environment list, no prefix matching, no
  rewriting. The environment gateway's routes are written against the paths clients type, and a
  prefix stripped here would break every one of them.
- **No UI, no SPA, no landing page, no `/api`.** The only path this process answers is `/q`.
- **No TLS.** A terminator in front of it is a deployment choice; see `X-Forwarded-Proto` below.

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

Two things fail **at startup** rather than per request, deliberately: an environment name that could
not be a DNS label, and a default that is not in the list. Both would otherwise be a 502 or a
connection error with nothing to read.

`qits.edge.upstream-hosts` exists for the two topologies the pattern cannot describe — a developer
running one gateway on `localhost:8000`, and this repository's own suite, where the gateways are
stub servers on ephemeral ports. Prefer the pattern: an override is a second place an environment's
address is written, and a stale one sends a whole tier's traffic to the wrong process. Note also
that a `@ConfigMapping` map key cannot be **unset** by a later config source, only overridden, which
is why none is shipped in `application.properties`.

### Deployment

The deployer must publish the port — this is the one container on the host reached from outside
docker:

```
-p 8080:8080
```

`.config/qits/deployments.yml` makes this an **environment-tiered** service, so it deploys from
`environment/<tier>`. One caveat is written down there and repeated here because it is the thing
that will bite: the edge reaches `<env>-qits-gateway` for *every* environment in its list, while an
environment service joins its own tier's networks. A single-environment platform is unaffected; a
second environment needs either `deployment_target: platform` with an explicit `branch:`, or the
edge joined to the other tiers by hand.

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

## Relationship to qits-gateway

They are the same idea one hop apart, and the split is deliberate: this one demultiplexes
**environments by host name**, the other demultiplexes **services by path** inside one environment.
Keep the Quarkus platform version and the JDK release in step with it.
