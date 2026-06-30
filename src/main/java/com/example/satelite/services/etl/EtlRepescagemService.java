package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;

@Service
public class EtlRepescagemService {

    private static final Logger log = LoggerFactory.getLogger(EtlRepescagemService.class);

    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_VEDACIT = "VEDACIT";

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final EtlRegistroService etlRegistroService;
    private final EtlEstadoIntegracaoService etlEstadoIntegracaoService;
    private final PpgIntegrationService ppgIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;

    @Value("${RODOGARCIA_TOKEN_PPG}")
    private String tokenPpgEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT}")
    private String tokenVedacitEsl;

    @Value("${ETL_REPESCAGEM_INTERVAL_MS:10000}")
    private long intervaloEntreRegistrosMs = 10000;

    public EtlRepescagemService(
            LogIntegracaoRepository logIntegracaoRepository,
            EtlRegistroService etlRegistroService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService
    ) {
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.etlRegistroService = etlRegistroService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
    }

    public void executarRepescagem(LocalDateTime inicioCiclo) {
        if (inicioCiclo == null) {
            log.warn("⏭️ Repescagem ignorada: início do ciclo não informado.");
            return;
        }

        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosManuaisDesde(inicioCiclo);
        if (registros == null || registros.isEmpty()) {
            log.info("🎣 Repescagem: nenhum erro definitivo encontrado para o ciclo atual.");
            return;
        }

        log.warn("🎣 Repescagem lenta iniciada para {} registro(s) com erro definitivo neste ciclo.", registros.size());
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            reprocessarRegistro(registro);

            if (indice < registros.size() - 1 && !pausarEntreRegistros()) {
                log.warn("⏹️ Repescagem interrompida antes de concluir todos os registros.");
                return;
            }
        }

        log.warn("🎣 Repescagem lenta finalizada.");
    }

    private void reprocessarRegistro(LogIntegracaoModel registro) {
        String destino = normalizarDestino(registro);
        if (destino == null) {
            log.warn(
                    "⏭️ Repescagem ignorou log sem destino válido. id={} nf={}",
                    registro.getId(),
                    registro.getChaveNfe()
            );
            return;
        }

        log.warn(
                "🎣 [{}] NF {}: iniciando tentativa final de repescagem. tentativas_dados={} tentativas_canhoto={}",
                destino,
                registro.getChaveNfe(),
                valorTentativas(registro.getTentativasDados()),
                valorTentativas(registro.getTentativasCanhoto())
        );

        try {
            ResultadoRegistro resultado = etlRegistroService.reprocessarLogExistente(
                    destino,
                    headerAuth(destino),
                    registro,
                    processadorDestino(destino)
            );
            log.warn("🎣 [{}] NF {}: resultado da repescagem={}", destino, registro.getChaveNfe(), resultado);
        } catch (Exception e) {
            log.error(
                    "❌ [{}] NF {}: falha inesperada na repescagem - {}",
                    destino,
                    registro.getChaveNfe(),
                    e.getMessage(),
                    e
            );
        }
    }

    private ProcessadorDestino processadorDestino(String destino) {
        if (DESTINO_PPG.equals(destino)) {
            return (ocorrencia, comprovante, logIntegracao) ->
                    ppgIntegrationService.processarOcorrencia(ocorrencia, comprovante);
        }

        return (ocorrencia, comprovante, logIntegracao) -> vedacitIntegrationService.processarOcorrencia(
                ocorrencia,
                comprovante,
                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusDados()),
                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusCanhoto())
        );
    }

    private String headerAuth(String destino) {
        return "Bearer " + (DESTINO_PPG.equals(destino) ? tokenPpgEsl : tokenVedacitEsl);
    }

    private String normalizarDestino(LogIntegracaoModel registro) {
        if (registro == null || registro.getSistemaDestino() == null) {
            return null;
        }

        String destino = registro.getSistemaDestino().trim().toUpperCase(Locale.ROOT);
        if (DESTINO_PPG.equals(destino) || DESTINO_VEDACIT.equals(destino)) {
            return destino;
        }

        return null;
    }

    private boolean pausarEntreRegistros() {
        long esperaMs = Math.max(0, intervaloEntreRegistrosMs);
        if (esperaMs <= 0) {
            return true;
        }

        try {
            Thread.sleep(esperaMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas != null ? tentativas : 0;
    }
}
