package com.example.satelite.services.selia;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.SeliaClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.selia.SeliaAddEventsRequestDTO;
import com.example.satelite.dto.selia.SeliaTrackingAttachmentDTO;
import com.example.satelite.dto.selia.SeliaTrackingEventDTO;
import com.example.satelite.services.ResultadoIntegracao;

import feign.FeignException;

@Service
public class SeliaIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(SeliaIntegrationService.class);

    private final SeliaClient seliaClient;

    @Value("${SELIA_INTELIPOST_API_KEY:}")
    private String apiKey;

    @Value("${SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY:}")
    private String logisticProviderApiKey;

    @Value("${SELIA_INTELIPOST_PLATFORM:}")
    private String platform;

    @Value("${SELIA_INTELIPOST_PLATFORM_VERSION:}")
    private String platformVersion;

    @Value("${SELIA_INTELIPOST_PLUGIN:}")
    private String plugin;

    @Value("${SELIA_INTELIPOST_PLUGIN_VERSION:}")
    private String pluginVersion;

    @Value("${SELIA_INTELIPOST_DELIVERY_EVENT_CODE:}")
    private String deliveryEventCode;

    @Value("${SELIA_INTELIPOST_RECEIPT_TYPE:POD}")
    private String receiptType;

    @Value("${SELIA_INTELIPOST_RECEIPT_MIME_TYPE:image/jpeg}")
    private String receiptMimeType;

    @Value("${SELIA_INTELIPOST_RATE_LIMIT_FALLBACK_MS:15000}")
    private long rateLimitFallbackMs;

    @Value("${app.selia.nfe-whitelist:}")
    private String nfeWhitelist;

    @Value("${app.selia.nfe-whitelist-enabled:false}")
    private boolean whitelistEnabled;

    public SeliaIntegrationService(SeliaClient seliaClient) {
        this.seliaClient = seliaClient;
    }

    public ResultadoIntegracao processarOcorrencia(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        String chaveNfe = obterChaveNfe(ocorrencia);
        if (!notaFiscalPermitida(ocorrencia)) {
            log.warn("[SELIA] NF {} ignorada por não pertencer à whitelist.", chaveNfe);
            return ResultadoIntegracao.ignorado();
        }

        SeliaAddEventsRequestDTO request = converter(ocorrencia, comprovante);
        try {
            seliaClient.adicionarEventos(
                    obrigatorio(apiKey, "SELIA_INTELIPOST_API_KEY"),
                    obrigatorio(logisticProviderApiKey, "SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY"),
                    obrigatorio(platform, "SELIA_INTELIPOST_PLATFORM"),
                    obrigatorio(platformVersion, "SELIA_INTELIPOST_PLATFORM_VERSION"),
                    obrigatorio(plugin, "SELIA_INTELIPOST_PLUGIN"),
                    obrigatorio(pluginVersion, "SELIA_INTELIPOST_PLUGIN_VERSION"),
                    request
            );
            log.info("[SELIA] NF {}: ocorrência e comprovante enviados para AddEvents.", chaveNfe);
            return ResultadoIntegracao.enviado();
        } catch (FeignException e) {
            if (e.status() == 429) {
                aguardarRateLimit(e, chaveNfe);
            }

            if (erroDuplicidade(e)) {
                log.info("[SELIA] NF {}: destino informou duplicidade; conciliado como sucesso.", chaveNfe);
                return ResultadoIntegracao.enviado();
            }

            throw e;
        }
    }

    public boolean notaFiscalPermitida(EslOcorrenciaDTO ocorrencia) {
        if (!whitelistEnabled) {
            return true;
        }

        return obterWhitelistNfe().contains(obterChaveNfe(ocorrencia));
    }

    private SeliaAddEventsRequestDTO converter(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        if (ocorrencia == null || ocorrencia.invoice() == null) {
            throw new IllegalStateException("Ocorrência SELIA sem dados de nota fiscal");
        }

        String chaveNfe = obrigatorio(ocorrencia.invoice().key(), "invoice.key");
        String numeroPedido = obterNumeroPedido(ocorrencia);
        OffsetDateTime dataOcorrencia = ocorrencia.occurrenceAt();
        if (dataOcorrencia == null) {
            throw new IllegalStateException("occurrence_at ausente para envio SELIA");
        }

        SeliaTrackingAttachmentDTO comprovanteDto = new SeliaTrackingAttachmentDTO(
                obterUrlComprovante(comprovante),
                obrigatorio(receiptType, "SELIA_INTELIPOST_RECEIPT_TYPE"),
                "canhoto-" + chaveNfe + ".jpg",
                obrigatorio(receiptMimeType, "SELIA_INTELIPOST_RECEIPT_MIME_TYPE")
        );
        SeliaTrackingEventDTO evento = new SeliaTrackingEventDTO(
                dataOcorrencia.toString(),
                obrigatorio(deliveryEventCode, "SELIA_INTELIPOST_DELIVERY_EVENT_CODE"),
                descricaoOcorrencia(ocorrencia),
                List.of(comprovanteDto)
        );

        return new SeliaAddEventsRequestDTO(
                chaveNfe,
                ocorrencia.invoice().series(),
                ocorrencia.invoice().number(),
                numeroPedido,
                numeroPedido,
                List.of(evento)
        );
    }

    private String obterNumeroPedido(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia.orderNumber() != null && !ocorrencia.orderNumber().isBlank()) {
            return ocorrencia.orderNumber().trim();
        }

        if (ocorrencia.freight() != null
                && ocorrencia.freight().orderNumber() != null
                && !ocorrencia.freight().orderNumber().isBlank()) {
            return ocorrencia.freight().orderNumber().trim();
        }

        throw new IllegalStateException(
                "order_number ausente na ocorrência ESL; a Intelipost exige volume_number igual ao número do pedido"
        );
    }

    private String obterUrlComprovante(ComprovanteEslDTO comprovante) {
        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            throw new IllegalStateException("Comprovante de entrega ausente para envio SELIA");
        }

        ComprovanteEslItemDTO primeiro = comprovante.data().get(0);
        if (primeiro == null || primeiro.imageUrl() == null || primeiro.imageUrl().isBlank()) {
            throw new IllegalStateException("URL do comprovante de entrega ausente para envio SELIA");
        }

        return primeiro.imageUrl().trim();
    }

    private String descricaoOcorrencia(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia.occurrence() == null
                || ocorrencia.occurrence().description() == null
                || ocorrencia.occurrence().description().isBlank()) {
            return "ENTREGA REALIZADA";
        }

        return ocorrencia.occurrence().description().trim();
    }

    private String obterChaveNfe(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.invoice() == null || ocorrencia.invoice().key() == null) {
            return "NAO_INFORMADO";
        }

        return ocorrencia.invoice().key();
    }

    private Set<String> obterWhitelistNfe() {
        if (nfeWhitelist == null || nfeWhitelist.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(nfeWhitelist.split(","))
                .map(String::trim)
                .filter(chave -> !chave.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String obrigatorio(String valor, String nomeConfiguracao) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(nomeConfiguracao + " não configurado");
        }

        return valor.trim();
    }

    private boolean erroDuplicidade(FeignException erro) {
        String mensagem = erro.contentUTF8();
        if (mensagem == null || mensagem.isBlank()) {
            mensagem = erro.getMessage();
        }

        String normalizada = mensagem == null ? "" : mensagem.toLowerCase(Locale.ROOT);
        return normalizada.contains("duplic")
                || normalizada.contains("already exists")
                || normalizada.contains("already sent");
    }

    private void aguardarRateLimit(FeignException erro, String chaveNfe) {
        long esperaMs = obterEsperaRateLimitMs(erro);
        log.warn("[SELIA] NF {}: HTTP 429 recebido. Aguardando {} ms antes da nova tentativa.", chaveNfe, esperaMs);
        try {
            Thread.sleep(esperaMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aguardar rate limit SELIA foi interrompido", e);
        }
    }

    private long obterEsperaRateLimitMs(FeignException erro) {
        String reset = erro.responseHeaders().entrySet().stream()
                .filter(entry -> "ratelimit-reset".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
        if (reset != null) {
            try {
                return Math.max(0L, Long.parseLong(reset.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // Usa o fallback configurado quando o header não for numérico.
            }
        }

        return Math.max(0L, rateLimitFallbackMs);
    }
}
