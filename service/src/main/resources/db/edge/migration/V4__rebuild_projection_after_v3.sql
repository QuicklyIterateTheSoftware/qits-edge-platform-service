-- Start the projection over, so the replay rebuilds it in the V3 shape.
--
-- V3 dropped edge_endpoint.navigation_label/navigation_position: on the wire that fact moved to the
-- application level, and the edge maps an old frame's label to a `system` placement as it replays.
-- But the replay is idempotent by event id — EdgeRoutes.replace skips a frame the snapshot already
-- records — so an edge upgraded in place never re-read the frames it had, and every application's
-- placement was gone with the column (wohlben.eu, 2026-08-24: 18 routes, 0 placements). Deleting
-- the snapshot rows (endpoints and placements cascade) makes the next boot's replay write them all
-- again. A fresh database has nothing to delete and is unaffected.
delete from edge_deployment_snapshot;
