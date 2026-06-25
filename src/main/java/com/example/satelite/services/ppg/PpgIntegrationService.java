package com.example.satelite.services.ppg;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import com.example.satelite.clients.PpgClient;
import com.example.satelite.dto.ppg.PpgFotoDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaRequestDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaResponseDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.utils.ImageUtils;

import feign.FeignException;

@Service
public class PpgIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(PpgIntegrationService.class);
    private static final DateTimeFormatter PPG_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final PpgClient ppgClient;
    private final PpgAuthService ppgAuthService;
    private final ImageDownloader imageDownloader;
    private final ImageUtils imageUtils;

    @Value("${PPG_ENTREGADOR_ID}")
    private Integer entregadorId;

    @Value("${PPG_CNPJ_TRANSPORTADORA}")
    private String cnpjTransportadora;

    @Value("${app.ppg.nfe-whitelist:}")
    private String nfeWhitelist;

    @Value("${app.ppg.nfe-whitelist-enabled:false}")
    private boolean whitelistEnabled;

    public PpgIntegrationService(
            PpgClient ppgClient,
            PpgAuthService ppgAuthService,
            ImageDownloader imageDownloader,
            ImageUtils imageUtils
    ) {
        this.ppgClient = ppgClient;
        this.ppgAuthService = ppgAuthService;
        this.imageDownloader = imageDownloader;
        this.imageUtils = imageUtils;
    }

    public ResultadoIntegracao processarOcorrencia(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        String chaveNfe = obterChaveNfe(ocorrencia);
        String cteKey = obterChaveCte(ocorrencia);

        if (!notaFiscalPermitida(ocorrencia)) {
            log.warn("⚠️ [PPG] NF {} ignorada por não estar na Whitelist de Produção", chaveNfe);
            return ResultadoIntegracao.ignorado();
        }

        try {
            String urlImagem = obterUrlImagem(comprovante);
            log.info("⬇️ [PPG] NF {}: Baixando imagem do canhoto... CTe={}", chaveNfe, cteKey);
            byte[] imagemOriginalBytes = imageDownloader.baixarImagemDaUrl(urlImagem, cteKey);
            log.info("🖼️ [PPG] NF {}: Imagem baixada com sucesso ({} bytes).", chaveNfe, imagemOriginalBytes.length);

            String imagemPpgBase64 = imageUtils.converterParaBase64Ppg(imagemOriginalBytes);
            log.info("🛠️ [PPG] NF {}: Imagem convertida para o padrão OK Entrega.", chaveNfe);

            String token = ppgAuthService.obterTokenValido();
            PpgOcorrenciaRequestDTO dtoConvertido = converterParaPpg(ocorrencia, imagemPpgBase64);
            log.info("📤 [PPG] NF {}: Enviando ocorrência para OK Entrega...", chaveNfe);
            return enviarOcorrenciaComConciliacao(token, dtoConvertido, chaveNfe, cteKey);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.error("❌ [PPG] NF {}: Erro ao processar - {}. CTe={}", chaveNfe, e.getMessage(), cteKey);
            throw new IllegalStateException("Falha no processamento da ocorrência PPG: " + e.getMessage(), e);
        }
    }

    private ResultadoIntegracao enviarOcorrenciaComConciliacao(
            String token,
            PpgOcorrenciaRequestDTO dtoConvertido,
            String chaveNfe,
            String cteKey
    ) throws Exception {
        try {
            PpgOcorrenciaResponseDTO response = ppgClient.enviarOcorrencia(token, dtoConvertido);
            log.info(
                    "✅ [PPG] NF {}: Ocorrência e canhoto enviados com sucesso! ocorrenciaentrega_id={} statusbaixa={}",
                    chaveNfe,
                    response.ocorrenciaentregaId(),
                    response.statusbaixa()
            );
            return ResultadoIntegracao.enviado();
        } catch (Exception e) {
            if (erroDuplicidadePpg(e)) {
                log.info("Aviso: Destino informou duplicidade. Conciliando... [PPG] NF {} CTe={}", chaveNfe, cteKey);
                return ResultadoIntegracao.enviado();
            }

            throw e;
        }
    }

    private boolean erroDuplicidadePpg(Throwable erro) {
        String textoErro = normalizarTextoErro(extrairTextoErro(erro));
        return textoErro.contains("1721")
                || textoErro.contains("duplicad")
                || textoErro.contains("duplicidade");
    }

    private String extrairTextoErro(Throwable erro) {
        StringBuilder texto = new StringBuilder();
        Throwable atual = erro;

        while (atual != null) {
            if (atual.getMessage() != null) {
                texto.append(atual.getMessage()).append(' ');
            }

            if (atual instanceof FeignException feignException) {
                texto.append(feignException.contentUTF8()).append(' ');
            }

            if (atual instanceof HttpClientErrorException httpClientErrorException) {
                texto.append(httpClientErrorException.getResponseBodyAsString(StandardCharsets.UTF_8)).append(' ');
            }

            Throwable causa = atual.getCause();
            atual = causa == atual ? null : causa;
        }

        return texto.toString();
    }

    private String normalizarTextoErro(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }

        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    public boolean notaFiscalPermitida(EslOcorrenciaDTO ocorrencia) {
        if (!whitelistEnabled) {
            return true;
        }

        return obterWhitelistNfe().contains(obterChaveNfe(ocorrencia));
    }

    private PpgOcorrenciaRequestDTO converterParaPpg(EslOcorrenciaDTO origem, String imagemPpgBase64) {
        PpgFotoDTO fotoCanhoto = new PpgFotoDTO(
            "C",
            imagemPpgBase64,
            "data:image/jpeg;base64",
            "jpeg"
        );

        List<PpgFotoDTO> listaDeFotos = List.of(fotoCanhoto);
        String dataRegistro = formatarDataPpg(origem.occurrenceAt());

        return new PpgOcorrenciaRequestDTO(
            origem.invoice().key(),  // documento (Chave NFe de origem)
            1,                       // tipoocorrenciaId (1 = Entrega)
            "F",                     // tipoentrega (F = Entrega final)
            obterCnpjTransportadora(),
            this.entregadorId,       // entregadorId (Pego do .env)
            dataRegistro,            // dtentrega
            null,                    // dtreentrega
            null,                    // dtsinistro
            dataRegistro,            // dtregistro
            "I",                     // tipoentrada (I = Integração)
            "0",                     // latitude (Mande "0" caso não tenha no DTO atual)
            "0",                     // longitude
            null,                    // motivoocorrenciaId
            listaDeFotos             // ocorrenciaentregafoto
        );
    }

    private String formatarDataPpg(OffsetDateTime data) {
        if (data == null) {
            throw new IllegalStateException("Data da ocorrencia ausente para envio PPG");
        }

        return PPG_DATE_FORMATTER.format(data);
    }

    private String obterCnpjTransportadora() {
        if (cnpjTransportadora == null || cnpjTransportadora.isBlank()) {
            throw new IllegalStateException("PPG_CNPJ_TRANSPORTADORA nao configurado");
        }

        return cnpjTransportadora;
    }

    private String obterUrlImagem(ComprovanteEslDTO comprovante) {
        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            throw new IllegalStateException("Comprovante de entrega sem imagem");
        }

        ComprovanteEslItemDTO primeiroComprovante = comprovante.data().get(0);
        if (primeiroComprovante == null || primeiroComprovante.imageUrl() == null || primeiroComprovante.imageUrl().isBlank()) {
            throw new IllegalStateException("URL da imagem do comprovante ausente");
        }

        return primeiroComprovante.imageUrl();
    }

    private String obterChaveNfe(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.invoice() == null || ocorrencia.invoice().key() == null) {
            return "NAO_INFORMADO";
        }

        return ocorrencia.invoice().key();
    }

    private String obterChaveCte(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia == null || ocorrencia.freight() == null || ocorrencia.freight().cteKey() == null) {
            return "NAO_INFORMADO";
        }

        return ocorrencia.freight().cteKey();
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
}
