package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.satelite.models.EslRequestTelemetryModel;
import com.example.satelite.repositories.EslRequestTelemetryRepository;

@Service
public class EslRequestTelemetryService implements EslRequestTelemetryRecorder {

    private static final Logger log = LoggerFactory.getLogger(EslRequestTelemetryService.class);

    private final EslRequestTelemetryRepository repository;

    public EslRequestTelemetryService(EslRequestTelemetryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void registrar(EslRequestContext contexto, Integer statusHttp, int tentativa, boolean retry, long duracaoMs) {
        try {
            repository.save(EslRequestTelemetryModel.builder()
                    .dataEvento(LocalDateTime.now(ZoneOffset.UTC))
                    .origem(contexto.origem())
                    .destino(contexto.destino())
                    .rota(contexto.rota())
                    .template(contexto.template())
                    .statusHttp(statusHttp)
                    .tentativa(Math.max(1, tentativa))
                    .retry(retry)
                    .fallback(contexto.fallback())
                    .cacheStatus(contexto.cacheStatus())
                    .duracaoMs(Math.max(0, duracaoMs))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Falha ao registrar telemetria de consumo ESL; a chamada de integracao nao sera interrompida. causa={}",
                    e.getClass().getSimpleName());
        }
    }
}
