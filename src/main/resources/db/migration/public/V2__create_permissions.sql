-- V2: Create permissions table in public schema
CREATE TABLE IF NOT EXISTS permissions (
                                           id          BIGSERIAL       PRIMARY KEY,
                                           deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
                                           deleted_at  TIMESTAMPTZ,
                                           version     BIGINT          NOT NULL DEFAULT 0,
                                           created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_by  BIGINT,

    name        VARCHAR(255)    NOT NULL UNIQUE,
    description VARCHAR(500)
    );

CREATE INDEX IF NOT EXISTS idx_permissions_name ON permissions (name);
