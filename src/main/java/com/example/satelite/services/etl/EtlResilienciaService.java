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
    private static final Set<Integer> CODIGOS_HTTP_INFRAESTRUTURA = Set.of(502, 503, 504);

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

            if (!resultado.erro() || !erroTransitorioRegistrado(logIntegracao)) {
                return resultado;
            }

            int tentativasConsumidas = maiorTentativas(logIntegracao);
            if (tentativasConsumidas >= limiteMaximoTentativas()) {
                if (resultado != ResultadoRegistro.ERRO) {
                    return resultado;
                }

                return resultadoErroAposTentativa(destino, chaveNfe, logIntegracao);
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

        return resultadoErroAposTentativa(destino, chaveNfe, logIntegracao);
    }

    public ResultadoRegistro resultadoErroAposTentativa(
            String destino,
            String chaveNfe,
            LogIntegracaoModel logIntegracao
    ) {
        if (limiteTentativasAtingido(logIntegracao)) {
            logarLimiteTentativasAtingido(destino, chaveNfe, logIntegracao);
            if (!falhaInfraestruturaRegistrada(logIntegracao)) {
                return ResultadoRegistro.JA_PROCESSADO;
            }
        }

        return falhaInfraestruturaRegistrada(logIntegracao)
                ? ResultadoRegistro.ERRO_INFRAESTRUTURA
                : ResultadoRegistro.ERRO;
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
        String normalizado = normalizarTextoErro(logIntegracao);
        return !normalizado.isBlank()
                && (CODIGOS_HTTP_TRANSITORIOS.stream().anyMatch(codigo -> contemCodigoHttp(normalizado, codigo))
                || normalizado.contains("too many requests")
                || contemMarcadorInfraestrutura(normalizado));
    }

    public boolean falhaInfraestruturaRegistrada(LogIntegracaoModel logIntegracao) {
        String normalizado = normalizarTextoErro(logIntegracao);
        return !normalizado.isBlank() && contemMarcadorInfraestrutura(normalizado);
    }

    private boolean contemMarcadorInfraestrutura(String textoErroNormalizado) {
        return CODIGOS_HTTP_INFRAESTRUTURA.stream().anyMatch(codigo -> contemCodigoHttp(textoErroNormalizado, codigo))
                || textoErroNormalizado.contains("bad gateway")
                || textoErroNormalizado.contains("service unavailable")
                || textoErroNormalizado.contains("gateway timeout")
                || textoErroNormalizado.contains("sockettimeoutexception")
                || textoErroNormalizado.contains("timed out")
                || textoErroNormalizado.contains("timeout");
    }

    private String normalizarTextoErro(LogIntegracaoModel logIntegracao) {
        return montarTextoErro(logIntegracao).toLowerCase(Locale.ROOT);
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
