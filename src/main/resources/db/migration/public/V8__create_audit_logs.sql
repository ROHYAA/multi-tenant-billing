-- V8__create_audit_logs.sql
-- Enterprise Audit Logging System
-- Stores audit trail for admin operations in the public schema

CREATE TABLE public.audit_logs (
    id BIGSERIAL PRIMARY KEY,
    
    -- WHO performed the action
    who_user_id BIGINT,
    who_user_email VARCHAR(255),
    who_user_name VARCHAR(255),
    who_role VARCHAR(100),
    
    -- WHAT action was performed
    what_action VARCHAR(50) NOT NULL,
    
    -- WHERE did it happen
    where_entity_type VARCHAR(50) NOT NULL,
    where_entity_id BIGINT,
    where_entity_name VARCHAR(500),
    
    -- WHAT CHANGED (JSONB for flexibility)
    changes_before JSONB,
    changes_after JSONB,
    changes_summary JSONB,
    
    -- CONTEXT information
    context_tenant_id BIGINT,
    context_tenant_name VARCHAR(255),
    context_ip_address VARCHAR(45),
    context_user_agent VARCHAR(500),
    context_metadata JSONB,
    
    -- Additional metadata
    description VARCHAR(1000),
    module VARCHAR(100),
    severity VARCHAR(20) DEFAULT 'INFO',
    
    -- Standard audit fields (inherited from AuditableEntity)
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted BOOLEAN DEFAULT FALSE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    deleted_by VARCHAR(255),
    version BIGINT DEFAULT 0
);

-- Indexes for common queries
CREATE INDEX idx_audit_logs_who_user_id ON public.audit_logs(who_user_id);
CREATE INDEX idx_audit_logs_what_action ON public.audit_logs(what_action);
CREATE INDEX idx_audit_logs_where_entity_type ON public.audit_logs(where_entity_type);
CREATE INDEX idx_audit_logs_where_entity_id ON public.audit_logs(where_entity_id);
CREATE INDEX idx_audit_logs_context_tenant_id ON public.audit_logs(context_tenant_id);
CREATE INDEX idx_audit_logs_created_at ON public.audit_logs(created_at);
CREATE INDEX idx_audit_logs_module ON public.audit_logs(module);
CREATE INDEX idx_audit_logs_severity ON public.audit_logs(severity);

-- Composite indexes for common query patterns
CREATE INDEX idx_audit_logs_tenant_created ON public.audit_logs(context_tenant_id, created_at DESC);
CREATE INDEX idx_audit_logs_user_created ON public.audit_logs(who_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity ON public.audit_logs(where_entity_type, where_entity_id);

-- GIN index for JSONB searching (optional, for complex queries)
CREATE INDEX idx_audit_logs_changes_gin ON public.audit_logs USING GIN (changes_after);
CREATE INDEX idx_audit_logs_metadata_gin ON public.audit_logs USING GIN (context_metadata);

-- Comments for documentation
COMMENT ON TABLE public.audit_logs IS 'Enterprise audit trail for all admin operations';
COMMENT ON COLUMN public.audit_logs.what_action IS 'Action performed: CREATE, UPDATE, DELETE, VIEW, LOGIN, LOGOUT, etc.';
COMMENT ON COLUMN public.audit_logs.where_entity_type IS 'Entity type: TENANT, USER, PLAN, SUBSCRIPTION, INVOICE, PAYMENT, SETTINGS';
COMMENT ON COLUMN public.audit_logs.severity IS 'Log level: INFO, WARNING, ERROR, CRITICAL';
