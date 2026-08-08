package com.mtbs.auth.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantResolutionResponse {

    @Builder.Default
    private List<TenantOption> tenants = new ArrayList<>();

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantOption {
        private String slug;
        private String name;
    }

    public static TenantResolutionResponse fromOptions(List<com.mtbs.auth.service.SlugGeneratorService.TenantOption> options) {
        if (options == null || options.isEmpty()) {
            return TenantResolutionResponse.builder()
                    .tenants(new ArrayList<>())
                    .build();
        }
        
        List<TenantOption> tenantOptions = options.stream()
                .map(opt -> TenantOption.builder()
                        .slug(opt.slug())
                        .name(opt.name())
                        .build())
                .toList();
        
        return TenantResolutionResponse.builder()
                .tenants(tenantOptions)
                .build();
    }
}