CREATE TABLE IF NOT EXISTS public.platform_admins (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(120) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    name        VARCHAR(120) NOT NULL,
    role        VARCHAR(50)  NOT NULL DEFAULT 'SUPER_ADMIN',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Default SUPER_ADMIN credentials:
-- email:    admin@platform.com
-- password: Admin@1234  (BCrypt hash below)
-- BCrypt hash of 'Admin@1234' with strength 10
INSERT INTO public.platform_admins (email, password, name, role)
VALUES (
    'admin@platform.com',
    '$2a$10$n5yCz8yCTd65uAPmJfkji.GrochuM5Iz0GhH1Zvv7GQ6LKNk/W2KW',
    'Platform Administrator',
    'SUPER_ADMIN'
)
ON CONFLICT (email) DO NOTHING;
