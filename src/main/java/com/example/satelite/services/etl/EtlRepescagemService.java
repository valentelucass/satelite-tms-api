package com.example.satelite.services.etl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ppg.PpgIntegrationService;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.services.selia.SeliaIntegrationService;
import com.example.satelite.services.vedacit.VedacitIntegrationService;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocument;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpInventory;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpDocumentSource;

@Service
public class EtlRepescagemService {

    private static final Logger log = LoggerFactory.getLogger(EtlRepescagemService.class);
    private static final Logger logDetalheSftpVedacit = LoggerFactory.getLogger("satelite.vedacit.sftp.detail");
    /** Mantém a consulta muito abaixo do limite de 2.100 parâmetros do SQL Server. */
    private static final int MAXIMO_NFES_POR_CONSULTA_SQL = 500;
    private static final Comparator<LogIntegracaoModel> ORDEM_FILA_SFTP = (primeiro, segundo) -> {
        if (primeiro == segundo) return 0;
        if (primeiro == null) return 1;
        if (segundo == null) return -1;
        int porData = Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()).compare(
                primeiro.getDataProcessamento(), segundo.getDataProcessamento()
        );
        if (porData != 0) return porData;
        return Comparator.nullsLast(Comparator.<Long>naturalOrder()).compare(primeiro.getId(), segundo.getId());
    };

    private static final String DESTINO_PPG = "PPG";
    private static final String DESTINO_SELIA = "SELIA";
    private static final String DESTINO_VEDACIT = "VEDACIT";
    private static final String STATUS_ERRO_DESTINO = "ERRO_DESTINO";
    private static final Pattern CHAVE_CTE_NA_MENSAGEM = Pattern.compile("[?&](?:cte_)?key=([0-9]{44})");

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final EtlRegistroService etlRegistroService;
    private final EtlEstadoIntegracaoService etlEstadoIntegracaoService;
    private final PpgIntegrationService ppgIntegrationService;
    private final VedacitIntegrationService vedacitIntegrationService;
    private final SeliaIntegrationService seliaIntegrationService;
    private final SftpDocumentoLockService sftpDocumentoLockService;

    @Value("${RODOGARCIA_TOKEN_PPG}")
    private String tokenPpgEsl;

    @Value("${RODOGARCIA_TOKEN_VEDACIT}")
    private String tokenVedacitEsl;

    @Value("${RODOGARCIA_TOKEN_SELIA:}")
    private String tokenSeliaEsl;

    @Value("${ETL_REPESCAGEM_INTERVAL_MS:10000}")
    private long intervaloEntreRegistrosMs = 10000;

    @Value("${WORK_SFTP_CLIENTES_DRAIN_ENABLED:true}")
    private boolean drenarFilaSftp = true;

    @Value("${WORK_SFTP_CLIENTES_MAX_CONSECUTIVE_ERRORS:3}")
    private int limiteErrosConsecutivosSftp = 3;

    @Autowired
    public EtlRepescagemService(
            LogIntegracaoRepository logIntegracaoRepository,
            EtlRegistroService etlRegistroService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService,
            SeliaIntegrationService seliaIntegrationService,
            SftpDocumentoLockService sftpDocumentoLockService
    ) {
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.etlRegistroService = etlRegistroService;
        this.etlEstadoIntegracaoService = etlEstadoIntegracaoService;
        this.ppgIntegrationService = ppgIntegrationService;
        this.vedacitIntegrationService = vedacitIntegrationService;
        this.seliaIntegrationService = seliaIntegrationService;
        this.sftpDocumentoLockService = sftpDocumentoLockService;
    }

    public EtlRepescagemService(
            LogIntegracaoRepository logIntegracaoRepository,
            EtlRegistroService etlRegistroService,
            EtlEstadoIntegracaoService etlEstadoIntegracaoService,
            PpgIntegrationService ppgIntegrationService,
            VedacitIntegrationService vedacitIntegrationService
    ) {
        this(
                logIntegracaoRepository,
                etlRegistroService,
                etlEstadoIntegracaoService,
                ppgIntegrationService,
                vedacitIntegrationService,
                null,
                null
        );
    }

    /** Fluxo isolado do WORK-SFTP-CLIENTES: cliente, inventário e envio nunca se cruzam. */
    public ResultadoClienteSftpVedacit processarClienteSftpVedacit(
            String cliente,
            VedacitSftpInventory inventario,
            VedacitSftpDocumentSource fonteSftp,
            int limite,
            long intervaloEntreItensMs
    ) {
        String clienteSeguro = cliente == null ? "" : cliente.trim().toUpperCase(Locale.ROOT);
        if (!clienteSeguro.matches("[A-Z0-9_]+")) throw new IllegalArgumentException("Cliente SFTP inválido");
        VedacitSftpInventory seguro = inventario == null ? new VedacitSftpInventory(List.of(), List.of()) : inventario;
        ResultadoInventarioSftpVedacit materializacao = sincronizarInventarioSftpVedacit(clienteSeguro, seguro);
        Set<String> chavesNfe = new LinkedHashSet<>();
        for (VedacitSftpDocument documento : seguro.documentosValidos()) {
            if (documento != null && documento.chaveNfe() != null) {
                chavesNfe.add(documento.chaveNfe());
            }
        }
        List<String> nfes = normalizarChavesNfe(chavesNfe);
        if (nfes.isEmpty()) return new ResultadoClienteSftpVedacit(materializacao, new ResultadoReprocessamentoCanhotoVedacit(0,0,0,0,0), 0);
        int limiteSeguro = Math.max(1, limite);
        int enviados = 0, pendentes = 0, erros = 0, ignorados = 0;
        int errosConsecutivos = 0;
        Set<String> tentadas = new HashSet<>();
        boolean continuar = true;
        boolean filaNormalEsgotada = false;
        while (continuar && !Thread.currentThread().isInterrupted()) {
            List<String> restantes = nfes.stream().filter(nfe -> !tentadas.contains(nfe)).toList();
            List<LogIntegracaoModel> registros = buscarRegistrosSftpEmLotes(restantes, limiteSeguro,
                    lote -> logIntegracaoRepository.findCandidatosSftpPorClienteENfes(
                            clienteSeguro, lote, PageRequest.of(0, limiteSeguro))).stream()
                    .filter(registro -> !tentadas.contains(registro.getChaveNfe())).toList();
            if (registros.isEmpty()) { filaNormalEsgotada = true; break; }
            for (LogIntegracaoModel registro : registros) {
                // Uma passagem finita pelo inventário: ausência de arquivo ou lock não trava o dreno.
                if (!tentadas.add(registro.getChaveNfe())) continue;
                if (tentadas.size() > 1 && !pausarEntreRegistros(intervaloEntreItensMs)) {
                    continuar = false; break;
                }
                ResultadoRegistro resultado = processarComLockCliente(clienteSeguro, registro, fonteSftp);
                if (resultado == ResultadoRegistro.ENVIADO) enviados++;
                else if (resultado == ResultadoRegistro.PENDENTE_FOTO) pendentes++;
                else if (resultado.erro()) erros++;
                else ignorados++;
                errosConsecutivos = resultado.erro() ? errosConsecutivos + 1 : 0;
                if (errosConsecutivos >= Math.max(1, limiteErrosConsecutivosSftp)) {
                    log.warn("[WORK-SFTP-CLIENTES] cliente={} dreno suspenso após {} erros consecutivos.", clienteSeguro, errosConsecutivos);
                    continuar = false; break;
                }
            }
            if (!drenarFilaSftp) break;
        }
        // A quarentena técnica só inicia quando a fila normal do cliente ficou ociosa.
        if (filaNormalEsgotada && erros == 0 && !Thread.currentThread().isInterrupted()) {
            int limiteTecnicos = Math.min(10, limiteSeguro);
            List<LogIntegracaoModel> tecnicos = buscarRegistrosSftpEmLotes(
                    nfes.stream().filter(nfe -> !tentadas.contains(nfe)).toList(),
                    limiteTecnicos,
                    lote -> logIntegracaoRepository.findTecnicosSftpPorClienteENfes(
                            clienteSeguro, lote, PageRequest.of(0, limiteTecnicos)
                    )
            );
            int errosTecnicos = 0;
            for (int indice = 0; indice < tecnicos.size() && errosTecnicos < 3; indice++) {
                if ((!tentadas.isEmpty() || indice > 0) && !pausarEntreRegistros(intervaloEntreItensMs)) break;
                ResultadoRegistro resultado = processarComLockCliente(clienteSeguro, tecnicos.get(indice), fonteSftp);
                if (resultado == ResultadoRegistro.ENVIADO) enviados++;
                else if (resultado == ResultadoRegistro.PENDENTE_FOTO) pendentes++;
                else if (resultado.erro()) { erros++; errosTecnicos++; }
                else ignorados++;
            }
        }
        long saldo = contarNfesSftpEmLotes(
                nfes,
                lote -> logIntegracaoRepository.countNfesCandidatasSftpPorClienteENfes(clienteSeguro, lote)
        );
        return new ResultadoClienteSftpVedacit(materializacao,
                new ResultadoReprocessamentoCanhotoVedacit(enviados + pendentes + erros + ignorados, enviados, pendentes, erros, ignorados), saldo);
    }

    private ResultadoRegistro processarComLockCliente(String cliente, LogIntegracaoModel registro, VedacitSftpDocumentSource fonteSftp) {
        if (sftpDocumentoLockService == null) {
            throw new IllegalStateException("Lock SFTP Vedacit indisponível");
        }
        Optional<ResultadoRegistro> resultadoComLock = sftpDocumentoLockService.executarComLock(
                cliente, registro.getChaveNfe(), registro.getChaveCte(), () -> {
            registro.setSftpCliente(cliente);
            registrarOrigemSftpDoCanhoto(registro);
            return etlRegistroService.reprocessarCanhotoVedacitPorCte(registro, fonteSftp);
        });
        if (resultadoComLock.isPresent()) {
            return resultadoComLock.get();
        }
        log.warn("[WORK-SFTP-CLIENTES] cliente={} NF={} CT-e={}: lock ocupado; mantendo pendente para nova tentativa.",
                cliente, chaveResumida(registro.getChaveNfe()), chaveResumida(registro.getChaveCte()));
        return ResultadoRegistro.PENDENTE_FOTO;
    }

    private ResultadoInventarioSftpVedacit sincronizarInventarioSftpVedacit(String cliente, VedacitSftpInventory inventario) {
        int novos = 0, enviados = 0, existentes = 0;
        for (VedacitSftpDocument documento : inventario.documentosValidos()) {
            if (documento == null || documento.chaveNfe() == null || documento.chaveCte() == null
                    || !documento.chaveNfe().matches("\\d{44}") || !documento.chaveCte().matches("\\d{44}")) continue;
            Optional<LogIntegracaoModel> existente = logIntegracaoRepository
                    .findTopBySistemaDestinoAndSftpClienteAndChaveNfeAndChaveCteOrderByDataProcessamentoDescIdDesc(
                            DESTINO_VEDACIT, cliente, documento.chaveNfe(), documento.chaveCte());
            if (existente.isPresent()) {
                if (ResultadoIntegracao.STATUS_PENDENTE_ORIGEM.equals(existente.get().getStatusDados())) {
                    Optional<LogIntegracaoModel> legado = buscarConfirmacaoDados(cliente, documento);
                    if (legado.isPresent()) {
                        reconciliarRegistroSftpComLegado(existente.get(), documento, legado.get());
                        if (ResultadoIntegracao.STATUS_SUCESSO.equals(legado.get().getStatusCanhoto())) enviados++;
                        else existentes++;
                        continue;
                    }
                }
                if (ResultadoIntegracao.STATUS_SUCESSO.equals(existente.get().getStatusCanhoto())) enviados++;
                else { existentes++; promoverCandidatoSftpComDadosConfirmados(existente.get(), documento); }
                continue;
            }
            Optional<LogIntegracaoModel> legado = buscarConfirmacaoDados(cliente, documento);
            if (legado.isPresent()) {
                materializarLegadoArquivadoDoCliente(cliente, documento, legado.get());
                continue;
            }
            LogIntegracaoModel pendencia = LogIntegracaoModel.builder().sistemaDestino(DESTINO_VEDACIT).sftpCliente(cliente)
                    .chaveNfe(documento.chaveNfe()).chaveCte(documento.chaveCte()).status(ResultadoIntegracao.STATUS_PARCIAL)
                    .statusDados("PENDENTE_ORIGEM").statusCanhoto(ResultadoIntegracao.STATUS_PENDENTE_FOTO).canhotoOrigem("SFTP")
                    .canhotoReferencia(documento.caminhoRelativo()).tentativasDados(0).tentativasCanhoto(0)
                    .dataProcessamento(etlEstadoIntegracaoService.agoraAuditoria()).build();
            etlEstadoIntegracaoService.classificarCanhotoVedacit(pendencia, ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_ORIGEM);
            etlEstadoIntegracaoService.salvar(pendencia); novos++;
        }
        for (VedacitSftpInventory.DocumentoRejeitado rejeitado : inventario.rejeitados()) {
            if (rejeitado == null || rejeitado.caminhoRelativo() == null || rejeitado.caminhoRelativo().isBlank()) continue;
            LogIntegracaoModel registro = logIntegracaoRepository.findTopBySistemaDestinoAndSftpClienteAndCanhotoReferenciaOrderByDataProcessamentoDescIdDesc(DESTINO_VEDACIT, cliente, rejeitado.caminhoRelativo())
                    .orElseGet(() -> LogIntegracaoModel.builder().sistemaDestino(DESTINO_VEDACIT).sftpCliente(cliente).canhotoReferencia(rejeitado.caminhoRelativo()).chaveNfe(rejeitado.chaveNfe()).chaveCte(rejeitado.chaveCte()).tentativasDados(0).tentativasCanhoto(0).build());
            registrarRejeicaoSftp(registro, rejeitado);
        }
        return new ResultadoInventarioSftpVedacit(inventario.documentosValidos().size(), novos, enviados, existentes);
    }

    private Optional<LogIntegracaoModel> buscarConfirmacaoDados(String cliente, VedacitSftpDocument documento) {
        var ativos = logIntegracaoRepository.findConfirmacaoVedacitAtivaPorPar(
                cliente, documento.chaveNfe(), documento.chaveCte(), PageRequest.of(0, 1));
        if (!ativos.isEmpty()) return Optional.of(ativos.get(0));
        return logIntegracaoRepository.findLegadoVedacitArquivadoComDadosSucesso(documento.chaveNfe(), documento.chaveCte());
    }

    private void materializarLegadoArquivadoDoCliente(
            String cliente, VedacitSftpDocument documento, LogIntegracaoModel legado
    ) {
        boolean comprovanteJaEnviado = ResultadoIntegracao.STATUS_SUCESSO.equals(legado.getStatusCanhoto());
        boolean preservarRestricao = !comprovanteJaEnviado && (
                ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(legado.getCanhotoClassificacaoOperacional())
                || ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(legado.getCanhotoClassificacaoOperacional()));
        LogIntegracaoModel atual = LogIntegracaoModel.builder()
                .sistemaDestino(DESTINO_VEDACIT).sftpCliente(cliente)
                .chaveNfe(documento.chaveNfe()).chaveCte(documento.chaveCte())
                .status(comprovanteJaEnviado ? ResultadoIntegracao.STATUS_ENVIADO : ResultadoIntegracao.STATUS_PARCIAL)
                .statusDados(ResultadoIntegracao.STATUS_SUCESSO)
                .dataProcessamentoDados(legado.getDataProcessamentoDados())
                .dataProcessamentoCanhoto(legado.getDataProcessamentoCanhoto())
                .statusCanhoto(preservarRestricao ? legado.getStatusCanhoto()
                        : comprovanteJaEnviado ? ResultadoIntegracao.STATUS_SUCESSO : ResultadoIntegracao.STATUS_PENDENTE_FOTO)
                .mensagemErroCanhoto(preservarRestricao ? legado.getMensagemErroCanhoto() : null)
                .canhotoOrigem("SFTP").canhotoReferencia(documento.caminhoRelativo())
                .tentativasDados(0).tentativasCanhoto(0)
                .dataProcessamento(etlEstadoIntegracaoService.agoraAuditoria())
                .build();
        etlEstadoIntegracaoService.classificarCanhotoVedacit(atual,
                preservarRestricao ? ClassificacaoOperacionalCanhotoVedacit.valueOf(legado.getCanhotoClassificacaoOperacional())
                        : comprovanteJaEnviado ? ClassificacaoOperacionalCanhotoVedacit.SUCESSO
                        : ClassificacaoOperacionalCanhotoVedacit.PENDENTE_ENVIO);
        etlEstadoIntegracaoService.salvar(atual);
    }

    private void reconciliarRegistroSftpComLegado(
            LogIntegracaoModel atual, VedacitSftpDocument documento, LogIntegracaoModel legado
    ) {
        boolean comprovanteJaEnviado = ResultadoIntegracao.STATUS_SUCESSO.equals(legado.getStatusCanhoto());
        boolean preservarCanhoto = !comprovanteJaEnviado && (
                ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(atual.getCanhotoClassificacaoOperacional())
                || ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(atual.getCanhotoClassificacaoOperacional())
                || ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(legado.getCanhotoClassificacaoOperacional())
                || ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(legado.getCanhotoClassificacaoOperacional()));
        atual.setStatus(comprovanteJaEnviado ? ResultadoIntegracao.STATUS_ENVIADO : ResultadoIntegracao.STATUS_PARCIAL);
        atual.setStatusDados(ResultadoIntegracao.STATUS_SUCESSO);
        atual.setDataProcessamentoDados(legado.getDataProcessamentoDados());
        if (preservarCanhoto) {
            if (ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(legado.getCanhotoClassificacaoOperacional())
                    || ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(legado.getCanhotoClassificacaoOperacional())) {
                atual.setStatusCanhoto(legado.getStatusCanhoto());
                atual.setMensagemErroCanhoto(legado.getMensagemErroCanhoto());
                atual.setDataProcessamentoCanhoto(legado.getDataProcessamentoCanhoto());
                etlEstadoIntegracaoService.classificarCanhotoVedacit(atual,
                        ClassificacaoOperacionalCanhotoVedacit.valueOf(legado.getCanhotoClassificacaoOperacional()));
            }
            etlEstadoIntegracaoService.salvar(atual); return;
        }
        atual.setDataProcessamentoCanhoto(legado.getDataProcessamentoCanhoto());
        atual.setStatusCanhoto(comprovanteJaEnviado ? ResultadoIntegracao.STATUS_SUCESSO : ResultadoIntegracao.STATUS_PENDENTE_FOTO);
        atual.setCanhotoOrigem("SFTP");
        atual.setCanhotoReferencia(documento.caminhoRelativo());
        etlEstadoIntegracaoService.classificarCanhotoVedacit(atual,
                comprovanteJaEnviado ? ClassificacaoOperacionalCanhotoVedacit.SUCESSO
                        : ClassificacaoOperacionalCanhotoVedacit.PENDENTE_ENVIO);
        etlEstadoIntegracaoService.salvar(atual);
    }

    public void executarRepescagem(LocalDateTime inicioCiclo) {
        List<LogIntegracaoModel> errosDefinitivos = buscarErrosDefinitivosDoCiclo(inicioCiclo);
        List<LogIntegracaoModel> errosParciaisCanhoto = buscarErrosParciaisCanhotoPendentesRetry();
        List<LogIntegracaoModel> registros = new ArrayList<>(errosDefinitivos.size() + errosParciaisCanhoto.size());
        registros.addAll(errosDefinitivos);
        registros.addAll(errosParciaisCanhoto);

        if (registros.isEmpty()) {
            log.info("🎣 Repescagem: nenhum erro definitivo ou parcial de canhoto pendente encontrado.");
            return;
        }

        log.warn(
                "🎣 Repescagem ativa iniciada para {} registro(s): definitivos_do_ciclo={} parciais_canhoto_retry={}.",
                registros.size(),
                errosDefinitivos.size(),
                errosParciaisCanhoto.size()
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            reprocessarRegistro(registro);

            if (indice < registros.size() - 1 && !pausarEntreRegistros()) {
                log.warn("⏹️ Repescagem interrompida antes de concluir todos os registros.");
                return;
            }
        }

        log.warn("🎣 Repescagem ativa finalizada.");
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosVedacit(int limite) {
        int limiteSeguro = Math.max(1, limite);
        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosParciaisCanhotoVedacit(
                PageRequest.of(0, limiteSeguro)
        );
        if (registros == null || registros.isEmpty()) {
            log.info("🎯 [VEDACIT] Nenhum canhoto com erro pendente para reprocessamento cirúrgico.");
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        log.warn("🎯 [VEDACIT] Iniciando reprocessamento cirúrgico de {} canhoto(s).", registros.size());
        for (LogIntegracaoModel registro : registros) {
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            }
            log.info(
                    "🎯 [VEDACIT] NF {}: resultado do canhoto isolado={}",
                    registro.getChaveNfe(),
                    resultado
            );
        }

        return new ResultadoReprocessamentoCanhotoVedacit(registros.size(), enviados, pendentes, erros, 0);
    }

    /** Reprocessa o legado somente pelo CT-e/SFTP; não reconsulta ocorrência ESL nem XML. */
    public ResultadoReprocessamentoCanhotoVedacit reprocessarErrosLegadosSftpVedacit(int limite) {
        List<LogIntegracaoModel> registros = limitarUmaTentativaPorNfe(
                logIntegracaoRepository.findErrosLegadosSftpVedacit(PageRequest.of(0, Math.max(1, limite))), Math.max(1, limite));
        int enviados = 0, pendentes = 0, erros = 0, ignorados = 0;
        for (LogIntegracaoModel registro : registros) {
            registrarOrigemSftpDoCanhoto(registro);
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) enviados++;
            else if (resultado == ResultadoRegistro.PENDENTE_FOTO) pendentes++;
            else if (resultado.erro()) erros++;
            else ignorados++;
        }
        return new ResultadoReprocessamentoCanhotoVedacit(registros.size(), enviados, pendentes, erros, ignorados);
    }

    private void registrarOrigemSftpDoCanhoto(LogIntegracaoModel registro) {
        registro.setCanhotoOrigem("SFTP");
        etlEstadoIntegracaoService.salvar(registro);
    }

    /**
     * Materializa a fila de canhotos a partir do inventário SFTP, sem consultar
     * ESL e sem armazenar bytes do documento. Um CT-e já auditado é preservado;
     * somente documentos ainda desconhecidos recebem um bloqueio de origem.
     */
    public ResultadoInventarioSftpVedacit sincronizarInventarioSftpVedacit(List<VedacitSftpDocument> documentos) {
        if (documentos == null || documentos.isEmpty()) {
            return new ResultadoInventarioSftpVedacit(0, 0, 0, 0);
        }
        int novos = 0;
        int jaEnviados = 0;
        int existentes = 0;
        for (VedacitSftpDocument documento : documentos) {
            if (documento == null || documento.chaveNfe() == null || documento.chaveCte() == null
                    || !documento.chaveNfe().matches("\\d{44}") || !documento.chaveCte().matches("\\d{44}")) {
                continue;
            }
            Optional<LogIntegracaoModel> existente = logIntegracaoRepository
                    .findTopBySistemaDestinoAndChaveCteOrderByDataProcessamentoDescIdDesc(DESTINO_VEDACIT, documento.chaveCte());
            if (existente.isPresent()) {
                if (ResultadoIntegracao.STATUS_SUCESSO.equals(existente.get().getStatusCanhoto())) {
                    jaEnviados++;
                } else {
                    existentes++;
                    promoverCandidatoSftpComDadosConfirmados(existente.get(), documento);
                }
                continue;
            }
            LogIntegracaoModel pendencia = LogIntegracaoModel.builder()
                    .sistemaDestino(DESTINO_VEDACIT)
                    .chaveNfe(documento.chaveNfe())
                    .chaveCte(documento.chaveCte())
                    .status(ResultadoIntegracao.STATUS_PARCIAL)
                    // O arquivo SFTP não comprova que o XML/CT-e já foi aceito.
                    // Sem log anterior, a auditoria fica bloqueada até a origem confirmar o XML/CT-e.
                    .statusDados("PENDENTE_ORIGEM")
                    .statusCanhoto(ResultadoIntegracao.STATUS_PENDENTE_FOTO)
                    .canhotoOrigem("SFTP")
                    .canhotoReferencia(documento.caminhoRelativo())
                    .tentativasDados(0)
                    .tentativasCanhoto(0)
                    .dataProcessamento(etlEstadoIntegracaoService.agoraAuditoria())
                    .build();
            etlEstadoIntegracaoService.classificarCanhotoVedacit(
                    pendencia, ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_ORIGEM
            );
            etlEstadoIntegracaoService.salvar(pendencia);
            novos++;
        }
        return new ResultadoInventarioSftpVedacit(documentos.size(), novos, jaEnviados, existentes);
    }

    /**
     * O inventário SFTP é a fonte da fila de canhotos. Um log existente com XML
     * confirmado não pode ficar invisível apenas por possuir um status legado
     * diferente de PENDENTE_FOTO. Recusas e timeouts continuam fora do dreno.
     */
    private void promoverCandidatoSftpComDadosConfirmados(
            LogIntegracaoModel registro,
            VedacitSftpDocument documento
    ) {
        if (ResultadoIntegracao.STATUS_SUCESSO.equals(registro.getStatusCanhoto())) {
            return;
        }
        String classificacao = registro.getCanhotoClassificacaoOperacional();
        if (ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(classificacao)
                || ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(classificacao)) return;
        if (!ResultadoIntegracao.STATUS_SUCESSO.equals(registro.getStatusDados())) {
            if (ResultadoIntegracao.STATUS_PENDENTE_ORIGEM.equals(registro.getStatusDados())) {
                registro.setStatusCanhoto(ResultadoIntegracao.STATUS_PENDENTE_FOTO);
                registro.setCanhotoReferencia(documento.caminhoRelativo());
                etlEstadoIntegracaoService.classificarCanhotoVedacit(registro, ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_ORIGEM);
                etlEstadoIntegracaoService.salvar(registro);
            }
            return;
        }
        registro.setStatus(ResultadoIntegracao.STATUS_PARCIAL);
        registro.setStatusCanhoto(ResultadoIntegracao.STATUS_PENDENTE_FOTO);
        registro.setCanhotoOrigem("SFTP");
        registro.setCanhotoReferencia(documento.caminhoRelativo());
        etlEstadoIntegracaoService.classificarCanhotoVedacit(
                registro, ClassificacaoOperacionalCanhotoVedacit.PENDENTE_ENVIO
        );
        etlEstadoIntegracaoService.salvar(registro);
    }

    /** Persiste rejeições do inventário sem colocá-las na fila de envio. */
    public ResultadoInventarioSftpVedacit sincronizarInventarioSftpVedacit(VedacitSftpInventory inventario) {
        VedacitSftpInventory seguro = inventario == null ? new VedacitSftpInventory(List.of(), List.of()) : inventario;
        ResultadoInventarioSftpVedacit resultado = sincronizarInventarioSftpVedacit(seguro.documentosValidos());
        for (VedacitSftpInventory.DocumentoRejeitado rejeitado : seguro.rejeitados()) {
            if (rejeitado == null || rejeitado.caminhoRelativo() == null || rejeitado.caminhoRelativo().isBlank()) continue;
            Optional<LogIntegracaoModel> existente = logIntegracaoRepository
                    .findTopBySistemaDestinoAndCanhotoReferenciaOrderByDataProcessamentoDescIdDesc(DESTINO_VEDACIT, rejeitado.caminhoRelativo());
            LogIntegracaoModel registro = existente.orElseGet(() -> LogIntegracaoModel.builder()
                    .sistemaDestino(DESTINO_VEDACIT).canhotoReferencia(rejeitado.caminhoRelativo())
                    .chaveNfe(rejeitado.chaveNfe()).chaveCte(rejeitado.chaveCte())
                    .tentativasDados(0).tentativasCanhoto(0).build());
            registrarRejeicaoSftp(registro, rejeitado);
        }
        return resultado;
    }

    private void registrarRejeicaoSftp(LogIntegracaoModel registro, VedacitSftpInventory.DocumentoRejeitado rejeitado) {
        String classificacao = registro.getCanhotoClassificacaoOperacional();
        if (ResultadoIntegracao.STATUS_SUCESSO.equals(registro.getStatusCanhoto())
                || ClassificacaoOperacionalCanhotoVedacit.TIMEOUT_AMBIGUO.name().equals(classificacao)
                || ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_DESTINO.name().equals(classificacao)) return;
        registro.setStatus(ResultadoIntegracao.STATUS_PARCIAL);
        // Rejeição de comprovante não é evidência de transmissão do XML.
        if (registro.getStatusDados() == null || registro.getStatusDados().isBlank())
            registro.setStatusDados(ResultadoIntegracao.STATUS_PENDENTE_ORIGEM);
        registro.setStatusCanhoto(ResultadoIntegracao.STATUS_ERRO_DESTINO);
        registro.setCanhotoOrigem("SFTP");
        registro.setMensagemErroCanhoto(rejeitado.motivo());
        registro.setErro(rejeitado.motivo());
        registro.setDataProcessamento(etlEstadoIntegracaoService.agoraAuditoria());
        etlEstadoIntegracaoService.classificarCanhotoVedacit(registro, classificarRejeicaoSftp(rejeitado.motivo()));
        etlEstadoIntegracaoService.salvar(registro);
    }

    /**
     * Processa somente pendências de foto cujo XML já foi integrado e cujo CT-e
     * está auditado. O runner correspondente exige fonte SFTP exclusiva, sem
     * fallback para ESL, para evitar carga acidental na origem durante lote.
     */
    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosPendentesFotoSftpVedacit(
            int limite,
            long intervaloEntreItensMs
    ) {
        return reprocessarCanhotosPendentesFotoSftpVedacit(limite, intervaloEntreItensMs, null);
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosPendentesFotoSftpVedacit(
            int limite,
            long intervaloEntreItensMs,
            List<String> chavesNfeComArquivoSftp
    ) {
        return reprocessarCanhotosPendentesFotoSftpVedacit(
                limite, intervaloEntreItensMs, chavesNfeComArquivoSftp, new HashSet<>()
        );
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosPendentesFotoSftpVedacit(
            int limite,
            long intervaloEntreItensMs,
            List<String> chavesNfeComArquivoSftp,
            Set<String> chavesNfeJaTentadas
    ) {
        int limiteSeguro = Math.max(1, limite);
        Set<String> tentadas = chavesNfeJaTentadas == null ? new HashSet<>() : chavesNfeJaTentadas;
        List<LogIntegracaoModel> registros;
        if (chavesNfeComArquivoSftp == null) {
            registros = limitarUmaTentativaPorNfe(
                    logIntegracaoRepository.findCanhotosPendentesFotoVedacit(PageRequest.of(0, limiteSeguro)), limiteSeguro
            );
        } else {
            List<String> nfesDisponiveis = normalizarChavesNfe(chavesNfeComArquivoSftp).stream()
                    .filter(chaveNfe -> !tentadas.contains(chaveNfe))
                    .toList();
            registros = buscarRegistrosSftpEmLotes(
                    nfesDisponiveis,
                    limiteSeguro,
                    lote -> logIntegracaoRepository.findCanhotosPendentesFotoVedacitPorNfes(
                            lote, PageRequest.of(0, limiteSeguro)
                    )
            );
        }
        if (registros == null || registros.isEmpty()) {
            log.info("🎯 [VEDACIT] Nenhum canhoto PENDENTE_FOTO com CT-e elegível no lote SFTP.");
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        int ignorados = 0;
        int processados = 0;
        log.info("[INICIO] [VEDACIT][SFTP] Lote | itens={}", registros.size());
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            tentadas.add(registro.getChaveNfe());
            long inicioItem = System.nanoTime();
            registrarOrigemSftpDoCanhoto(registro);
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            } else {
                ignorados++;
            }
            processados++;
            logarProgressoSftp(
                    indice + 1, registros.size(), registro, resultado,
                    Duration.ofNanos(System.nanoTime() - inicioItem),
                    enviados, pendentes, erros, ignorados
            );

            if (indice < registros.size() - 1 && !pausarEntreRegistros(intervaloEntreItensMs)) {
                log.warn("⏹️ [VEDACIT] Lote SFTP interrompido antes de concluir os candidatos.");
                break;
            }
        }

        return new ResultadoReprocessamentoCanhotoVedacit(processados, enviados, pendentes, erros, ignorados);
    }

    public ResultadoReprocessamentoCanhotoVedacit reprocessarTimeoutsAmbiguosSftpVedacit(
            int limite,
            int limiteTentativas,
            long intervaloEntreItensMs
    ) {
        int limiteSeguro = Math.max(1, Math.min(limite, 10));
        int tentativasSeguras = Math.max(1, limiteTentativas);
        List<LogIntegracaoModel> registros = logIntegracaoRepository.findTimeoutsAmbiguosCanhotoSftpVedacit(
                tentativasSeguras,
                PageRequest.of(0, limiteSeguro)
        );
        if (registros == null || registros.isEmpty()) {
            log.info("[INFO] [VEDACIT][SFTP] Nenhum timeout ambíguo elegível para retentativa controlada.");
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        int ignorados = 0;
        log.info(
                "[RETENTATIVA] [VEDACIT][SFTP] itens={} | pausa={}s | somente timeout de leitura",
                registros.size(),
                intervaloEntreItensMs / 1000
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            long inicioItem = System.nanoTime();
            registrarOrigemSftpDoCanhoto(registro);
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            } else {
                ignorados++;
            }
            logarProgressoSftp(
                    indice + 1, registros.size(), registro, resultado,
                    Duration.ofNanos(System.nanoTime() - inicioItem),
                    enviados, pendentes, erros, ignorados
            );
            if (indice < registros.size() - 1 && !pausarEntreRegistros(intervaloEntreItensMs)) {
                log.warn("[ATENCAO] [VEDACIT][SFTP] Retentativa controlada interrompida antes do próximo timeout.");
                break;
            }
        }
        return new ResultadoReprocessamentoCanhotoVedacit(
                enviados + pendentes + erros + ignorados,
                enviados,
                pendentes,
                erros,
                ignorados
        );
    }

    /** Executa somente falhas já classificadas como técnicas, após o dreno normal. */
    public ResultadoReprocessamentoCanhotoVedacit reprocessarCanhotosTecnicosSftpVedacit(
            int limite,
            long intervaloEntreItensMs,
            int limiteErros,
            List<String> chavesNfeComArquivoSftp
    ) {
        if (chavesNfeComArquivoSftp == null || chavesNfeComArquivoSftp.isEmpty()) {
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }
        int limiteSeguro = Math.max(1, limite);
        List<LogIntegracaoModel> registros = buscarRegistrosSftpEmLotes(
                chavesNfeComArquivoSftp,
                limiteSeguro,
                lote -> logIntegracaoRepository.findCanhotosTecnicosSftpVedacitPorNfes(
                        lote, PageRequest.of(0, limiteSeguro)
                )
        );
        if (registros.isEmpty()) {
            return new ResultadoReprocessamentoCanhotoVedacit(0, 0, 0, 0, 0);
        }
        int enviados = 0, pendentes = 0, erros = 0, ignorados = 0, processados = 0;
        int limiteErrosSeguro = Math.max(1, limiteErros);
        log.info(
                "[QUARENTENA] [VEDACIT][SFTP] Retentativa técnica pós-fila | itens={} | pausa={}s | limite_erros={}",
                registros.size(), intervaloEntreItensMs / 1000, limiteErrosSeguro
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            long inicio = System.nanoTime();
            registrarOrigemSftpDoCanhoto(registro);
            ResultadoRegistro resultado = etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) enviados++;
            else if (resultado == ResultadoRegistro.PENDENTE_FOTO) pendentes++;
            else if (resultado.erro()) erros++;
            else ignorados++;
            processados++;
            logarProgressoSftp(indice + 1, registros.size(), registro, resultado,
                    Duration.ofNanos(System.nanoTime() - inicio), enviados, pendentes, erros, ignorados);
            if (erros >= limiteErrosSeguro) {
                log.warn(
                        "[ATENCAO] [VEDACIT][SFTP] Quarentena pausada: {} erro(s), limite seguro={}.",
                        erros, limiteErrosSeguro
                );
                break;
            }
            if (indice < registros.size() - 1 && !pausarEntreRegistros(intervaloEntreItensMs)) break;
        }
        return new ResultadoReprocessamentoCanhotoVedacit(processados, enviados, pendentes, erros, ignorados);
    }

    public boolean possuiTimeoutAmbiguoSftpVedacitPendente() {
        return !logIntegracaoRepository.findTimeoutsAmbiguosCanhotoSftpVedacit(
                2,
                PageRequest.of(0, 1)
        ).isEmpty();
    }

    public long contarNfesCandidatasCanhotoVedacitSftp(List<String> chavesNfeComArquivoSftp) {
        if (chavesNfeComArquivoSftp == null || chavesNfeComArquivoSftp.isEmpty()) {
            return 0;
        }
        return contarNfesSftpEmLotes(
                chavesNfeComArquivoSftp,
                logIntegracaoRepository::countNfesCandidatasCanhotoVedacitPorNfes
        );
    }

    public long contarLogsCandidatosCanhotoVedacitSftp(List<String> chavesNfeComArquivoSftp) {
        if (chavesNfeComArquivoSftp == null || chavesNfeComArquivoSftp.isEmpty()) {
            return 0;
        }
        return contarNfesSftpEmLotes(
                chavesNfeComArquivoSftp,
                logIntegracaoRepository::countLogsCandidatosCanhotoVedacitPorNfes
        );
    }

    public long contarClassificacaoCanhotoVedacit(String classificacao) {
        return logIntegracaoRepository.countBySistemaDestinoAndCanhotoClassificacaoOperacional(
                DESTINO_VEDACIT, classificacao
        );
    }

    public long contarClassificacaoCanhotoVedacit(String cliente, String classificacao) {
        return logIntegracaoRepository.countBySistemaDestinoAndSftpClienteAndCanhotoClassificacaoOperacional(
                DESTINO_VEDACIT, cliente, classificacao);
    }

    private ClassificacaoOperacionalCanhotoVedacit classificarRejeicaoSftp(String motivo) {
        String motivoSeguro = motivo == null ? "" : motivo.trim().toLowerCase(Locale.ROOT);
        return motivoSeguro.startsWith("arquivo inv")
                ? ClassificacaoOperacionalCanhotoVedacit.BLOQUEADO_ORIGEM
                : ClassificacaoOperacionalCanhotoVedacit.PENDENTE_TECNICO;
    }

    private List<LogIntegracaoModel> limitarUmaTentativaPorNfe(List<LogIntegracaoModel> registros, int limite) {
        if (registros == null || registros.isEmpty()) {
            return List.of();
        }
        Map<String, LogIntegracaoModel> primeiroPorNfe = new LinkedHashMap<>();
        for (LogIntegracaoModel registro : registros) {
            if (registro == null || registro.getChaveNfe() == null || registro.getChaveNfe().isBlank()) {
                continue;
            }
            primeiroPorNfe.putIfAbsent(registro.getChaveNfe(), registro);
            if (primeiroPorNfe.size() == limite) {
                break;
            }
        }
        return List.copyOf(primeiroPorNfe.values());
    }

    private List<LogIntegracaoModel> buscarRegistrosSftpEmLotes(
            List<String> chavesNfe,
            int limite,
            Function<List<String>, List<LogIntegracaoModel>> consulta
    ) {
        int limiteSeguro = Math.max(1, limite);
        List<LogIntegracaoModel> registros = new ArrayList<>();
        for (List<String> lote : dividirChavesNfeParaConsulta(chavesNfe)) {
            List<LogIntegracaoModel> encontrados = consulta.apply(lote);
            if (encontrados != null) {
                registros.addAll(encontrados);
            }
        }
        registros.sort(ORDEM_FILA_SFTP);
        return limitarUmaTentativaPorNfe(registros, limiteSeguro);
    }

    private long contarNfesSftpEmLotes(List<String> chavesNfe, ToLongFunction<List<String>> consulta) {
        long total = 0;
        for (List<String> lote : dividirChavesNfeParaConsulta(chavesNfe)) {
            total += consulta.applyAsLong(lote);
        }
        return total;
    }

    private List<List<String>> dividirChavesNfeParaConsulta(List<String> chavesNfe) {
        List<String> chavesNormalizadas = normalizarChavesNfe(chavesNfe);
        if (chavesNormalizadas.isEmpty()) {
            return List.of();
        }
        List<List<String>> lotes = new ArrayList<>();
        for (int inicio = 0; inicio < chavesNormalizadas.size(); inicio += MAXIMO_NFES_POR_CONSULTA_SQL) {
            int fim = Math.min(inicio + MAXIMO_NFES_POR_CONSULTA_SQL, chavesNormalizadas.size());
            lotes.add(List.copyOf(chavesNormalizadas.subList(inicio, fim)));
        }
        return List.copyOf(lotes);
    }

    private List<String> normalizarChavesNfe(Iterable<String> chavesNfe) {
        if (chavesNfe == null) {
            return List.of();
        }
        Set<String> normalizadas = new LinkedHashSet<>();
        for (String chaveNfe : chavesNfe) {
            if (chaveNfe != null && !chaveNfe.isBlank()) {
                normalizadas.add(chaveNfe.trim());
            }
        }
        return List.copyOf(normalizadas);
    }

    private void logarProgressoSftp(
            int itemAtual,
            int totalItens,
            LogIntegracaoModel registro,
            ResultadoRegistro resultado,
            Duration duracao,
            int enviados,
            int pendentes,
            int erros,
            int ignorados
    ) {
        String motivo = resultado.erro()
                ? " | motivo=" + resumirMensagem(registro.getMensagemErroCanhoto())
                : "";
        log.info(
                "{} [VEDACIT][SFTP] {}/{} | {} | NF={} | duracao={} | acumulado enviados={} erros={} pendentes={} ignorados={}{}",
                simboloResultado(resultado), itemAtual, totalItens, resultado.name(), chaveResumida(registro.getChaveNfe()),
                formatarDuracao(duracao), enviados, erros, pendentes, ignorados, motivo
        );
        logDetalheSftpVedacit.info(
                "[VEDACIT][SFTP][DETALHE] item={}/{} resultado={} nfe={} cte_original={} cte_efetivo={} duracao_ms={}",
                itemAtual, totalItens, resultado.name(), registro.getChaveNfe(), registro.getChaveCte(),
                registro.getCanhotoChaveCteEfetiva(), duracao.toMillis()
        );
    }

    private String simboloResultado(ResultadoRegistro resultado) {
        if (resultado == ResultadoRegistro.ENVIADO) return "[OK]";
        if (resultado.erro()) return "[ERRO]";
        if (resultado == ResultadoRegistro.IGNORADO || resultado == ResultadoRegistro.JA_PROCESSADO) return "[PULAR]";
        return "[PENDENTE]";
    }

    private String chaveResumida(String chave) {
        if (chave == null || chave.length() <= 12) return String.valueOf(chave);
        return chave.substring(0, 6) + "..." + chave.substring(chave.length() - 6);
    }

    private String resumirMensagem(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) return "erro sem detalhe";
        String resumo = mensagem.replaceAll("\\s+", " ").trim();
        return resumo.length() <= 120 ? resumo : resumo.substring(0, 117) + "...";
    }

    private String formatarDuracao(Duration duracao) {
        long totalSegundos = Math.max(0, duracao.toSeconds());
        return totalSegundos >= 60
                ? "%dm%02ds".formatted(totalSegundos / 60, totalSegundos % 60)
                : "%ds".formatted(totalSegundos);
    }

    /**
     * Repescagem noturna limitada a falhas tecnicas Vedacit. Recusas de negocio
     * e CT-es sem chave ficam fora da selecao e continuam visiveis na quarentena.
     */
    public ResultadoRepescagemNoturnaVedacit reprocessarPendenciasTecnicasVedacit(
            int limiteItens,
            int limiteTentativas
    ) {
        int limiteSeguro = Math.max(1, Math.min(limiteItens, 500));
        int tentativasSeguras = Math.max(1, limiteTentativas);
        List<LogIntegracaoModel> dados = new ArrayList<>(normalizarLista(
                logIntegracaoRepository.findCandidatosRepescagemNoturnaVedacitDados(
                        tentativasSeguras,
                        PageRequest.of(0, limiteSeguro)
                )
        ));
        int restanteParaHistoricos = Math.max(0, limiteSeguro - dados.size());
        List<LogIntegracaoModel> dadosHistoricos = buscarDadosHistoricosTecnicosVedacit(
                restanteParaHistoricos,
                tentativasSeguras
        );
        dados.addAll(dadosHistoricos);
        int restante = Math.max(0, limiteSeguro - dados.size());
        List<LogIntegracaoModel> canhotos = restante == 0
                ? List.of()
                : normalizarLista(logIntegracaoRepository.findCandidatosRepescagemNoturnaVedacitCanhoto(
                        tentativasSeguras,
                        PageRequest.of(0, restante)
                ));

        if (dados.isEmpty() && canhotos.isEmpty()) {
            log.info("🌙 [VEDACIT] Repescagem noturna: nenhuma falha técnica elegível encontrada.");
            return new ResultadoRepescagemNoturnaVedacit(0, 0, 0, 0, 0);
        }

        int enviados = 0;
        int pendentes = 0;
        int erros = 0;
        List<LogIntegracaoModel> registros = new ArrayList<>(dados.size() + canhotos.size());
        registros.addAll(dados);
        registros.addAll(canhotos);

        log.warn(
                "🌙 [VEDACIT] Iniciando repescagem noturna registrada: xml={} (historicos={}) canhotos={} limite_tentativas={}.",
                dados.size(), dadosHistoricos.size(), canhotos.size(), tentativasSeguras
        );
        for (int indice = 0; indice < registros.size(); indice++) {
            LogIntegracaoModel registro = registros.get(indice);
            ResultadoRegistro resultado = indice < dados.size()
                    ? etlRegistroService.reprocessarXmlCteVedacitPorChave(registro)
                    : etlRegistroService.reprocessarCanhotoVedacitPorCte(registro);
            if (resultado == ResultadoRegistro.ENVIADO) {
                enviados++;
            } else if (resultado == ResultadoRegistro.PENDENTE_FOTO) {
                pendentes++;
            } else if (resultado.erro()) {
                erros++;
            }

            log.info("🌙 [VEDACIT] NF {}: repescagem noturna resultado={}", registro.getChaveNfe(), resultado);
            if (indice < registros.size() - 1 && !pausarEntreRegistros()) {
                log.warn("⏹️ [VEDACIT] Repescagem noturna interrompida antes de concluir os candidatos.");
                break;
            }
        }

        ResultadoRepescagemNoturnaVedacit resultado = new ResultadoRepescagemNoturnaVedacit(
                dados.size(), canhotos.size(), enviados, pendentes, erros
        );
        log.warn(
                "🌙 [VEDACIT] Repescagem noturna finalizada: selecionados_xml={} selecionados_canhoto={} enviados={} pendentes={} erros={}.",
                resultado.selecionadosXml(), resultado.selecionadosCanhoto(), resultado.enviados(),
                resultado.pendentes(), resultado.erros()
        );
        return resultado;
    }

    public record ResultadoReprocessamentoCanhotoVedacit(
            int selecionados,
            int enviados,
            int pendentes,
            int erros,
            int ignorados
    ) {
        public boolean concluidoSemErro() {
            return erros == 0;
        }
    }

    public record ResultadoInventarioSftpVedacit(int arquivos, int novos, int jaEnviados, int existentes) { }

    public record ResultadoClienteSftpVedacit(
            ResultadoInventarioSftpVedacit inventario,
            ResultadoReprocessamentoCanhotoVedacit processamento,
            long saldo
    ) { }

    public record ResultadoRepescagemNoturnaVedacit(
            int selecionadosXml,
            int selecionadosCanhoto,
            int enviados,
            int pendentes,
            int erros
    ) {
        public boolean concluidoSemErro() {
            return erros == 0;
        }
    }

    private List<LogIntegracaoModel> buscarErrosDefinitivosDoCiclo(LocalDateTime inicioCiclo) {
        if (inicioCiclo == null) {
            log.warn("⏭️ Repescagem de erros definitivos do ciclo ignorada: início do ciclo não informado.");
            return List.of();
        }

        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosManuaisDesde(inicioCiclo);
        return registros != null ? registros : List.of();
    }

    private List<LogIntegracaoModel> normalizarLista(List<LogIntegracaoModel> registros) {
        return registros != null ? registros : List.of();
    }

    /**
     * Recupera somente erros técnicos históricos em que a chave do CT-e ficou
     * registrada no URL da falha 401, mas não chegou a ser persistida no campo
     * próprio da auditoria. A chave é validada pelo formato antes do reenvio e
     * passa a compor o mesmo log auditável da tentativa original.
     */
    private List<LogIntegracaoModel> buscarDadosHistoricosTecnicosVedacit(
            int limite,
            int limiteTentativas
    ) {
        if (limite <= 0) {
            return List.of();
        }

        List<LogIntegracaoModel> candidatos = normalizarLista(
                logIntegracaoRepository.findQuarentenaByDestino(DESTINO_VEDACIT)
        );
        List<LogIntegracaoModel> selecionados = new ArrayList<>();
        for (LogIntegracaoModel candidato : candidatos) {
            if (!ehCandidatoHistoricoTecnicoVedacit(candidato, limiteTentativas)) {
                continue;
            }

            Optional<String> chaveCte = extrairChaveCteDaMensagem(candidato);
            if (chaveCte.isEmpty()) {
                continue;
            }

            candidato.setChaveCte(chaveCte.get());
            selecionados.add(candidato);
            if (selecionados.size() >= limite) {
                break;
            }
        }
        return selecionados;
    }

    private boolean ehCandidatoHistoricoTecnicoVedacit(
            LogIntegracaoModel registro,
            int limiteTentativas
    ) {
        return registro != null
                && DESTINO_VEDACIT.equals(registro.getSistemaDestino())
                && STATUS_ERRO_DESTINO.equals(registro.getStatus())
                && STATUS_ERRO_DESTINO.equals(registro.getStatusDados())
                && registro.getChaveNfe() != null
                && registro.getChaveNfe().length() == 44
                && (registro.getChaveCte() == null || registro.getChaveCte().isBlank())
                && valorTentativas(registro.getTentativasDados()) < limiteTentativas
                && mensagemTecnica(registro).toLowerCase(Locale.ROOT).contains("401 unauthorized");
    }

    private Optional<String> extrairChaveCteDaMensagem(LogIntegracaoModel registro) {
        Matcher matcher = CHAVE_CTE_NA_MENSAGEM.matcher(mensagemTecnica(registro));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String mensagemTecnica(LogIntegracaoModel registro) {
        if (registro == null) {
            return "";
        }
        if (registro.getMensagemErroDados() != null && !registro.getMensagemErroDados().isBlank()) {
            return registro.getMensagemErroDados();
        }
        if (registro.getMensagemErroCanhoto() != null && !registro.getMensagemErroCanhoto().isBlank()) {
            return registro.getMensagemErroCanhoto();
        }
        return registro.getErro() != null ? registro.getErro() : "";
    }

    private List<LogIntegracaoModel> buscarErrosParciaisCanhotoPendentesRetry() {
        List<LogIntegracaoModel> registros = logIntegracaoRepository.findErrosParciaisCanhotoPendentesRetry();
        return registros != null ? registros : List.of();
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
        if (DESTINO_SELIA.equals(destino)) {
            log.warn(
                    "⏭️ [SELIA] NF {}: repescagem genérica bloqueada; o reprocessamento exige fluxo SELIA específico.",
                    registro.getChaveNfe()
            );
            return;
        }

        log.warn(
                "🎣 [{}] NF {}: iniciando repescagem. tentativas_dados={} tentativas_canhoto={}",
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

        if (DESTINO_SELIA.equals(destino)) {
            return (ocorrencia, comprovante, logIntegracao) ->
                    seliaIntegrationService.processarOcorrencia(ocorrencia, comprovante);
        }

        return (ocorrencia, comprovante, logIntegracao) -> vedacitIntegrationService.processarOcorrencia(
                ocorrencia,
                comprovante,
                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusDados()),
                etlEstadoIntegracaoService.statusSucesso(logIntegracao.getStatusCanhoto())
        );
    }

    private String headerAuth(String destino) {
        if (DESTINO_PPG.equals(destino)) {
            return "Bearer " + tokenPpgEsl;
        }

        return "Bearer " + (DESTINO_SELIA.equals(destino) ? tokenSeliaEsl : tokenVedacitEsl);
    }

    private String normalizarDestino(LogIntegracaoModel registro) {
        if (registro == null || registro.getSistemaDestino() == null) {
            return null;
        }

        String destino = registro.getSistemaDestino().trim().toUpperCase(Locale.ROOT);
        if (DESTINO_PPG.equals(destino) || DESTINO_SELIA.equals(destino) || DESTINO_VEDACIT.equals(destino)) {
            return destino;
        }

        return null;
    }

    private boolean pausarEntreRegistros() {
        return pausarEntreRegistros(intervaloEntreRegistrosMs);
    }

    private boolean pausarEntreRegistros(long intervaloMs) {
        long esperaMs = Math.max(0, intervaloMs);
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
