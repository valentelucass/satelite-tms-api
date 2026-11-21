package com.example.satelite.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.satelite.controllers.SeliaPlpRateLimitInterceptor;

@Configuration
public class SeliaPlpWebMvcConfig implements WebMvcConfigurer {

    private final SeliaPlpRateLimitInterceptor seliaPlpRateLimitInterceptor;

    public SeliaPlpWebMvcConfig(SeliaPlpRateLimitInterceptor seliaPlpRateLimitInterceptor) {
        this.seliaPlpRateLimitInterceptor = seliaPlpRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(seliaPlpRateLimitInterceptor)
                .addPathPatterns("/api/selia/intelipost/pre-shipment-list");
    }
}
