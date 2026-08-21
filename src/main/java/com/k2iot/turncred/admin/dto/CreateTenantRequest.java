package com.k2iot.turncred.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
        @NotBlank(message = "name is required") String name,
        @NotBlank(message = "realm is required") String realm
) {}
