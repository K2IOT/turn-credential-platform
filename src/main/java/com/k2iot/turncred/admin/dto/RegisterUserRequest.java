package com.k2iot.turncred.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterUserRequest(
        @NotBlank(message = "userId is required") String userId
) {}
