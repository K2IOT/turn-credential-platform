package com.k2iot.turncred.auth;

import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class TenantAuthInterceptor implements HandlerInterceptor {

    private final TenantRepository tenantRepository;

    public TenantAuthInterceptor(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        String hash = sha256Hex(apiKey);
        Optional<Tenant> tenant = tenantRepository.findByApiKeyHash(hash);

        if (tenant.isEmpty() || tenant.get().getStatus() != TenantStatus.ACTIVE) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        CurrentTenantHolder.set(tenant.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentTenantHolder.clear();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
