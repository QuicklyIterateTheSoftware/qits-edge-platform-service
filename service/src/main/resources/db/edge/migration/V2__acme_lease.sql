CREATE TABLE edge_acme_lease (
    lease_name  text PRIMARY KEY,
    owner_id    text        NOT NULL,
    expires_at  timestamptz NOT NULL
);
