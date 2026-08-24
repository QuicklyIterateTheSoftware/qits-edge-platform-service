-- One host per service, and a navigation tree instead of a flat list.
--
-- The projection is rebuilt from the epoch on every start (see the README), so a field that has
-- left the wire is DROPPED rather than kept nullable: nothing reads a column the next boot would
-- never write again, and a dead column is a second place to look.

-- The public name this application answers to inside its environment: `ci` for ci.dev.example.com.
-- Null while an application publishes none, which is every application until it is flipped.
alter table edge_deployment_snapshot
    add column browser_host varchar(63);

-- One name, one service, per environment. The deployer should never publish a host two
-- applications claim; this index is the belt, so an invalid event cannot make a public name
-- ambiguous the way a conflicting path cannot make a route ambiguous.
create unique index uq_edge_snapshot_browser_host
    on edge_deployment_snapshot (environment_name, browser_host)
    where browser_host is not null;

-- The declaration order of an application's routes. The FIRST of them is its PRIMARY route: the
-- segment its SPA is served under, the upstream its own host resolves to, and the path a legacy
-- navigation link points at. That order is a fact of the wire the first shape simply dropped.
alter table edge_endpoint
    add column ordinal integer not null default 0;

-- Navigation is an application-level fact now: one placement per slot, and the slot vocabulary is
-- closed (EdgeRoutes.SLOTS). The shell instantiates the nodes; the edge only says where they go.
create table edge_navigation_entry (
    environment_name varchar(63) not null,
    application_name varchar(255) not null,
    slot varchar(32) not null,
    label varchar(64) not null,
    "position" integer not null,
    -- At most one placement per slot per application, which is what makes the key the invariant.
    primary key (environment_name, application_name, slot),
    foreign key (environment_name, application_name)
        references edge_deployment_snapshot (environment_name, application_name)
        on delete cascade
);

-- The old per-endpoint navigation. An old frame still carries these two fields and the edge still
-- reads them off the wire, but it stores what they MEAN — one `system` placement — rather than
-- where they were written.
alter table edge_endpoint drop column navigation_label;
alter table edge_endpoint drop column navigation_position;
