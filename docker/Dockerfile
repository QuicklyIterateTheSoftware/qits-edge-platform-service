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
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the first build
# fetches the image from upstream and caches it, every later build is served from local disk. The
# tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves
# it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a file list — see .dockerignore for why that is affordable.
COPY . .

# The edge now consumes qits-eventstream from the platform Maven repository. Mirror only that
# repository id so Maven permits the deployment-local HTTP endpoint without weakening its global
# external-HTTP blocker.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL

# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests, deliberately: the suite has already run in the pipeline step that reached this build,
# and running it here would fork a second Quarkus per class inside a builder image for no new
# information. A red suite must stop the pipeline, not the image.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    ./mvnw -B -ntp -s .qits-maven-settings.xml package -pl service -am -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts-service's first SBOM
# release (2026.902.45658): the service module skips maven-deploy — it ships as this image, not as a
# jar — and the plugin SKIPS deploy-skipping modules by default, with a green build and one INFO
# line. The sbom stage's COPY then fails on a file nothing wrote. The spelling matters too: the bare
# -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the plugin.

# --- sbom ----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image. The
# release step reaches this one with `--target sbom --output type=local` using the SAME args and
# secrets as the image build, so every layer above is a cache hit and the export costs seconds.
# `makeBom` rides on the mvnw invocation above rather than a second one, for the same cache reason —
# one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime -------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-platform-edge` (no -runner suffix), so the
# process is identifiable in `ps` / `docker top`.
COPY --from=build --chown=1001:root /src/service/target/qits-platform-edge /work/qits-platform-edge
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
