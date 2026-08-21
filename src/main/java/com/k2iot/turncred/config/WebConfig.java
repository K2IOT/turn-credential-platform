package com.k2iot.turncred.config;

import com.k2iot.turncred.auth.AdminAuthInterceptor;
import com.k2iot.turncred.auth.TenantAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantAuthInterceptor tenantAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebConfig(TenantAuthInterceptor tenantAuthInterceptor, AdminAuthInterceptor adminAuthInterceptor) {
        this.tenantAuthInterceptor = tenantAuthInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantAuthInterceptor)
                .addPathPatterns("/v1/turn-credentials/**");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/v1/admin/**");
    }
}
