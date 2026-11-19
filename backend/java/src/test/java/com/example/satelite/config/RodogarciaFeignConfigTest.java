package com.example.satelite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.example.satelite.services.etl.EslRequestPolicyService.EslRequestTransientException;

import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.ErrorDecoder;

class RodogarciaFeignConfigTest {

    @Test
    void deveConverterHttp500HtmlEmExcecaoTransitoriaLimpa() {
        ErrorDecoder decoder = new RodogarciaFeignConfig().rodogarciaErrorDecoder();
        Response response = Response.builder()
                .status(500)
                .reason("Internal Server Error")
                .request(criarRequestEsl())
                .body("<!doctype html><html>bloqueio</html>", StandardCharsets.UTF_8)
                .build();

        Exception erro = decoder.decode("RodogarciaClient#buscarOcorrencias()", response);

        EslRequestTransientException transitoria =
                assertInstanceOf(EslRequestTransientException.class, erro);
        assertEquals(500, transitoria.status());
        assertEquals("RodogarciaClient#buscarOcorrencias()", transitoria.operacao());
        assertFalse(transitoria.getMessage().contains("<!doctype html>"));
    }

    private Request criarRequestEsl() {
        return Request.create(
                Request.HttpMethod.GET,
                "https://rodogarcia.eslcloud.com.br/api/customer/invoice_occurrences",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                new RequestTemplate()
        );
    }
}
