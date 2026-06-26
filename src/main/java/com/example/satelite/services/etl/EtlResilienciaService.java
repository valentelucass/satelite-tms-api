package com.example.satelite.services.etl;

import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.services.ResultadoIntegracao;

@Service
public class EtlResilienciaService {

    private static final Logger log = LoggerFactory.getLogger(EtlResilienciaService.class);

    private static final String STATUS_ERRO_DESTINO = ResultadoIntegracao.STATUS_ERRO_DESTINO;
    private static final Set<Integer> CODIGOS_HTTP_TRANSITORIOS = Set.of(429, 502, 503, 504);

    @Value("${INTEGRATION_MAX_RETRY_ATTEMPTS:3}")
    private int maxTentativasReprocessamento = 3;

    @Value("${INTEGRATION_TRANSIENT_BACKOFF_MS:15000}")
    private long backoffErroTransitorioMs = 15000;

    public ResultadoRegistro processarOcorrenciaComRetentativas(
            String destino,
            String chaveNfe,
            LogIntegracaoModel logIntegracao,
            ProcessamentoRegistroTentativa processamento
    ) {
        while (true) {
            ResultadoRegistro resultado = processamento.processar();

            if (resultado != ResultadoRegistro.ERRO || !erroTransitorioRegistrado(logIntegracao)) {
                return resultado;
            }

            int tentativasConsumidas = maiorTentativas(logIntegracao);
            if (tentativasConsumidas >= limiteMaximoTentativas()) {
                return resultadoErroRespeitandoLimiteTentativas(destino, chaveNfe, logIntegracao);
            }

            log.warn(
                    "⏸️ [{}] NF {}: erro HTTP temporário detectado após a tentativa {}/{}. Pausando {} ms antes de retomar o mesmo registro.",
                    destino,
                    chaveNfe,
                    tentativasConsumidas,
                    limiteMaximoTentativas(),
                    backoffTransitorioMs()
            );
            pausarAposErroTransitorio();
        }
    }

    public ResultadoRegistro resultadoErroRespeitandoLimiteTentativas(
            String destino,
            String chaveNfe,
            LogIntegracaoModel logIntegracao
    ) {
        if (limiteTentativasAtingido(logIntegracao)) {
            logarLimiteTentativasAtingido(destino, chaveNfe, logIntegracao);
            return ResultadoRegistro.JA_PROCESSADO;
        }

        return ResultadoRegistro.ERRO;
    }

    public boolean limiteTentativasAtingido(LogIntegracaoModel logIntegracao) {
        if (logIntegracao == null) {
            return false;
        }

        return limiteTentativasAtingido(logIntegracao.getStatusDados(), logIntegracao.getTentativasDados())
                || limiteTentativasAtingido(logIntegracao.getStatusCanhoto(), logIntegracao.getTentativasCanhoto());
    }

    private boolean limiteTentativasAtingido(String status, Integer tentativas) {
        return STATUS_ERRO_DESTINO.equals(status)
                && valorTentativas(tentativas) >= limiteMaximoTentativas();
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas == null ? 0 : tentativas;
    }

    private int maiorTentativas(LogIntegracaoModel logIntegracao) {
        if (logIntegracao == null) {
            return 0;
        }

        return Math.max(
                valorTentativas(logIntegracao.getTentativasDados()),
                valorTentativas(logIntegracao.getTentativasCanhoto())
        );
    }

    private int limiteMaximoTentativas() {
        return Math.max(1, maxTentativasReprocessamento);
    }

    private boolean erroTransitorioRegistrado(LogIntegracaoModel logIntegracao) {
        String textoErro = montarTextoErro(logIntegracao);
        if (textoErro.isBlank()) {
            return false;
        }

        String normalizado = textoErro.toLowerCase(Locale.ROOT);
        return CODIGOS_HTTP_TRANSITORIOS.stream().anyMatch(codigo -> contemCodigoHttp(normalizado, codigo))
                || normalizado.contains("too many requests")
                || normalizado.contains("bad gateway")
                || normalizado.contains("service unavailable")
                || normalizado.contains("gateway timeout");
    }

    private String montarTextoErro(LogIntegracaoModel logIntegracao) {
        if (logIntegracao == null) {
            return "";
        }

        StringBuilder texto = new StringBuilder();
        adicionarTextoErro(texto, logIntegracao.getErro());
        adicionarTextoErro(texto, logIntegracao.getMensagemErroDados());
        adicionarTextoErro(texto, logIntegracao.getMensagemErroCanhoto());
        return texto.toString();
    }

    private void adicionarTextoErro(StringBuilder texto, String mensagem) {
        if (mensagem == null || mensagem.isBlank()) {
            return;
        }

        if (!texto.isEmpty()) {
            texto.append(' ');
        }
        texto.append(mensagem);
    }

    private boolean contemCodigoHttp(String texto, int codigoHttp) {
        return texto.matches("(?s).*\\b" + codigoHttp + "\\b.*");
    }

    private long backoffTransitorioMs() {
        return Math.max(0, backoffErroTransitorioMs);
    }

    private void pausarAposErroTransitorio() {
        long esperaMs = backoffTransitorioMs();
        if (esperaMs <= 0) {
            return;
        }

        try {
            Thread.sleep(esperaMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pausa de backoff transitorio interrompida", e);
        }
    }

    private void logarLimiteTentativasAtingido(
            String destino,
            String chaveNfe,
            LogIntegracaoModel logIntegracao
    ) {
        log.warn(
                "🛑 [{}] NF {}: Limite de {} tentativa(s) atingido. Mantendo ERRO_DESTINO para o Dashboard e bloqueando retry automático. tentativas_dados={} tentativas_canhoto={}",
                destino,
                chaveNfe,
                limiteMaximoTentativas(),
                valorTentativas(logIntegracao.getTentativasDados()),
                valorTentativas(logIntegracao.getTentativasCanhoto())
        );
    }

    @FunctionalInterface
    public interface ProcessamentoRegistroTentativa {
        ResultadoRegistro processar();
    }
}
