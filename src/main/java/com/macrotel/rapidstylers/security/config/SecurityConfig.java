package com.macrotel.rapidstylers.security.config;

import com.macrotel.rapidstylers.security.access.Roles;
import com.macrotel.rapidstylers.security.access.Routes;
import com.macrotel.rapidstylers.security.filter.JwtAuthFilter;
import com.macrotel.rapidstylers.security.handler.JsonAuthErrorHandler;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Wires the security chain: who the caller is (JwtAuthFilter), whether they may
 * call this route (the lists in Routes), and what a rejection looks like
 * (JsonAuthErrorHandler). The rules live in Routes so this class rarely changes.
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

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
    private final JsonAuthErrorHandler authErrorHandler;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, JsonAuthErrorHandler authErrorHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authErrorHandler = authErrorHandler;
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
            .exceptionHandling()
                .authenticationEntryPoint(authErrorHandler)
                .accessDeniedHandler(authErrorHandler)
                .and()
            // Closed by default: a route missing from Routes needs a signed-in account
            // (a real account role, so the sign-up upload pass cannot reach it).
            // mvcMatchers match routes exactly the way the controllers do, so a
            // variant such as a trailing slash cannot slip past a role rule.
            .authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .antMatchers(Routes.FRAMEWORK_PUBLIC).permitAll()
                .mvcMatchers(Routes.PUBLIC).permitAll()
                .mvcMatchers(Routes.ADMIN).hasRole(Roles.ADMIN)
                .mvcMatchers(Routes.STYLER).hasRole(Roles.STYLER)
                .mvcMatchers(Routes.CUSTOMER).hasRole(Roles.CUSTOMER)
                .mvcMatchers(Routes.SUPPORT).hasAnyRole(Roles.CUSTOMER, Roles.STYLER)
                .mvcMatchers(Routes.UPLOAD).hasAnyRole(Roles.ADMIN, Roles.STYLER, Roles.SIGNUP_UPLOAD)
                .anyRequest().hasAnyRole(Roles.ACCOUNTS)
            .and()
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * JwtAuthFilter is a @Component, so Spring Boot would also register it as a
     * plain servlet filter outside the chain. It belongs only inside the chain
     * (added above), so switch that second registration off.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterServletRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
