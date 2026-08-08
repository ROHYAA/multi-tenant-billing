-- V4: Seed permissions
INSERT INTO permissions (name, description, created_at, updated_at, deleted, version) VALUES
        ('TENANT_VIEW',     'View tenant details and settings',            NOW(), NOW(), FALSE, 0),
        ('TENANT_MANAGE',   'Update tenant settings and configurations',   NOW(), NOW(), FALSE, 0),
        ('USER_VIEW',       'View users within the tenant',                NOW(), NOW(), FALSE, 0),
        ('USER_MANAGE',     'Create and update users within the tenant',   NOW(), NOW(), FALSE, 0),
        ('USER_DELETE',     'Delete users from the tenant',                NOW(), NOW(), FALSE, 0),
        ('ROLE_VIEW',       'View roles and their permissions',            NOW(), NOW(), FALSE, 0),
        ('ROLE_MANAGE',     'Create, update and assign roles',             NOW(), NOW(), FALSE, 0),
        ('BILLING_MANAGE',  'Manage subscriptions, invoices and payments', NOW(), NOW(), FALSE, 0),
        ('CUSTOMER_MANAGE', 'Create, view, update and delete customers',   NOW(), NOW(), FALSE, 0),
        ('PRODUCT_MANAGE',  'Create, view, update and deactivate products',NOW(), NOW(), FALSE, 0)
    ON CONFLICT (name) DO NOTHING;
