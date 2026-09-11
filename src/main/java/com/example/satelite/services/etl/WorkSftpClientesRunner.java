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
        try {
            validarModoExclusivo();
            int maximo = inteiro("WORK_SFTP_CLIENTES_MAX_ITEMS", 100, 1, 500);
            int limite = inteiro("WORK_SFTP_CLIENTES_TURN_ITEMS", 10, 1, 100);
            long duracao = inteiro("WORK_SFTP_CLIENTES_TURN_MS", 120000, 1000, 600000);
            long pausa = inteiro("WORK_SFTP_CLIENTES_INTERVAL_MS", 1000, 0, 60000);
            auditoria.validarEstrutura();
            var ciclos = clientes.criarClientesHabilitados().stream().map(CicloCliente::new).toList();
            Runnable rodada = () -> ciclos.forEach(ciclo -> ciclo.processar(Math.min(limite, maximo), duracao, pausa));
            ResultadoDestino xml = ResultadoDestino.vazio("VEDACIT");
            boolean xmlHabilitado = ciclos.stream().anyMatch(c -> "VEDACIT".equals(c.perfil.identificador()))
                    && Boolean.TRUE.equals(environment.getProperty("WORK_SFTP_CLIENTES_XML_ENABLED", Boolean.class, false));
            if (xmlHabilitado) {
                if (!environment.getProperty("SFTP_RODOGARCIA_ENABLED", Boolean.class, false))
                    throw new IllegalStateException("Etapa XML exige a fonte SFTP habilitada");
                TurnoEtl turno = new TurnoEtl(limite, duracao, () -> {
                    ciclos.forEach(c -> c.passagem.revisarInventario());
                    rodada.run();
                });
                try { xml = orquestrador.executarXmlVedacit(turno); }
                catch (Exception e) { xml = xml.comErroCritico("XML: " + resumir(e)); }
                ciclos.forEach(c -> c.passagem.revisarInventario());
                log.info("[WORK-SFTP-CLIENTES][XML] paginas={} recebidos={} enviados={} ja_processados={} erros={}",
                        xml.paginasProcessadas(), xml.recebidos(), xml.enviados(), xml.jaProcessados(), xml.erros());
            }
            do {
                rodada.run();
            } while (!Thread.currentThread().isInterrupted() && ciclos.stream().anyMatch(c -> !c.falhou && c.passagem.temMais()));
            int falhos = 0;
            for (CicloCliente ciclo : ciclos) {
                ciclo.xmlHabilitado = "VEDACIT".equals(ciclo.perfil.identificador()) && xmlHabilitado;
                if (ciclo.xmlHabilitado) ciclo.xml = xml;
                if ("VEDACIT".equals(ciclo.perfil.identificador()) && (xml.erroCritico() || xml.erros() > 0)) {
                    ciclo.falhou = true;
                    if (ciclo.motivo == null) ciclo.motivo = "XML_RETIDO: Há falhas XML auditadas; consulte a etapa XML";
                }
                if (!ciclo.registrar() || ciclo.falhou) falhos++;
            }
            log.info("[WORK-SFTP-CLIENTES][RESUMO] clientes_falhos={} clientes={} xml_enviados={} xml_erros={}",
                    falhos, ciclos.size(), xml.enviados(), xml.erros());
            return exitCode = falhos == 0 && !Thread.currentThread().isInterrupted() ? 0 : 1;
        } catch (Exception e) {
            log.error("[WORK-SFTP-CLIENTES] falha crítica: {}", resumir(e));
            return exitCode = 2;
        }
    }

    private final class CicloCliente {
        final VedacitSftpClientFactory.ClienteSftp perfil;
        final EtlRepescagemService.PassagemSftp passagem = new EtlRepescagemService.PassagemSftp();
        final Instant inicio = Instant.now();
        final LocalDateTime inicioAuditoria = LocalDateTime.now();
        com.example.satelite.services.origem.sftp.vedacit.VedacitSftpInventory inventario;
        boolean conectado, conexaoTentada, falhou;
        int selecionados, enviados, pendentes, errosComprovante;
        boolean xmlHabilitado;
        ResultadoDestino xml = ResultadoDestino.vazio("VEDACIT");
        long saldo, bloqueios, timeouts;
        String motivo;
        CicloCliente(VedacitSftpClientFactory.ClienteSftp perfil) {
            this.perfil = perfil; passagem.limitada = true;
        }
        void processar(int limite, long duracao, long pausa) {
            if (falhou || passagem.suspensa || Thread.currentThread().isInterrupted()) return;
            try {
                if (!"VEDACIT".equals(perfil.identificador())) {
                    falhou = true;
                    motivo = "ADAPTADOR_AUSENTE: Cliente sem adaptador de destino neste worker";
                    return;
                }
                if (inventario == null) {
                    conexaoTentada = true;
                    perfil.cliente().verificarDisponibilidade(); conectado = true;
                    inventario = perfil.cliente().listarInventarioComprovantes();
                }
                int teto = Math.min(limite, perfil.limiteItensPorCiclo());
                var resultado = repescagem.processarClienteSftpVedacit(perfil.identificador(), inventario,
                        perfil.cliente(), teto, pausa, passagem, duracao);
                selecionados += resultado.processamento().selecionados();
                enviados += resultado.processamento().enviados();
                pendentes += resultado.processamento().pendentes();
                errosComprovante += resultado.processamento().erros();
                saldo = resultado.saldo();
                if (resultado.processamento().erros() > 0) motivo = passagem.motivoFalha == null
                        ? "PROCESSAMENTO: Falha de comprovante; consulte a auditoria do documento" : passagem.motivoFalha;
                // Erros individuais ficam auditados; só a proteção da passagem suspende novos itens.
                bloqueios = repescagem.contarClassificacaoCanhotoVedacit(perfil.identificador(), "BLOQUEADO_ORIGEM")
                        + repescagem.contarClassificacaoCanhotoVedacit(perfil.identificador(), "BLOQUEADO_DESTINO");
                timeouts = repescagem.contarClassificacaoCanhotoVedacit(perfil.identificador(), "TIMEOUT_AMBIGUO");
                log.info("[WORK-SFTP-CLIENTES][TURNO] cliente={} avaliados={} enviados={} pendentes={} erros={} saldo={}",
                        perfil.identificador(), resultado.processamento().selecionados(), resultado.processamento().enviados(),
                        resultado.processamento().pendentes(), resultado.processamento().erros(), saldo);
            } catch (Exception e) {
                falhou = true;
                selecionados = Math.max(selecionados, passagem.totalAvaliados);
                enviados = Math.max(enviados, passagem.totalEnviados);
                pendentes = Math.max(pendentes, passagem.totalPendentes);
                errosComprovante = Math.max(errosComprovante, passagem.totalErros);
                motivo = (conectado ? inventario == null ? "INVENTARIO: " : "BANCO_FILA_PROCESSAMENTO: " : "CONEXAO: ") + resumir(e);
                log.error("[WORK-SFTP-CLIENTES] cliente={} motivo={}", perfil.identificador(), motivo);
            }
        }
        boolean registrar() {
            falhou |= motivo != null || Thread.currentThread().isInterrupted();
            return registrarCiclo(perfil.identificador(), inicioAuditoria, conectado ? "OK" : conexaoTentada ? "FALHA" : "NAO_EXECUTADA",
                    falhou ? "FALHA" : "CONCLUIDO", inventario == null ? 0 : inventario.documentosValidos().size(),
                    inventario == null ? 0 : inventario.rejeitados().size(), selecionados, enviados, pendentes,
                    saldo, bloqueios, timeouts, Duration.between(inicio, Instant.now()).toMillis(),
                    xmlHabilitado, xml, errosComprovante, motivo);
        }
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
    private String resumir(Exception e) { return com.example.satelite.utils.FalhaIntegracaoSanitizada.resumir(e); }
    private boolean registrarCiclo(String cliente, LocalDateTime inicio, String conexao, String status, int validos, int rejeitados,
            int selecionados, int enviados, int pendentes, long saldo, long bloqueios, long timeouts, long duracao,
            boolean xmlHabilitado, ResultadoDestino xml, int errosComprovante, String motivo) {
        try {
            auditoria.registrar(new WorkSftpClientesAuditoriaRepository.Ciclo(cliente, inicio, LocalDateTime.now(), conexao, status,
                    validos, rejeitados, selecionados, enviados, pendentes, saldo, bloqueios, timeouts, duracao,
                    xmlHabilitado, xml.recebidos(), xml.enviados(), xml.jaProcessados(), xml.pendentesOrigem(), xml.erros(),
                    errosComprovante, motivo == null && Thread.currentThread().isInterrupted() ? "INTERROMPIDO: Ciclo interrompido" : motivo));
            return true;
        } catch (Exception e) {
            log.error("[WORK-SFTP-CLIENTES] cliente={} falha ao registrar auditoria do ciclo: {}", cliente, resumir(e));
            return false;
        }
    }
}
