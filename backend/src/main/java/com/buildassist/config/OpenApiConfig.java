package com.buildassist.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_JWT = "bearer-jwt";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("BuildAssist Email API")
                .description("AI email assistant MVP for construction companies")
                .version("0.0.1")
                .contact(new Contact().name("BuildAssist")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT))
            .components(new Components()
                .addSecuritySchemes(BEARER_JWT, new SecurityScheme()
                    .name(BEARER_JWT)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT from POST /api/auth/login")));
    }
}
