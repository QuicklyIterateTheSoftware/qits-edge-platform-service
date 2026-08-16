-- The edge's durable view of DeploymentActive. qits-events is the log; these rows are the current
-- answer to "where does this environment's path go?" and can be rebuilt from that log.
create table edge_deployment_snapshot (
    environment_name varchar(63) not null,
    application_name varchar(255) not null,
    event_id varchar(255) not null,
    occurred_at timestamp(6) with time zone not null,
    primary key (environment_name, application_name)
);

create table edge_endpoint (
    environment_name varchar(63) not null,
    application_name varchar(255) not null,
    path varchar(2048) not null,
    upstream_host varchar(255) not null,
    upstream_port integer not null,
    navigation_label varchar(255),
    navigation_position integer,
    primary key (environment_name, application_name, path),
    -- Two applications claiming one path is not a routing tie the edge may guess at. Deployment
    -- validation should prevent it; this constraint keeps an invalid event from corrupting a good
    -- projection if it nevertheless arrives.
    unique (environment_name, path),
    foreign key (environment_name, application_name)
        references edge_deployment_snapshot (environment_name, application_name)
        on delete cascade,
    check (upstream_port between 1 and 65535)
);

create index idx_edge_endpoint_environment_path
    on edge_endpoint (environment_name, path);
