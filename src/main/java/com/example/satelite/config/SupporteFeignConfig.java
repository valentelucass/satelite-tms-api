package com.example.satelite.config;

import org.springframework.context.annotation.Bean;

import feign.Logger;

public class SupporteFeignConfig {

    @Bean
    Logger.Level supporteFeignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
