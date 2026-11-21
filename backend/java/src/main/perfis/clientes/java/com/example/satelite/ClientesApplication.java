package com.example.satelite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public class ClientesApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SateliteApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
