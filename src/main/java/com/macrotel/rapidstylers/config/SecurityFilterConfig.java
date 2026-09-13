package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityFilterConfig {

    /**
     * Starter Content-Security-Policy for this origin (API + the bundled Swagger UI).
     * JSON responses are unaffected by CSP — it constrains HTML/worker documents —
     * and the policy is written so the self-hosted Swagger UI keeps working:
     * default-src 'none' kills everything not explicitly allowed; self + inline
     * script/style keep the UI's bundled bootstrap intact; object-src/base-uri/
     * form-action/frame-ancestors harden against plugins, base-tag and clickjacking.
     * Tighten later (drop the inline script allowance) when/if the Swagger UI is
     * removed or moved behind ADMIN-only access in production.
     */
    private static final String SECURITY_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private final JwtAuthFilter jwtAuthFilter;
    private final CorsConfig corsConfig;

    public SecurityFilterConfig(JwtAuthFilter jwtAuthFilter, CorsConfig corsConfig) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.corsConfig = corsConfig;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors().and()
            .csrf().disable()
            // Security headers, declared explicitly so a refactor cannot silently
            // drop them: nosniff, DENY framing, HSTS (applied to secure requests
            // only), and the CSP above. Cache-Control and the deprecated XSS
            // protection remain Spring defaults.
            .headers()
                .contentTypeOptions()
                .and().frameOptions().deny()
                .httpStrictTransportSecurity()
                .and().contentSecurityPolicy(SECURITY_CSP)
                .and()
            .and()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
            .authorizeRequests()
                .antMatchers("/actuator/**").permitAll()
                .anyRequest().permitAll()
            .and()
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt encoder used for admin-account password hashes. Env-only admin
     * passwords are re-hashed here when each admin account is created/seeded.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
