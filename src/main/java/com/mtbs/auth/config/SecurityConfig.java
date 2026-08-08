package com.mtbs.auth.config;

import com.mtbs.app.filter.MdcSecurityEnrichmentFilter;
import com.mtbs.auth.security.JwtAuthenticationEntryPoint;
import com.mtbs.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${api.version}")
    private String apiVersion;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final MdcSecurityEnrichmentFilter mdcSecurityEnrichmentFilter;

    private String[] getPublicUrls() {
        return new String[]{
                // Auth — only these specific endpoints are public
                "/api/" + apiVersion + "/auth/login",
                "/api/" + apiVersion + "/auth/signup",
                "/api/" + apiVersion + "/auth/refresh",
                "/api/" + apiVersion + "/auth/forgot-password",
                "/api/" + apiVersion + "/auth/reset-password",

                // Admin auth — login/refresh only, logout is authenticated
                "/api/" + apiVersion + "/admin/auth/login",
                "/api/" + apiVersion + "/admin/auth/refresh",

                // Platform public endpoints
                "/api/" + apiVersion + "/plans",
                "/api/" + apiVersion + "/plans/{id}",
                "/api/" + apiVersion + "/health",

                // Payment webhooks — authenticated via Razorpay signature, not JWT
                "/api/" + apiVersion + "/webhooks/**",

                // API docs
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/api-docs/**",
                "/v3/api-docs/**",
                "/actuator/**"
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF disabled — stateless JWT architecture has no CSRF attack surface.
                // CSRF is a session-cookie exploit. We use HttpOnly JWT cookies with
                // SameSite=Strict, which browsers already block cross-origin.
                .csrf(AbstractHttpConfigurer::disable)

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(getPublicUrls()).permitAll()
                        .requestMatchers("/api/" + apiVersion + "/admin/**")
                        .hasAuthority("SUPER_ADMIN")
                        .anyRequest().authenticated()
                )

                // JWT filter runs first, then MDC enrichment picks up the auth context
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(mdcSecurityEnrichmentFilter,
                        JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        origins.replaceAll(String::trim);
        config.setAllowedOrigins(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Set-Cookie"));

        // Required for cookies (access_token, refresh_token) on cross-origin requests
        config.setAllowCredentials(true);

        // Cache preflight for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}