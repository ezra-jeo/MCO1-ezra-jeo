package com.kingpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KingpayApplication {

	/** Starts the application.
	 * <p>Preconditions: environment and database configuration are available.
	 * <p>Postconditions: Spring starts the API or fails startup without a usable application.
	 * @param args Spring Boot command-line options
	 */
	public static void main(String[] args) {
		SpringApplication.run(KingpayApplication.class, args);
	}

}
