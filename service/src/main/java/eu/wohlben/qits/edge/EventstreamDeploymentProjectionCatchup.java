package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.CatchupResult;
import eu.wohlben.qits.eventstream.control.CatchupSweeper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** CDI adapter that keeps the edge's readiness policy independent of eventstream's mechanics. */
@ApplicationScoped
class EventstreamDeploymentProjectionCatchup implements DeploymentProjectionCatchup {

  @Inject CatchupSweeper catchup;

  @Override
  public CatchupResult rebuildFromEpoch(String consumerId) {
    return catchup.rebuildFromEpoch(consumerId);
  }

  @Override
  public CatchupResult catchUp(String consumerId) {
    return catchup.catchUp(consumerId);
  }
}
