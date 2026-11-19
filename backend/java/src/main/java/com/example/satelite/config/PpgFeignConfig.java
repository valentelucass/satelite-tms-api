package com.example.satelite.config;

import org.springframework.context.annotation.Bean;

import feign.Logger;

public class PpgFeignConfig {

    @Bean
    Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }
}
