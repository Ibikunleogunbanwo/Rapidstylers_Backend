package com.macrotel.rapidstylers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;

// Sign-in is our own JWT filter, so Spring's default in-memory user (and its
// "generated security password" log line) is switched off.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class RapidstylersApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(RapidstylersApplication.class);
		// application.properties exposes only /actuator/health (an unauthenticated probe
		// endpoint). The metrics endpoint — which carries the ThrottledLog degradation
		// counters — is added here at a precedence that overrides that list; it is still
		// gated by the shared x-api-key via ApiKeyFilter, exactly like every other
		// non-health path. Deployments that prefer not to expose metrics can override
		// MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE in the environment instead.
		application.addInitializers(context -> context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("actuator-metrics-exposure", Collections.singletonMap(
						"management.endpoints.web.exposure.include", "health,metrics"))));
		application.run(args);
	}

}
