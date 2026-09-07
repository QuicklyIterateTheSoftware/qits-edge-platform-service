package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Native registrations for both live event frames and catch-up pages, plus the consumers' DTOs. */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      DeploymentActiveSubscriber.DeploymentActivePayload.class,
      DeploymentActiveSubscriber.EndpointPayload.class,
      DeploymentActiveSubscriber.NavigationPayload.class,
      ProjectLifecycleSubscriber.ProjectLifecyclePayload.class
    },
    classNames = {"eu.wohlben.qits.eventstream.control.EventPage"})
final class EventWireReflection {

  private EventWireReflection() {}
}
