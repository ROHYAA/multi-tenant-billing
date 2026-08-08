-- V1: Create roles table in tenant schema
CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL       PRIMARY KEY,
    deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_by  BIGINT,
    name        VARCHAR(100)    NOT NULL UNIQUE
    );

CREATE INDEX IF NOT EXISTS idx_roles_name ON roles (name);