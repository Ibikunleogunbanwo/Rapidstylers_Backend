package com.macrotel.rapidstylers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RapidstylersApplication {
	public static void main(String[] args) {
		SpringApplication.run(RapidstylersApplication.class, args);
	}

}
