package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ResultadoIntegracao;

class EtlResilienciaServiceTest {

    private EtlResilienciaService service;

    @BeforeEach
    void setUp() {
        service = new EtlResilienciaService();
        ReflectionTestUtils.setField(service, "backoffErroTransitorioMs", 0L);
    }

    @Test
    void deveRetentarErroHttpTransitorioAteSucesso() {
        LogIntegracaoModel logIntegracao = new LogIntegracaoModel();
        AtomicInteger chamadas = new AtomicInteger();

        ResultadoRegistro resultado = service.processarOcorrenciaComRetentativas(
                "VEDACIT",
                "nf-123",
                logIntegracao,
                () -> {
                    int tentativa = chamadas.incrementAndGet();
                    if (tentativa == 1) {
                        marcarErroDestino(logIntegracao, "HTTP 502 Bad Gateway", tentativa);
                        return ResultadoRegistro.ERRO;
                    }
                    return ResultadoRegistro.ENVIADO;
                }
        );

        assertEquals(ResultadoRegistro.ENVIADO, resultado);
        assertEquals(2, chamadas.get());
    }

    @Test
    void deveDesistirNaTerceiraFalhaHttpTransitoria() {
        LogIntegracaoModel logIntegracao = new LogIntegracaoModel();
        AtomicInteger chamadas = new AtomicInteger();

        ResultadoRegistro resultado = service.processarOcorrenciaComRetentativas(
                "VEDACIT",
                "nf-123",
                logIntegracao,
                () -> {
                    int tentativa = chamadas.incrementAndGet();
                    marcarErroDestino(logIntegracao, "HTTP 429 Too Many Requests", tentativa);
                    return ResultadoRegistro.ERRO;
                }
        );

        assertEquals(ResultadoRegistro.JA_PROCESSADO, resultado);
        assertEquals(3, chamadas.get());
    }

    @Test
    void deveMarcarFalhaInfraestruturaNaTerceiraFalhaHttp502() {
        LogIntegracaoModel logIntegracao = new LogIntegracaoModel();
        AtomicInteger chamadas = new AtomicInteger();

        ResultadoRegistro resultado = service.processarOcorrenciaComRetentativas(
                "VEDACIT",
                "nf-123",
                logIntegracao,
                () -> {
                    int tentativa = chamadas.incrementAndGet();
                    marcarErroDestino(logIntegracao, "HTTP 502 Bad Gateway", tentativa);
                    return ResultadoRegistro.ERRO;
                }
        );

        assertEquals(ResultadoRegistro.ERRO_INFRAESTRUTURA, resultado);
        assertEquals(3, chamadas.get());
    }

    @Test
    void naoDeveClassificarHttp429ComoFalhaInfraestrutura() {
        LogIntegracaoModel logIntegracao = new LogIntegracaoModel();
        marcarErroDestino(logIntegracao, "HTTP 429 Too Many Requests", 1);

        assertFalse(service.falhaInfraestruturaRegistrada(logIntegracao));
    }

    @Test
    void naoDeveRetentarErroNaoTransitorio() {
        LogIntegracaoModel logIntegracao = new LogIntegracaoModel();
        AtomicInteger chamadas = new AtomicInteger();

        ResultadoRegistro resultado = service.processarOcorrenciaComRetentativas(
                "VEDACIT",
                "nf-123",
                logIntegracao,
                () -> {
                    chamadas.incrementAndGet();
                    marcarErroDestino(logIntegracao, "Erro de validação do payload", 1);
                    return ResultadoRegistro.ERRO;
                }
        );

        assertEquals(ResultadoRegistro.ERRO, resultado);
        assertEquals(1, chamadas.get());
    }

    private void marcarErroDestino(LogIntegracaoModel logIntegracao, String mensagem, int tentativa) {
        logIntegracao.setStatusDados(ResultadoIntegracao.STATUS_ERRO_DESTINO);
        logIntegracao.setMensagemErroDados(mensagem);
        logIntegracao.setTentativasDados(tentativa);
    }
}
