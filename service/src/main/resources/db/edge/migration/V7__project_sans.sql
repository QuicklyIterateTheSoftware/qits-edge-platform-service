-- The edge's durable view of which projects exist, for the certificate rather than for routing.
--
-- qits-projects publishes ProjectCreated and ProjectDeleted; the edge reads them for one reason —
-- a project's own name is a certificate tier (*.<slug>.<domain> and *.<slug>.<env>.<domain>), and
-- before this the editor host reached the certificate only through a bootstrap key somebody had to
-- remember to extend. These rows are the current answer to "which slugs must the SAN list carry?"
-- and, like every other edge projection, they can be rebuilt from qits-events.
--
-- `present` is a TOMBSTONE rather than a delete, and it is what makes replay order-safe. This
-- consumer replays from the epoch, so a catch-up delivers a historical ProjectCreated LATE —
-- after a live ProjectDeleted for the same slug has already been handled. Deleting the row would
-- let that late create resurrect the project; keeping a row whose occurred_at is the delete's makes
-- the create simply older, and last-writer-wins by (occurred_at, event_id) refuses it. It is the
-- same rule edge_deployment_snapshot carries, restated on the one key this table has.
--
-- The slug is the primary key because it is what the certificate is built from: two projects
-- cannot hold one slug, and a rename arrives as its own event pair. project_id is carried for the
-- log line and for the operator reading this table, not to key it.
create table edge_project (
    slug        varchar(63)  primary key,
    project_id  varchar(255) not null,
    present     boolean      not null,
    event_id    varchar(255) not null,
    occurred_at timestamp(6) with time zone not null
);
