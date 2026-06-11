package com.buildassist.config;

/**
 * Request matchers for endpoints exposed without JWT (also used in SecurityConfig).
 */
public final class OpenApiPublicPaths {

    public static final String[] SWAGGER = {
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs",
        "/v3/api-docs/**"
    };

    private OpenApiPublicPaths() {
    }
}
