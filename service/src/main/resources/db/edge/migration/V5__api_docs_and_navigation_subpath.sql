-- Two additions to what a deployment publishes: where an application's browsable API document
-- lives, and which view of the declaring application a navigation entry opens.
--
-- api_docs_path is a PATH under one of the application's published routes (`/ci/q/swagger-ui`),
-- never an origin — the document composes the authority around it exactly as it does for a primary
-- path. Null is a real answer: a service that documents no HTTP surface.
alter table edge_deployment_snapshot
    add column api_docs_path varchar(255);

-- subpath is a client-side route segment the shell appends after the scope it composes
-- (`/<project>/<category>/<repository>/api-docs`). Null is every entry declared before the field
-- existed: the application's root under that same scope.
alter table edge_navigation_entry
    add column subpath varchar(255);

-- V4's lesson applied in advance: the replay is idempotent by event id, so an edge upgraded in
-- place would never re-read the frames it already recorded and both new columns would stay null
-- until every application deployed again. Deleting the snapshot rows (endpoints and placements
-- cascade) makes the next boot's replay rewrite the projection in this shape. A fresh database has
-- nothing to delete and is unaffected.
delete from edge_deployment_snapshot;
