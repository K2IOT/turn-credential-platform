package com.k2iot.turncred.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthInterceptorTest {

    private AdminAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AdminAuthInterceptor("dev-admin-key");
    }

    @Test
    void missingHeaderReturns401() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void invalidHeaderReturns401() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Admin-Api-Key", "wrong-key");
        var res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void validHeaderReturnsTrue() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Admin-Api-Key", "dev-admin-key");
        var res = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(req, res, new Object());

        assertThat(result).isTrue();
    }
}
