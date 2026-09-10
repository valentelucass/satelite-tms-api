package com.example.satelite.services.etl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClientFactory;
import com.example.satelite.repositories.WorkSftpClientesAuditoriaRepository;

/** Runner headless de ciclo único. Cada perfil mantém conexão, inventário e fila próprios. */
@Component
@Order(-30)
@ConditionalOnProperty(name = "work.sftp-clientes.enabled", havingValue = "true")
public class WorkSftpClientesRunner implements CommandLineRunner, ExitCodeGenerator {
    private static final Logger log = LoggerFactory.getLogger(WorkSftpClientesRunner.class);
    private final VedacitSftpClientFactory clientes;
    private final EtlRepescagemService repescagem;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final WorkSftpClientesAuditoriaRepository auditoria;
    private int exitCode;

    @Autowired
    private OrquestradorEtlService orquestrador;

    public WorkSftpClientesRunner(VedacitSftpClientFactory clientes, EtlRepescagemService repescagem,
            Environment environment, ConfigurableApplicationContext context,
            WorkSftpClientesAuditoriaRepository auditoria) {
        this.clientes = clientes; this.repescagem = repescagem; this.environment = environment; this.context = context; this.auditoria = auditoria;
    }

    @Override public void run(String... args) {
        exitCode = executarCiclo();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    int executarCiclo() {
        Instant inicio = Instant.now(); int falhas = 0, arquivos = 0, enviados = 0, pendentes = 0, bloqueios = 0, timeouts = 0;
        try {
            validarModoExclusivo();
            int limiteGlobal = inteiro("WORK_SFTP_CLIENTES_MAX_ITEMS", 100, 1, 500);
            long pausa = inteiro("WORK_SFTP_CLIENTES_INTERVAL_MS", 1000, 0, 60000);
            var perfis = clientes.criarClientesHabilitados();
            if (perfis.stream().anyMatch(perfil -> "VEDACIT".equals(perfil.identificador()))
                    && Boolean.TRUE.equals(environment.getProperty("WORK_SFTP_CLIENTES_XML_ENABLED", Boolean.class, false))) {
                if (!Boolean.TRUE.equals(environment.getProperty("SFTP_RODOGARCIA_ENABLED", Boolean.class, false)))
                    throw new IllegalStateException("Etapa XML exige a fonte SFTP habilitada");
                var xml = orquestrador.executarXmlVedacit();
                if (xml.erroCritico() || xml.erros() > 0) falhas++;
                log.info("[WORK-SFTP-CLIENTES][XML] paginas={} recebidos={} enviados={} ja_processados={} erros={}",
                        xml.paginasProcessadas(), xml.recebidos(), xml.enviados(), xml.jaProcessados(), xml.erros());
            }
            for (VedacitSftpClientFactory.ClienteSftp perfil : perfis) {
                Instant inicioCliente = Instant.now();
                LocalDateTime inicioAuditoria = LocalDateTime.now();
                boolean conectado = false;
                try {
                    // Limite de cada lote; o serviço continua até esgotar os elegíveis da passagem.
                    int limiteCliente = Math.min(limiteGlobal, perfil.limiteItensPorCiclo());
                    VedacitSftpClient sftp = perfil.cliente();
                    sftp.verificarDisponibilidade();
                    conectado = true;
                    var inventario = sftp.listarInventarioComprovantes();
                    var resultado = repescagem.processarClienteSftpVedacit(perfil.identificador(), inventario, sftp, limiteCliente, pausa);
                    arquivos += resultado.inventario().arquivos(); enviados += resultado.processamento().enviados(); pendentes += resultado.processamento().pendentes();
                    long bloqueiosCliente = repescagem.contarClassificacaoCanhotoVedacit(perfil.identificador(), "BLOQUEADO_ORIGEM")
                            + repescagem.contarClassificacaoCanhotoVedacit(perfil.identificador(), "BLOQUEADO_DESTINO");
                    long timeoutsCliente = repescagem.contarClassificacaoCanhotoVedacit(perfil.identificador(), "TIMEOUT_AMBIGUO");
                    bloqueios += bloqueiosCliente; timeouts += timeoutsCliente;
                    boolean processamentoFalhou = resultado.processamento().erros() > 0;
                    boolean auditoriaRegistrada = registrarCiclo(perfil.identificador(), inicioAuditoria, "OK", processamentoFalhou ? "FALHA" : "CONCLUIDO",
                            resultado.inventario().arquivos(), inventario.rejeitados().size(), resultado.processamento().selecionados(),
                            resultado.processamento().enviados(), resultado.processamento().pendentes(), resultado.saldo(), bloqueiosCliente,
                            timeoutsCliente, Duration.between(inicioCliente, Instant.now()).toMillis());
                    if (processamentoFalhou || !auditoriaRegistrada) falhas++;
                    log.info("[WORK-SFTP-CLIENTES] cliente={} conexao=OK arquivos_validos={} rejeitados_auditados={} selecionados={} enviados={} pendentes={} erros={} saldo={} duracao_ms={}",
                            perfil.identificador(), resultado.inventario().arquivos(), inventario.rejeitados().size(), resultado.processamento().selecionados(), resultado.processamento().enviados(),
                            resultado.processamento().pendentes(), resultado.processamento().erros(), resultado.saldo(), Duration.between(inicioCliente, Instant.now()).toMillis());
                } catch (Exception e) {
                    falhas++;
                    String conexao = conectado ? "OK" : "FALHA";
                    registrarCiclo(perfil.identificador(), inicioAuditoria, conexao, "FALHA", 0, 0, 0, 0, 0, 0, 0, 0,
                            Duration.between(inicioCliente, Instant.now()).toMillis());
                    log.error("[WORK-SFTP-CLIENTES] cliente={} conexao={} duracao_ms={} motivo={}", perfil.identificador(), conexao,
                            Duration.between(inicioCliente, Instant.now()).toMillis(), resumir(e));
                }
            }
            exitCode = falhas == 0 ? 0 : 1;
            log.info("[WORK-SFTP-CLIENTES][RESUMO] clientes_falhos={} arquivos_validos={} enviados={} pendentes={} bloqueios={} timeouts_ambiguos={} duracao_ms={} proximo_ciclo=PM2_30_MIN",
                    falhas, arquivos, enviados, pendentes, bloqueios, timeouts, Duration.between(inicio, Instant.now()).toMillis());
        } catch (Exception e) {
            exitCode = 2;
            log.error("[WORK-SFTP-CLIENTES] falha crítica: {}", resumir(e));
        }
        return exitCode;
    }

    @Override public int getExitCode() { return exitCode; }
    private void validarModoExclusivo() {
        if (!environment.getProperty("VEDACIT_SFTP_RECEIPT_ONLY", Boolean.class, false))
            throw new IllegalStateException("WORK-SFTP-CLIENTES exige VEDACIT_SFTP_RECEIPT_ONLY=true");
    }
    private int inteiro(String chave, int padrao, int minimo, int maximo) {
        int valor = Integer.parseInt(environment.getProperty(chave, String.valueOf(padrao)).trim());
        if (valor < minimo || valor > maximo) throw new IllegalArgumentException("Configuração fora do limite: " + chave);
        return valor;
    }
    private String resumir(Exception e) { return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
    private boolean registrarCiclo(String cliente, LocalDateTime inicio, String conexao, String status, int validos, int rejeitados,
            int selecionados, int enviados, int pendentes, long saldo, long bloqueios, long timeouts, long duracao) {
        try {
            auditoria.registrar(new WorkSftpClientesAuditoriaRepository.Ciclo(cliente, inicio, LocalDateTime.now(), conexao, status,
                    validos, rejeitados, selecionados, enviados, pendentes, saldo, bloqueios, timeouts, duracao));
            return true;
        } catch (Exception e) {
            log.error("[WORK-SFTP-CLIENTES] cliente={} falha ao registrar auditoria do ciclo: {}", cliente, resumir(e));
            return false;
        }
    }
}
