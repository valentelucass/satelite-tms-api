package com.example.satelite.services.selia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;

class SeliaPlpCorrelationServiceTest {
    private final LogIntegracaoRepository repository = mock(LogIntegracaoRepository.class);
    private final SeliaPlpCorrelationService service = new SeliaPlpCorrelationService(repository);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingKeyDoesNotQueryDatabase(String key) {
        assertTrue(service.buscarPorChaveNfe(key).isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void retainsDistinctVolumesFromLatestListWithoutInventingIdentifiers() {
        when(repository.findBySistemaDestinoAndChaveNfeAndStatusOrderByDataProcessamentoDescIdDesc(anyString(), anyString(), anyString()))
                .thenReturn(List.of(row(2L, " order ", " 1 "), row(2L, "order", "1"),
                        row(2L, "order", "2"), row(2L, null, "3"), row(2L, "order", " "), row(1L, "old", "1")));
        var result = service.buscarPorChaveNfe(" unit-nfe ");
        assertEquals(List.of(new SeliaPlpCorrelationService.IdentificacaoEntrega("order", "1"),
                new SeliaPlpCorrelationService.IdentificacaoEntrega("order", "2")), result);
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        verify(repository).findBySistemaDestinoAndChaveNfeAndStatusOrderByDataProcessamentoDescIdDesc("SELIA_PLP_MAP", "unit-nfe", "ACEITO_PLP");
    }

    @Test
    void absentCorrelationDoesNotFabricateOrder() { assertTrue(service.buscarPorChaveNfe("unit-nfe").isEmpty()); }

    private LogIntegracaoModel row(long plp, String order, String volume) {
        return LogIntegracaoModel.builder().intelipostPreShipmentList(plp).orderNumber(order).volumeNumber(volume).build();
    }
}
