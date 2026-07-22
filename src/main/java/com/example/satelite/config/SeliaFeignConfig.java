package com.example.satelite.config;

import org.springframework.context.annotation.Bean;

import feign.Logger;

public class SeliaFeignConfig {

    @Bean
    Logger.Level seliaFeignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
