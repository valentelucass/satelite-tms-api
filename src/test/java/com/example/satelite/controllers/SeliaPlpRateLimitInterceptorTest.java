package com.example.satelite.controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.services.selia.SeliaPlpRateLimitService;
import com.example.satelite.services.selia.SeliaPreShipmentListService;

class SeliaPlpRateLimitInterceptorTest {

    @Test
    void deveRetornar429NaRajadaSemExporChave() throws Exception {
        SeliaPreShipmentListService plpService = new SeliaPreShipmentListService(null);
        ReflectionTestUtils.setField(plpService, "logisticProviderApiKey", "segredo-da-plp");
        SeliaPlpRateLimitService rateLimit = new SeliaPlpRateLimitService(true, 60_000, 1, 10, 100, Clock.systemUTC());
        SeliaPlpRateLimitInterceptor interceptor = new SeliaPlpRateLimitInterceptor(plpService, rateLimit);

        assertTrue(interceptor.preHandle(requisicao("segredo-da-plp"), new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse bloqueada = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(requisicao("segredo-da-plp"), bloqueada, new Object()));
        assertTrue(bloqueada.getContentAsString().contains("selia.plp.rate_limit"));
        assertFalse(bloqueada.getContentAsString().contains("segredo-da-plp"));
    }

    private MockHttpServletRequest requisicao(String chave) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/selia/intelipost/pre-shipment-list");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("logistic-provider-api-key", chave);
        return request;
    }
}
