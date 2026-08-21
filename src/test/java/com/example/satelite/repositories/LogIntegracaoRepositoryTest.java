package com.example.satelite.repositories;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class LogIntegracaoRepositoryTest {

    @Test
    void quarentenaDeveConsiderarSomenteOEstadoMaisRecentePorCteOuOcorrencia() throws NoSuchMethodException {
        Method metodo = LogIntegracaoRepository.class.getMethod(
                "findErrosManuais",
                List.class,
                Pageable.class
        );
        Query query = metodo.getAnnotation(Query.class);

        assertTrue(query.value().contains("COALESCE(l.arquivado, false) = false"));
        assertTrue(query.value().contains("AND NOT EXISTS"));
        assertTrue(query.value().contains("posterior.chaveCte = l.chaveCte"));
        assertTrue(query.value().contains("posterior.occurrenceId = l.occurrenceId"));
        assertTrue(query.value().contains("posterior.dataProcessamento > l.dataProcessamento"));
        assertTrue(query.countQuery().contains("AND NOT EXISTS"));
        assertTrue(query.countQuery().contains("COALESCE(l.arquivado, false) = false"));
        assertTrue(query.countQuery().contains("posterior.chaveCte = l.chaveCte"));
        assertTrue(query.countQuery().contains("posterior.occurrenceId = l.occurrenceId"));
    }

    @Test
    void reprocessamentoManualDeveIgnorarErrosHistoricosComEstadoPosterior() throws NoSuchMethodException {
        Method metodo = LogIntegracaoRepository.class.getMethod("resetarQuarentenaByDestino", String.class);
        Query query = metodo.getAnnotation(Query.class);

        assertTrue(query.value().contains("AND NOT EXISTS"));
        assertTrue(query.value().contains("posterior.chaveCte = l.chaveCte"));
        assertTrue(query.value().contains("posterior.occurrenceId = l.occurrenceId"));
    }
}
