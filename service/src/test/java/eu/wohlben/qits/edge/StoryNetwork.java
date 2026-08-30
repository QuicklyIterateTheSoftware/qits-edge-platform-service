package eu.wohlben.qits.edge;

import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.util.List;

/**
 * <b>The whole capture wiring of this catalogue, in one call</b> — so a story class's
 * {@code @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>A front door is the one component whose diagram has to be readable from BOTH ends at once.
 * What arrives is a name, a path and a credential; what leaves is a different set of headers to a
 * service chosen by that name — and the interesting facts all live in the difference. A diagram
 * drawn from the near side alone would say the edge answered 200 and nothing about which service
 * did, or with whose identity, or whether anything left the process at all.
 *
 * <p>So there are two kinds of feed and neither of them is narrated:
 *
 * <ul>
 *   <li><b>The incoming half is tapped inside {@link EdgeClient}</b>, and it has to be. Every
 *       sibling service uses the framework's shipped {@code NetworkTaps.restAssured} filter; here
 *       that is impossible for the reason that class exists at all — this service routes on {@code
 *       Host}, rest-assured derives that header from the URL it was given, so nothing a story sends
 *       goes through it. Nothing is registered here for it: {@code EdgeClient} observes what it
 *       sends, at the one funnel every request passes.
 *   <li><b>The outgoing halves are the far side's own recordings</b>, registered below as
 *       cumulative {@link NetworkCapture#source}s. Each supplier hands over the WHOLE recording
 *       every time it is asked and the framework remembers how much of it earlier stories consumed,
 *       so every recorded request lands in exactly one story. They are invoked lazily at story end,
 *       so registering them before anything has been recorded is safe.
 * </ul>
 *
 * <h2>Story order is load-bearing</h2>
 *
 * <p>A cumulative source is attributed by a cursor, so traffic recorded before a drain lands in
 * whichever story drains <b>first</b>. This catalogue has no boot traffic to attribute — a launched
 * edge dials nothing until a request arrives — but the rule still decides the shape: every story
 * class carries {@code @UserflowRunsAfter(ForwardAuthBootstrapIT.class)} on its stories, and {@code
 * UserflowClassOrderer} (registered as junit's secondary orderer in the test {@code
 * application.properties}) is what turns those annotations into an order. Run a later class on its
 * own and its first story inherits whatever the launch happened to produce and fails its edge count
 * — loudly, which is the right way for that assumption to break.
 *
 * <h2>Idempotence</h2>
 *
 * <p>{@link NetworkCapture#source} re-registering under an id replaces the supplier and KEEPS its
 * cursor, so every story class may call {@link #install()} from its own {@code @BeforeAll} without
 * the diagram doubling an edge or losing one. A class that installs it must pin at least one edge —
 * otherwise a {@code @BeforeAll} dropped in a later edit would silently empty every diagram in it
 * and every remaining assertion would still pass.
 */
public final class StoryNetwork {

  private StoryNetwork() {}

  /** Register every far side. The near side needs no wiring — see the class comment. */
  public static void install() {
    farSide(StoryTarget.PROJECTS);
    farSide(StoryTarget.DOCS);
    farSide(StoryTarget.MIRROR);
    idp();
  }

  /**
   * One service's recording as traffic leaving the edge.
   *
   * <p>The source id is the service's own name and so is the edge's {@code to}: these are the
   * services a deployment really routes to, so the diagram names the dependency the configuration
   * declares rather than a loopback port this run happened to get.
   *
   * <p><b>The KIND comes from the recording</b>, not from a constant. A plain proxied request is
   * {@code http}; a WebSocket handshake is {@code socket}, because to a reader of a dependency map
   * the interesting fact is that the edge holds a connection open to a service rather than which
   * RFC the upgrade followed. They are the same hop through the same process and they take entirely
   * different code paths inside it — {@code EdgeWebSocketUpgrade} never installs the interceptor
   * chain — which is exactly why they must not draw as one arrow.
   */
  private static void farSide(String service) {
    NetworkCapture.source(
        service,
        () ->
            StoryUpstream.attach(service).recordedRequests().stream()
                .map(
                    request ->
                        new NetworkEdge(
                            request.kind(), StoryTarget.SERVICE, service, request.label()))
                .toList());
  }

  /**
   * The identity provider, which is where this service's browser half gets its answers.
   *
   * <p>The edge holds no session store and decides nothing about a cookie on its own, so idp's
   * recordings are what prove it ASKED — with its own client credential, over a real socket —
   * rather than believed. The label carries the status the mock ANSWERED with, which is the half a
   * method and a path cannot supply.
   */
  private static void idp() {
    NetworkCapture.source(
        StoryTarget.IDP,
        () ->
            MockService.attach(StoryTarget.IDP).recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            StoryTarget.SERVICE,
                            StoryTarget.IDP,
                            StoryTarget.proxied(
                                request.method(), request.path(), request.status())))
                .toList());
  }

  /** How many times idp has been asked about a cookie — what proves a cache hit made no call. */
  static long introspections() {
    return MockService.attach(StoryTarget.IDP).recordedRequests().stream()
        .filter(request -> StoryTarget.INTROSPECT.equals(request.path()))
        .count();
  }

  /** The one introspection at {@code path}, or a failure naming how many there were. */
  static MockService.RecordedRequest onlyIntrospection() {
    List<MockService.RecordedRequest> matched =
        MockService.attach(StoryTarget.IDP).recordedRequests().stream()
            .filter(request -> StoryTarget.INTROSPECT.equals(request.path()))
            .toList();
    if (matched.size() != 1) {
      throw new AssertionError(
          "exactly one introspection must have reached idp, but " + matched.size() + " did");
    }
    return matched.getFirst();
  }
}
