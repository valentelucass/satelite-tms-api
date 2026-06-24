package com.example.satelite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class SateliteApplication {

	public static void main(String[] args) {
		SpringApplication.run(SateliteApplication.class, args);
	}

}
