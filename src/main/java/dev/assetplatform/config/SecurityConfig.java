package dev.assetplatform.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.assetplatform.exception.ErrorWriter;
import jakarta.servlet.DispatcherType;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecretKey jwtKey(PlatformProperties props) {
    byte[] bytes = Base64.getDecoder().decode(props.jwtSecret());
    if (bytes.length < 32)
      throw new IllegalArgumentException("JWT_SECRET must encode at least 32 random bytes");
    if (props.tokenTtl().isNegative()
        || props.tokenTtl().isZero()
        || props.tokenTtl().compareTo(java.time.Duration.ofMinutes(30)) > 0) {
      throw new IllegalArgumentException("Token TTL must be positive and at most 30 minutes");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey key, PlatformProperties props) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> identity =
        jwt -> {
          try {
            UUID.fromString(jwt.getSubject());
            if (!jwt.getAudience().contains("asset-api") || jwt.getExpiresAt() == null)
              throw new IllegalArgumentException();
            return OAuth2TokenValidatorResult.success();
          } catch (IllegalArgumentException | NullPointerException ex) {
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Invalid token claims", null));
          }
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(props.jwtIssuer()), identity));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      ErrorWriter errors,
      dev.assetplatform.security.RateLimiter limiter,
      io.micrometer.core.instrument.MeterRegistry metrics)
      throws Exception {
    return http.addFilterAfter(
            new dev.assetplatform.security.RateLimitFilter(limiter, errors, metrics),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/actuator/health",
                        "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .hasAuthority("SCOPE_metrics.read")
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(
                        (req, res, ex) ->
                            errors.write(
                                req,
                                res,
                                HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED",
                                "Valid bearer token required")))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (req, res, err) ->
                            errors.write(
                                req,
                                res,
                                HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED",
                                "Valid bearer token required"))
                    .accessDeniedHandler(
                        (req, res, err) ->
                            errors.write(
                                req, res, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied")))
        .build();
  }
}
