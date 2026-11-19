package com.example.satelite.clients;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import feign.Feign;
import feign.Request;
import feign.Response;
import feign.Retryer;
import tools.jackson.databind.ObjectMapper;

class RodogarciaClientContractTest {
    private static final String CTE = "35260612345678000123570010000012341000012345";

    @Test void rotaClientePreservaFiltroAutenticacaoEContratoDaResposta() {
        verificar(null, "/api/customer/freight_delivery_receipts");
    }

    @Test void rotaClientePodeSerConfiguradaSemAlterarContratoGeral() {
        verificar("/api/customer/receipts-configurado", "/api/customer/receipts-configurado");
    }

    private void verificar(String configurada, String esperada) {
        try (var context = new GenericApplicationContext()) {
            if (configurada != null) context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("teste", Map.of("RODOGARCIA_CUSTOMER_DELIVERY_RECEIPTS_PATH", configurada)));
            var contract = new SpringMvcContract();
            contract.setResourceLoader(context);
            var request = new AtomicReference<Request>();
            var json = new ObjectMapper();
            String body = """
                    {"data":[{"id":123,"image_url":"https://example.invalid/canhoto.jpg",
                     "created_at":"2026-09-12T20:00:00-03:00","campo_novo":true,
                     "freight":{"cte_key":"%s","cte_number":1234,"id":30,"draft_number":42}}],
                     "paging":{"size":1,"total":1},"campo_novo":true}
                    """.formatted(CTE);
            var client = Feign.builder().contract(contract).retryer(Retryer.NEVER_RETRY)
                    .client((req, options) -> {
                        request.set(req);
                        return Response.builder().request(req).status(200).reason("OK")
                                .headers(Map.of("Content-Type", List.of("application/json")))
                                .body(body, StandardCharsets.UTF_8).build();
                    })
                    .decoder((response, type) -> json.readValue(response.body().asInputStream(), ComprovanteEslDTO.class))
                    .target(RodogarciaClient.class, "https://example.invalid");
            var result = client.buscarComprovanteCliente("Bearer token-cliente", CTE);
            assertEquals("https://example.invalid" + esperada + "?cte_key=" + CTE, request.get().url());
            assertEquals(List.of("Bearer token-cliente"), List.copyOf(request.get().headers().get("Authorization")));
            assertEquals(Request.HttpMethod.GET, request.get().httpMethod());
            assertEquals(CTE, result.data().get(0).freight().cteKey());
            assertNotNull(result.data().get(0).createdAt());
            client.buscarComprovante("Bearer token-documental", CTE);
            assertEquals("https://example.invalid/api/freight_delivery_receipts?cte_key=" + CTE, request.get().url());
        }
    }
}
