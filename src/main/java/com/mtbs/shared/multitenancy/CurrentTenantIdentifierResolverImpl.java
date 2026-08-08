package com.mtbs.shared.multitenancy;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CurrentTenantIdentifierResolverImpl
        implements CurrentTenantIdentifierResolver<String> {

    private static final String DEFAULT_SCHEMA = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String schema = TenantContext.getSchemaName();
        if (schema == null || schema.isBlank()) {
            log.debug(">>> resolveCurrentTenantIdentifier: no schema in context, defaulting to '{}'", DEFAULT_SCHEMA);
            return DEFAULT_SCHEMA;
        }
        log.debug(">>> resolveCurrentTenantIdentifier returning: '{}'", schema);
        return schema;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}

