package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.ResultadoIntegracao;

@Service
public class EtlEstadoIntegracaoService {

    private static final String DESTINO_PPG = "PPG";
    private static final String STATUS_IGNORADO = ResultadoIntegracao.STATUS_IGNORADO;
    private static final String STATUS_ENVIADO = ResultadoIntegracao.STATUS_ENVIADO;
    private static final String STATUS_ERRO_DESTINO = ResultadoIntegracao.STATUS_ERRO_DESTINO;
    private static final String STATUS_SUCESSO = ResultadoIntegracao.STATUS_SUCESSO;
    private static final Set<String> STATUS_FINALIZADOS_SEM_REENVIO = Set.of(STATUS_ENVIADO, STATUS_IGNORADO);

    private final LogIntegracaoRepository logIntegracaoRepository;

    public EtlEstadoIntegracaoService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    public LogIntegracaoModel salvar(LogIntegracaoModel logIntegracao) {
        return logIntegracaoRepository.save(logIntegracao);
    }

    public List<LogIntegracaoModel> buscarPendenciasCanhoto(String destino, String statusCanhoto) {
        return logIntegracaoRepository.findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(
                destino,
                statusCanhoto
        );
    }

    public Optional<LogIntegracaoModel> buscarLogIntegracaoExistente(String destino, EslOcorrenciaDTO ocorrencia) {
        Long occurrenceId = obterOccurrenceId(ocorrencia);
        if (occurrenceId == null) {
            return Optional.empty();
        }

        return logIntegracaoRepository.findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc(
                destino,
                occurrenceId
        );
    }

    public boolean finalizadoSemReenvio(LogIntegracaoModel logIntegracao) {
        return logIntegracao != null && STATUS_FINALIZADOS_SEM_REENVIO.contains(logIntegracao.getStatus());
    }

    public LogIntegracaoModel criarLogComStatus(
            String destino,
            Long cursorNextId,
            EslOcorrenciaDTO ocorrencia,
            String status
    ) {
        return LogIntegracaoModel.builder()
                .occurrenceId(obterOccurrenceId(ocorrencia))
                .chaveNfe(obterChaveNfe(ocorrencia))
                .freightId(ocorrencia != null && ocorrencia.freight() != null ? ocorrencia.freight().id() : null)
                .cursorNextId(cursorNextId)
                .status(status)
                .statusDados(status)
                .statusCanhoto(status)
                .tentativasDados(0)
                .tentativasCanhoto(0)
                .sistemaDestino(destino)
                .dataProcessamento(LocalDateTime.now())
                .build();
    }

    public void aplicarResultadoIntegracao(LogIntegracaoModel logIntegracao, ResultadoIntegracao resultado) {
        LocalDateTime agora = LocalDateTime.now();
        String statusDadosAnterior = logIntegracao.getStatusDados();
        String statusCanhotoAnterior = logIntegracao.getStatusCanhoto();
        String statusDadosNovo = resultado.statusDados() != null ? resultado.statusDados() : statusDadosAnterior;
        String statusCanhotoNovo = resultado.statusCanhoto() != null ? resultado.statusCanhoto() : statusCanhotoAnterior;

        logIntegracao.setStatus(resultado.status());
        logIntegracao.setStatusDados(statusDadosNovo);
        logIntegracao.setStatusCanhoto(statusCanhotoNovo);
        logIntegracao.setMensagemErroDados(resultado.mensagemErroDados());
        logIntegracao.setMensagemErroCanhoto(resultado.mensagemErroCanhoto());
        logIntegracao.setErro(montarMensagemErroGeral(resultado));
        logIntegracao.setDataProcessamento(agora);

        if (deveAtualizarDataProcessamento(statusDadosAnterior, statusDadosNovo)) {
            logIntegracao.setDataProcessamentoDados(agora);
            logIntegracao.setTentativasDados(incrementar(logIntegracao.getTentativasDados()));
        }

        if (deveAtualizarDataProcessamento(statusCanhotoAnterior, statusCanhotoNovo)) {
            logIntegracao.setDataProcessamentoCanhoto(agora);
            logIntegracao.setTentativasCanhoto(incrementar(logIntegracao.getTentativasCanhoto()));
        }
    }

    public ResultadoRegistro converterResultadoRegistro(ResultadoIntegracao resultado) {
        if (resultado.erro()) {
            return ResultadoRegistro.ERRO;
        }

        if (resultado.foiIgnorado()) {
            return ResultadoRegistro.IGNORADO;
        }

        if (resultado.pendenteFoto()) {
            return ResultadoRegistro.PENDENTE_FOTO;
        }

        return ResultadoRegistro.ENVIADO;
    }

    public ResultadoIntegracao criarResultadoErroGenerico(String destino, Exception e) {
        String mensagem = e.getMessage();
        if (DESTINO_PPG.equals(destino)) {
            return new ResultadoIntegracao(
                    STATUS_ERRO_DESTINO,
                    STATUS_ERRO_DESTINO,
                    STATUS_ERRO_DESTINO,
                    mensagem,
                    mensagem
            );
        }

        return ResultadoIntegracao.erroDados(mensagem);
    }

    public String statusDadosAtualOuSucesso(LogIntegracaoModel logIntegracao) {
        if (logIntegracao.getStatusDados() == null || logIntegracao.getStatusDados().isBlank()) {
            return STATUS_SUCESSO;
        }

        return logIntegracao.getStatusDados();
    }

    public boolean statusSucesso(String status) {
        return STATUS_SUCESSO.equals(status);
    }

    boolean deveAtualizarDataProcessamento(String statusAnterior, String statusNovo) {
        return statusNovo != null
                && !ResultadoIntegracao.STATUS_NAO_APLICAVEL.equals(statusNovo)
                && (STATUS_ERRO_DESTINO.equals(statusNovo) || !statusNovo.equals(statusAnterior));
    }

    Integer incrementar(Integer valorAtual) {
        return valorAtual == null ? 1 : valorAtual + 1;
    }

    String montarMensagemErroGeral(ResultadoIntegracao resultado) {
        if (resultado.mensagemErroDados() != null && resultado.mensagemErroCanhoto() != null) {
            return resultado.mensagemErroDados() + " | " + resultado.mensagemErroCanhoto();
        }

        if (resultado.mensagemErroDados() != null) {
            return resultado.mensagemErroDados();
        }

        return resultado.mensagemErroCanhoto();
    }

    private Long obterOccurrenceId(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null) {
            return null;
        }

        return ocorrencia.id();
    }

    private String obterChaveNfe(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.invoice() == null) {
            return null;
        }

        return ocorrencia.invoice().key();
    }
}
