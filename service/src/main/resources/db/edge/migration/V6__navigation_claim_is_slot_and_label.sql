-- A navigation claim is a (slot, label) PAIR, not a slot.
--
-- V3 made (environment_name, application_name, slot) the primary key, on the reading that an
-- application appears once under each heading. It does not: qits-workspaces hangs Workspaces and
-- Editor under `project.detail` from one application in one container, and one entry per slot made
-- that unpublishable — qits-deployments refused the whole spec ("deployment spec unreadable" on a
-- green build) and this projection would have refused the frame a hop later.
--
-- Widening the key leaves the belt exactly where it was. The same row asked for twice is still
-- impossible, EdgeRoutes.validateSnapshot is still the refusal that carries a reason, and a
-- repeated POSITION is neither — the document breaks that tie by label and then by application, so
-- two rows of one application at one number order as stably as two applications at one number
-- already do.
--
-- No `delete from edge_deployment_snapshot` here, and that is the difference from V4 and V5: those
-- added a COLUMN the recorded frames had never been read for, so the projection had to be rebuilt
-- to fill it. Nothing is added here — every existing row is already in this shape — and a frame
-- that was refused for this rule was never recorded at all, so the ordinary epoch replay picks it
-- up on the next boot with nothing to repair.
alter table edge_navigation_entry
    drop constraint edge_navigation_entry_pkey;

alter table edge_navigation_entry
    add primary key (environment_name, application_name, slot, label);
