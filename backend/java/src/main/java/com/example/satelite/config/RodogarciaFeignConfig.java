package com.example.satelite.config;

import org.springframework.context.annotation.Bean;

import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;

import feign.Response;
import feign.codec.ErrorDecoder;

public class RodogarciaFeignConfig {

    @Bean
    ErrorDecoder rodogarciaErrorDecoder() {
        return new RodogarciaErrorDecoder();
    }

    private static class RodogarciaErrorDecoder implements ErrorDecoder {

        private final ErrorDecoder defaultDecoder = new ErrorDecoder.Default();

        @Override
        public Exception decode(String methodKey, Response response) {
            if (response != null && response.status() >= 500 && response.status() <= 599) {
                String operacao = normalizarOperacao(methodKey);
                return new EslRequestTransientException(
                        operacao,
                        response.status(),
                        "Falha transitoria da ESL em " + operacao + " (HTTP " + response.status() + ")",
                        null
                );
            }

            return defaultDecoder.decode(methodKey, response);
        }

        private String normalizarOperacao(String methodKey) {
            if (methodKey == null || methodKey.isBlank()) {
                return "RodogarciaClient";
            }

            return methodKey.trim();
        }
    }
}
