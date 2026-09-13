package com.macrotel.rapidstylers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Collections;

@SpringBootApplication
@EnableScheduling
public class RapidstylersApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(RapidstylersApplication.class);
		// application.properties exposes only /actuator/health (an unauthenticated probe
		// endpoint). The metrics endpoint — which carries the ThrottledLog degradation
		// counters — is added here at a precedence that overrides that list; it is still
		// gated by the shared x-api-key via AppConfig, exactly like every other
		// non-health path. Deployments that prefer not to expose metrics can override
		// MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE in the environment instead.
		application.addInitializers(context -> context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("actuator-metrics-exposure", Collections.singletonMap(
						"management.endpoints.web.exposure.include", "health,metrics"))));
		application.run(args);
	}

}
