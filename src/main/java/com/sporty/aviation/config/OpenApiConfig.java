package com.sporty.aviation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 configuration.
 *
 * <p>Registers the API metadata (title, version, description) and declares
 * the {@code X-API-Key} security scheme so that Swagger UI shows the
 * "Authorize" button and sends the header automatically on every try-it-out call.
 *
 * <p>UI:   <a href="http://localhost:8080/swagger-ui.html">http://localhost:8080/swagger-ui.html</a><br>
 * Spec: <a href="http://localhost:8080/v3/api-docs">http://localhost:8080/v3/api-docs</a>
 */
@Configuration
public class OpenApiConfig {

    /** Name used to reference the security scheme from controller annotations. */
    private static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI aviationOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, apiKeySecurityScheme()));
    }

    private Info apiInfo() {
        return new Info()
                .title("Aviation API Wrapper")
                .version("1.0.0")
                .description("""
                        Looks up airport details by **ICAO code** using the public \
                        [Aviation Weather API](https://aviationweather.gov/data/api/) provided by NOAA.

                        **Authentication:** every request requires a valid `X-API-Key` header.
                        Click **Authorize** and paste your key before using Try-it-out.

                        **Rate limiting:** 60 requests per minute per API key.
                        When exceeded the API returns `429 Too Many Requests` with a `Retry-After` header.

                        **Resilience:** automatic retry (3×) + circuit breaker + fallback to Provider 2 (AirportDB).
                        """)
                .contact(new Contact()
                        .name("Aviation API Wrapper")
                        .url("https://github.com/your-org/aviation-api-wrapper"));
    }

    /**
     * Declares the API key as an HTTP header named {@code X-API-Key}.
     * Swagger UI will include this header on every Try-it-out request
     * once the user clicks Authorize and enters a value.
     */
    private SecurityScheme apiKeySecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-API-Key")
                .description("API key passed in the `X-API-Key` request header. "
                        + "Contact the service owner to obtain a valid key.");
    }
}
