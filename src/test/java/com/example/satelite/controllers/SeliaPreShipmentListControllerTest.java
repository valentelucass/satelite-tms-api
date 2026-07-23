package com.example.satelite.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.selia.SeliaPreShipmentListService;

class SeliaPreShipmentListControllerTest {

    @Test
    void deveResponder401SemExporChaveConfigurada() throws Exception {
        LogIntegracaoRepository repository = org.mockito.Mockito.mock(LogIntegracaoRepository.class);
        SeliaPreShipmentListService service = new SeliaPreShipmentListService(repository);
        ReflectionTestUtils.setField(service, "logisticProviderApiKey", "segredo-que-nao-pode-vazar");
        ReflectionTestUtils.setField(service, "plpEnabled", true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SeliaPreShipmentListController(service)).build();

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
}
