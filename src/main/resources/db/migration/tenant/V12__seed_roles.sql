-- V12: Seed roles
INSERT INTO roles (name, deleted, version, created_at, updated_at)
VALUES ('OWNER',    false, 0, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

INSERT INTO roles (name, deleted, version, created_at, updated_at)
VALUES ('ADMIN',    false, 0, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

INSERT INTO roles (name, deleted, version, created_at, updated_at)
VALUES ('EMPLOYEE', false, 0, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
