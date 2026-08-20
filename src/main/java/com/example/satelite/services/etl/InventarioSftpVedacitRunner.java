package com.example.satelite.services.etl;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpInventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Inventário somente leitura do SFTP comparado com o último estado da auditoria. */
@Component
@Order(-13)
@ConditionalOnProperty(name = "vedacit.sftp-inventory.enabled", havingValue = "true")
public class InventarioSftpVedacitRunner implements CommandLineRunner, ExitCodeGenerator {
    private static final Logger log = LoggerFactory.getLogger(InventarioSftpVedacitRunner.class);

    private final VedacitSftpClient sftpClient;
    private final LogIntegracaoRepository repository;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public InventarioSftpVedacitRunner(
            VedacitSftpClient sftpClient,
            LogIntegracaoRepository repository,
            ConfigurableApplicationContext context
    ) {
        this.sftpClient = sftpClient;
        this.repository = repository;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            VedacitSftpInventory inventario = sftpClient.listarInventarioComprovantes();
            int enviados = 0, elegiveis = 0, aguardandoXml = 0, bloqueados = 0, semAuditoria = 0;
            for (var documento : inventario.documentosValidos()) {
                var ultimo = repository.findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc(
                        "VEDACIT", documento.chaveCte()
                );
                if (ultimo.isEmpty()) {
                    semAuditoria++;
                    continue;
                }
                LogIntegracaoModel logIntegracao = ultimo.get();
                if (ResultadoIntegracao.STATUS_SUCESSO.equals(logIntegracao.getStatusCanhoto())) {
                    enviados++;
                } else if (!ResultadoIntegracao.STATUS_SUCESSO.equals(logIntegracao.getStatusDados())) {
                    aguardandoXml++;
                } else if (bloqueado(logIntegracao)) {
                    bloqueados++;
                } else {
                    elegiveis++;
                }
            }
            log.info(
                    "[INVENTARIO] [VEDACIT][SFTP] arquivos_validos={} | rejeitados={} | enviados_confirmados={} | elegiveis={} | aguardando_xml={} | bloqueados={} | sem_auditoria={}",
                    inventario.documentosValidos().size(), inventario.rejeitados().size(), enviados, elegiveis,
                    aguardandoXml, bloqueados, semAuditoria
            );
            exitCode = 0;
        } catch (Exception e) {
            exitCode = 1;
            log.error("[FALHA] [VEDACIT][SFTP] Inventário somente leitura.", e);
        } finally {
            System.exit(SpringApplication.exit(context, () -> exitCode));
        }
    }

    private boolean bloqueado(LogIntegracaoModel registro) {
        String classificacao = registro.getCanhotoClassificacaoOperacional();
        return ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(classificacao)
                || ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_ORIGEM.name().equals(classificacao)
                || ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(classificacao);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
