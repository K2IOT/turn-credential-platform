package com.k2iot.turncred.auth;

import com.k2iot.turncred.util.HashUtil;
import com.k2iot.turncred.tenant.Tenant;
import com.k2iot.turncred.tenant.TenantRepository;
import com.k2iot.turncred.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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

        String hash = HashUtil.sha256Hex(apiKey);
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
}
