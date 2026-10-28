package com.kingpay.security;

import com.kingpay.dto.response.ApiResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/** Stateless bearer authentication with a local signing key and database-backed identities. */
@Configuration
@EnableConfigurationProperties({JwtSettings.class, AdminSettings.class})
public class SecurityConfig {

    private static final int BCRYPT_STRENGTH = 12;
    private static final int MINIMUM_SIGNING_BYTES = 32;

    /**
     * Supplies the required password-encoding abstraction with existing BCrypt compatibility.
     * <p>Preconditions: none.
     * <p>Postconditions: encoding uses a random salt and existing cost policy.
     * @return BCrypt password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * Connects username/password authentication to persisted user details.
     * <p>Preconditions: details and encoder beans exist.
     * <p>Postconditions: password, unknown-user, and disabled-user checks use Spring Security.
     * @param details database-backed identity adapter
     * @param encoder password verification strategy
     * @return authentication manager
     */
    @Bean
    public AuthenticationManager authenticationManager(KingpayUserDetailsService details, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(List.of(provider));
    }

    /**
     * Decodes the external signing key without exposing it in configuration errors.
     * <p>Preconditions: settings contain a Base64 key with at least 256 bits of entropy.
     * <p>Postconditions: a local HMAC key is available; invalid configuration fails startup.
     * @param settings external JWT configuration
     * @return HMAC-SHA256 signing key
     */
    @Bean
    public SecretKey jwtSigningKey(JwtSettings settings) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(settings.secret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT signing key must be valid Base64.");
        }
        if (bytes.length < MINIMUM_SIGNING_BYTES) {
            throw new IllegalStateException("JWT signing key must contain at least 32 random bytes.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /**
     * Supplies JWT signing using the externally configured key.
     * <p>Preconditions: key passed startup validation.
     * <p>Postconditions: no external identity provider is contacted.
     * @param key HMAC signing key
     * @return JWT encoder
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * Validates signature, issuer, required expiration, and timestamps with no expiry grace period.
     * <p>Preconditions: key and issuer configuration are valid.
     * <p>Postconditions: expired, unsigned, or wrong-issuer tokens are rejected.
     * @param key HMAC verification key
     * @param settings expected issuer configuration
     * @return validating JWT decoder
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey key, JwtSettings settings) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(settings.issuer()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)));
        return decoder;
    }

    /**
     * Supplies a shared UTC clock for deterministic token issuance.
     * <p>Preconditions: none.
     * <p>Postconditions: issuing services use UTC instants.
     * @return system UTC clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Protects business routes while allowing public authentication POSTs and read-only API documentation.
     * <p>Preconditions: JWT and JSON infrastructure are configured.
     * <p>Postconditions: bearer authentication is stateless; security failures use the API envelope.
     * @param http Spring Security builder
     * @param converter current-identity JWT adapter
     * @param mapper response serializer
     * @return configured filter chain
     * @throws Exception if security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            DatabaseJwtAuthenticationConverter converter, ObjectMapper mapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                writeFailure(response, mapper, HttpStatus.UNAUTHORIZED, "Authentication required or invalid."))
                        .accessDeniedHandler((request, response, error) ->
                                writeFailure(response, mapper, HttpStatus.FORBIDDEN, "Access denied.")))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) ->
                                writeFailure(response, mapper, HttpStatus.UNAUTHORIZED, "Authentication required or invalid."))
                        .accessDeniedHandler((request, response, error) ->
                                writeFailure(response, mapper, HttpStatus.FORBIDDEN, "Access denied.")))
                .build();
    }

    /**
     * Serializes a security failure without credentials or exception diagnostics.
     * <p>Preconditions: message is a fixed public-safe string and response is writable.
     * <p>Postconditions: response uses the same JSON envelope as controller errors.
     * @param response servlet response
     * @param mapper JSON serializer
     * @param status HTTP failure status
     * @param message public-safe error description
     * @throws IOException if the client response cannot be written
     */
    private static void writeFailure(HttpServletResponse response, ObjectMapper mapper, HttpStatus status,
            String message) throws IOException {
        response.setStatus(status.value());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiResponse<>(message, null));
    }
}
