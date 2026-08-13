# qits-platform-edge as a GraalVM-native container IMAGE — the same shape as qits-gateway's: a
# Mandrel builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces target/qits-platform-edge
# locally with no container involved. What this Dockerfile adds is the image around it — and,
# incidentally, a way for a machine with no GraalVM to still get a binary, since the builder stage
# brings its own native-image.
#
# Native matters more here than anywhere else on the platform. This process binds the host's only
# public port: nothing is reachable until it is up, and every restart of it is an outage of
# everything. Native gives it a ~50ms start and a footprint in tens of megabytes, so restarting the
# front door is a non-event.
#
# There is NO client stage, unlike qits-gateway's: this service serves no page, so there is no
# bundle to build beforehand and no build context to prepare. Build from the repo root:
#
#     docker build -t qits/platform-edge:latest -f docker/Dockerfile .
#
# --- stage `build`: native-compile ---------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8082/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the first build
# fetches the image from upstream and caches it, every later build is served from local disk. The
# tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves
# it.
FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a file list — see .dockerignore for why that is affordable.
COPY . .

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests, deliberately: the suite has already run in the pipeline step that reached this build,
# and running it here would fork a second Quarkus per class inside a builder image for no new
# information. A red suite must stop the pipeline, not the image.
RUN ./mvnw -B -ntp package -Dnative -DskipTests -Dquarkus.native.native-image-xmx=4g

# --- runtime -------------------------------------------------------------------------------------
# `localhost:8082/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8082/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-platform-edge` (no -runner suffix), so the
# process is identifiable in `ps` / `docker top`.
COPY --from=build --chown=1001:root /src/target/qits-platform-edge /work/qits-platform-edge
RUN chmod +x /work/qits-platform-edge

EXPOSE 8080
USER 1001

# The environment list is configuration: pass it as env (QITS_EDGE_ENVIRONMENTS=prod,dev and
# QITS_EDGE_DEFAULT_ENVIRONMENT=prod) or mount an application.properties next to the binary. The
# deployer must publish the port — `-p 8080:8080` — because this is the one container on the host
# that is reached from outside docker. See README.md.
#
# networkaddress.cache.negative.ttl=0 because under swarm a gateway's DNS name appears only once its
# task passes its healthcheck, and the JDK caches a FAILED lookup for 10s by default — so the edge
# would keep answering 502 for up to ten seconds after a gateway became resolvable. It has to be
# this JDK property: quarkus.vertx.resolver.cache-negative-time-to-live is inert while
# quarkus.vertx.use-async-dns stays false (the default), which sets vertx.disableDnsResolver=true
# and puts every lookup through the JDK's blocking resolver. GraalVM native reads the property at
# runtime, so the entrypoint is where it belongs.
ENTRYPOINT ["/work/qits-platform-edge", "-Dquarkus.http.host=0.0.0.0", "-Dnetworkaddress.cache.negative.ttl=0"]
