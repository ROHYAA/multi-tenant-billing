package com.mtbs.auth.service;

import com.mtbs.shared.exception.TenantException;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlugGeneratorService {

    private final TenantService tenantService;

    public String generateSlug(String tenantName) {
        String base = tenantName == null ? "tenant" : tenantName;

        base = base.toLowerCase().trim()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        if (base.isBlank()) {
            base = "tenant";
        }

        if (base.length() > 40) {
            base = base.substring(0, 40);
        }

        String candidate = base;
        int suffix = 1;
        while (tenantService.tenantSlugExists(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
            if (suffix > 999) {
                throw new TenantException(
                        com.mtbs.shared.exception.ErrorCode.TENANT_SLUG_ALREADY_EXISTS,
                        "Could not generate unique slug for: " + tenantName);
            }
        }

        log.debug("Generated slug: name={} slug={}", tenantName, candidate);
        return candidate;
    }

    public void validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new TenantException(
                    com.mtbs.shared.exception.ErrorCode.TENANT_SLUG_ALREADY_EXISTS,
                    "Slug is required");
        }

        if (!slug.matches("^[a-z0-9-]{2,50}$")) {
            throw new TenantException(
                    com.mtbs.shared.exception.ErrorCode.TENANT_SLUG_ALREADY_EXISTS,
                    "Slug must be 2-50 characters, lowercase letters, numbers, and hyphens only");
        }

        if (slug.startsWith("-") || slug.endsWith("-")) {
            throw new TenantException(
                    com.mtbs.shared.exception.ErrorCode.TENANT_SLUG_ALREADY_EXISTS,
                    "Slug cannot start or end with a hyphen");
        }

        if (tenantService.tenantSlugExists(slug)) {
            throw new TenantException(
                    com.mtbs.shared.exception.ErrorCode.TENANT_SLUG_ALREADY_EXISTS,
                    "Slug already taken: " + slug);
        }
    }

    public List<TenantOption> resolveTenantsForEmail(String email) {
        List<Tenant> allTenants = tenantService.getAllTenants();

        return allTenants.stream()
                .filter(t -> email != null && email.equalsIgnoreCase(t.getOwnerEmail()))
                .map(t -> new TenantOption(t.getSlug(), t.getName()))
                .toList();
    }

    public record TenantOption(String slug, String name) {}
}