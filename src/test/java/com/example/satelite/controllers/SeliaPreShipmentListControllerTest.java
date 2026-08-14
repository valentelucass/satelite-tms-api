package com.example.satelite.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.selia.SeliaPlpRateLimitService;
import com.example.satelite.services.selia.SeliaPreShipmentListService;

class SeliaPreShipmentListControllerTest {

    @Test
    void deveResponder401SemExporChaveConfigurada() throws Exception {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        SeliaPreShipmentListService service = new SeliaPreShipmentListService(repository);
        ReflectionTestUtils.setField(service, "logisticProviderApiKey", "segredo-que-nao-pode-vazar");
        ReflectionTestUtils.setField(service, "plpEnabled", true);
        MockMvc mvc = criarMvcComProtecao(service, 10);

        mvc.perform(post("/api/selia/intelipost/pre-shipment-list")
                        .header("logistic-provider-api-key", "invalida")
                        .contentType("application/json")
                        .content("""
                                {"intelipost_pre_shipment_list":2970,"shipment_order_array":[]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.messages[0].text").value("Não autorizado."));
    }

    @Test
    void deveManterReplayIdempotenteAtrasDoRateLimit() throws Exception {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        LogIntegracaoModel existente = LogIntegracaoModel.builder()
                .id(700L)
                .intelipostPreShipmentList(2970L)
                .logisticsProviderShipmentList(700L)
                .dataProcessamento(LocalDateTime.of(2026, 8, 14, 10, 0))
                .build();
        org.mockito.Mockito.when(repository.findTopBySistemaDestinoAndIntelipostPreShipmentListOrderByDataProcessamentoAscIdAsc(
                "SELIA_PLP", 2970L
        )).thenReturn(Optional.of(existente));
        SeliaPreShipmentListService service = new SeliaPreShipmentListService(repository);
        ReflectionTestUtils.setField(service, "logisticProviderApiKey", "segredo-que-nao-pode-vazar");
        ReflectionTestUtils.setField(service, "plpEnabled", true);
        MockMvc mvc = criarMvcComProtecao(service, 2);

        mvc.perform(post("/api/selia/intelipost/pre-shipment-list")
                        .header("logistic-provider-api-key", "segredo-que-nao-pode-vazar")
                        .contentType("application/json")
                        .content(requisicaoValida()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
        mvc.perform(post("/api/selia/intelipost/pre-shipment-list")
                        .header("logistic-provider-api-key", "segredo-que-nao-pode-vazar")
                        .contentType("application/json")
                        .content(requisicaoValida()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.Mockito.any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAll(org.mockito.Mockito.any());
    }

    private MockMvc criarMvcComProtecao(SeliaPreShipmentListService service, int maximoPorChave) {
        SeliaPlpRateLimitService rateLimit = new SeliaPlpRateLimitService(
                true, 60_000, maximoPorChave, 10, 100, Clock.systemUTC()
        );
        SeliaPlpRateLimitInterceptor interceptor = new SeliaPlpRateLimitInterceptor(service, rateLimit);
        return MockMvcBuilders.standaloneSetup(new SeliaPreShipmentListController(service))
                .addInterceptors(interceptor)
                .build();
    }

    private String requisicaoValida() {
        return """
                {
                  "intelipost_pre_shipment_list": 2970,
                  "shipment_order_array": [
                    {
                      "order_number": "PEDIDO-123",
                      "shipment_order_volume_array": [
                        {
                          "shipment_order_volume_number": "VOLUME-456",
                          "shipment_order_volume_invoice_array": [
                            {"invoice_key": "35260612345678000123550010000012341000012345"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
    }
}
