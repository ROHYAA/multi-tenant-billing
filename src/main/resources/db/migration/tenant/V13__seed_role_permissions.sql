-- V14: Seed role permissions

-- OWNER gets ALL permissions from public.permissions
INSERT INTO role_permissions (role_id, permission_id, created_at, updated_at, deleted, version)
SELECT r.id, p.id, NOW(), NOW(), FALSE, 0
FROM roles r
         CROSS JOIN public.permissions p
WHERE r.name = 'OWNER'
  AND r.deleted = FALSE
  AND p.deleted = FALSE
    ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- ADMIN: all except USER_DELETE, TENANT_MANAGE
INSERT INTO role_permissions (role_id, permission_id, created_at, updated_at, deleted, version)
SELECT r.id, p.id, NOW(), NOW(), FALSE, 0
FROM roles r
         CROSS JOIN public.permissions p
WHERE r.name = 'ADMIN'
  AND p.name NOT IN ('USER_DELETE', 'TENANT_MANAGE')
  AND r.deleted = FALSE
  AND p.deleted = FALSE
    ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- EMPLOYEE: USER_VIEW only
INSERT INTO role_permissions (role_id, permission_id, created_at, updated_at, deleted, version)
SELECT r.id, p.id, NOW(), NOW(), FALSE, 0
FROM roles r
         CROSS JOIN public.permissions p
WHERE r.name = 'EMPLOYEE'
  AND p.name IN ('USER_VIEW')
  AND r.deleted = FALSE
  AND p.deleted = FALSE
    ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;
