package com.k2iot.turncred.logging;

import com.k2iot.turncred.auth.CurrentTenantHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            var tenant = CurrentTenantHolder.get();
            long latencyMs = System.currentTimeMillis() - start;
            log.info("request method={} path={} status={} tenantId={} latencyMs={}",
                    httpRequest.getMethod(), httpRequest.getRequestURI(), httpResponse.getStatus(),
                    tenant != null ? tenant.getId() : "anonymous", latencyMs);
            MDC.remove("requestId");
        }
    }
}
