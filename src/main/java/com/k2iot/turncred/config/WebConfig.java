package com.k2iot.turncred.config;

import com.k2iot.turncred.auth.TenantAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantAuthInterceptor tenantAuthInterceptor;

    public WebConfig(TenantAuthInterceptor tenantAuthInterceptor) {
        this.tenantAuthInterceptor = tenantAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantAuthInterceptor)
                .addPathPatterns("/v1/turn-credentials/**");
    }
}
