package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.example.satelite.repositories.LogIntegracaoRepository;

@Service
public class AuditoriaDataHoraService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaDataHoraService.class);
    private static final ZoneId FUSO_HORARIO_OPERACIONAL = ZoneId.of("America/Sao_Paulo");

    private final LogIntegracaoRepository logIntegracaoRepository;

    public AuditoriaDataHoraService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    public LocalDateTime agora() {
        try {
            LocalDateTime dataHoraBanco = logIntegracaoRepository.buscarDataHoraServidor();
            if (dataHoraBanco != null) {
                return dataHoraBanco;
            }
        } catch (DataAccessException e) {
            log.warn("Não foi possível consultar a hora do servidor SQL para auditoria; usando relógio local.");
        }

        return LocalDateTime.now(FUSO_HORARIO_OPERACIONAL);
    }
}
