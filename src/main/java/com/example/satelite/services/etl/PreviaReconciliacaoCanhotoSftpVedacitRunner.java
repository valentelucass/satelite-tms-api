package com.example.satelite.services.etl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.vedacit.VedacitCteCanhotoReconciliationService;

/** Prévia somente leitura: não chama SOAP nem grava auditoria. */
@Component
@Order(-12)
@ConditionalOnProperty(name = "vedacit.sftp-receipt-reconciliation-preview.enabled", havingValue = "true")
public class PreviaReconciliacaoCanhotoSftpVedacitRunner implements CommandLineRunner, ExitCodeGenerator {
    private static final Logger log = LoggerFactory.getLogger(PreviaReconciliacaoCanhotoSftpVedacitRunner.class);
    private final LogIntegracaoRepository repository;
    private final VedacitCteCanhotoReconciliationService reconciliationService;
    private final ConfigurableApplicationContext context;
    @Value("${vedacit.sftp-receipt-reconciliation-preview.max-items:100}")
    private int limiteConfigurado;
    private int exitCode;

    public PreviaReconciliacaoCanhotoSftpVedacitRunner(
            LogIntegracaoRepository repository,
            VedacitCteCanhotoReconciliationService reconciliationService,
            ConfigurableApplicationContext context
    ) {
        this.repository = repository;
        this.reconciliationService = reconciliationService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            int limite = Math.max(1, Math.min(limiteConfigurado, 500));
            List<LogIntegracaoModel> candidatos = repository.findCanhotosPendentesFotoVedacit(PageRequest.of(0, limite));
            int encontrados = 0;
            for (LogIntegracaoModel candidato : candidatos) {
                var decisao = reconciliationService.reconciliar(candidato.getChaveNfe(), candidato.getChaveCte());
                if (decisao.encontrada()) encontrados++;
                log.info("📋 [VEDACIT][PRÉVIA] id={} nfe={} cte_original={} cte_ftp={} tipo={} decisão={} motivo={}",
                        candidato.getId(), candidato.getChaveNfe(), candidato.getChaveCte(), decisao.chaveCteEfetiva(),
                        decisao.tipo(), decisao.encontrada() ? "ELEGIVEL" : "PENDENTE", decisao.motivo());
            }
            log.info("📋 [VEDACIT][PRÉVIA] finalizada: candidatos={} elegíveis={}. Nenhum SOAP ou status foi alterado.", candidatos.size(), encontrados);
            exitCode = 0;
        } catch (Exception e) {
            exitCode = 1;
            log.error("💥 [VEDACIT][PRÉVIA] falha técnica; nenhum status foi alterado.", e);
        } finally {
            int codigo = SpringApplication.exit(context, () -> exitCode);
            System.exit(codigo);
        }
    }

    @Override public int getExitCode() { return exitCode; }
}
