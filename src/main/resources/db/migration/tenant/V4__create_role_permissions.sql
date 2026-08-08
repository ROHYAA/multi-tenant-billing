-- V4: Create role_permissions table in tenant schema
CREATE TABLE IF NOT EXISTS role_permissions (
    id              BIGSERIAL   PRIMARY KEY,
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT,
    role_id         BIGINT      NOT NULL REFERENCES roles(id),
    permission_id   BIGINT      NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id)
    );

CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id ON role_permissions (role_id);
