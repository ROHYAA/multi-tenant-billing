package com.mtbs.auth.dto.role;

import com.mtbs.auth.dto.permission.PermissionResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDetailResponse {

    private Long id;
    private String name;
    private List<PermissionResponse> permissions;
}