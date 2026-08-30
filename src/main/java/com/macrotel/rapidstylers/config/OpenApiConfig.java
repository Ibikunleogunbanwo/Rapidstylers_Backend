package com.macrotel.rapidstylers.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger wiring. springdoc-openapi is already on the classpath and
 * auto-scans the public endpoints plus method-level @PreAuthorize role guards;
 * this bean names the two auth mechanisms every non-public call needs — the
 * shared x-api-key header and the optional Bearer JWT. The generated docs are
 * available at /swagger-ui.html and /v3/api-docs (both still gated by the
 * shared API key via AppConfig).
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "RapidStylers API",
                version = "1.0",
                description = "Marketplace API for booking beauty & grooming professionals."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecuritySchemes({
        @SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT"),
        @SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = "x-api-key")
})
public class OpenApiConfig {
}