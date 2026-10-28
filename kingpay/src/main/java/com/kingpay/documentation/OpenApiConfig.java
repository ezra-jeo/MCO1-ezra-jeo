package com.kingpay.documentation;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata and reusable errors; controllers/DTOs supply actual operation/schema shapes. */
@Configuration
public class OpenApiConfig {
    /** Preconditions: Springdoc is available. Postconditions: generated docs describe bearer security and safe API errors.
     * @return API metadata, bearer scheme, and reusable error responses without configuration secrets */
    @Bean
    public OpenAPI kingpayOpenApi() {
        var error = new ObjectSchema().addProperty("message", new StringSchema())
                .addProperty("data", new Schema<>().type("object").nullable(true).additionalProperties(true)
                        .description("Null for ordinary errors; validation failures map field names to error-message strings."));
        error.setRequired(java.util.List.of("message", "data"));
        var components = new Components().addSchemas("ErrorResponse", error)
                .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .bearerFormat("JWT").description("Log in, then paste data.token into Authorize. Current identity/activity is checked on every request."));
        Map.of("InvalidRequest", "Invalid fields, malformed JSON or nonpositive/unrepresentable values (400).",
                "Unauthenticated", "Missing/invalid/expired bearer, invalid login or inactive user (401).",
                "Forbidden", "Wrong owner/participant or caller not in the configured admin allowlist (403).",
                "NotFound", "Requested resource or required wallet does not exist (404).",
                "Conflict", "State conflict: duplicate username/wallet, insufficient funds, unavailable provider, capacity or premature payment (409).",
                "ServerError", "Persistence/unexpected failure; financial transactions roll back and no internal diagnostics are exposed (500).")
                .forEach((name, description) -> components.addResponses(name, new ApiResponse().description(description)
                        .content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))))));
        return new OpenAPI().info(new Info().title("Kingpay API").version("1.0")
                .description("Superhero protection bookings and simulated wallets. Register/login are public; business routes require an active bearer identity. "
                        + "Confirmation moves no funds. Customer payment is atomic and successful retries return the original result. "
                        + "Admin overview is read-only and restricted by externally configured stable user IDs; no password hashes or tokens are returned there."))
                .servers(java.util.List.of(new Server().url("/")))
                .components(components).addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
