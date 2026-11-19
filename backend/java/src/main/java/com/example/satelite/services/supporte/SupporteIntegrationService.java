package com.example.satelite.services.supporte;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.SupporteClient;
import com.example.satelite.dto.rodogarcia.ComprovanteEslDTO;
import com.example.satelite.dto.rodogarcia.ComprovanteEslItemDTO;
import com.example.satelite.dto.rodogarcia.EslOcorrenciaDTO;
import com.example.satelite.dto.supporte.SupporteCteDTO;
import com.example.satelite.dto.supporte.SupporteEventoDTO;
import com.example.satelite.dto.supporte.SupporteNfDTO;
import com.example.satelite.dto.supporte.SupporteOcorrenciaRequestDTO;
import com.example.satelite.dto.supporte.SupporteOcorrenciaResponseDTO;
import com.example.satelite.dto.supporte.SupporteRetornoDTO;
import com.example.satelite.services.ResultadoIntegracao;
import com.example.satelite.utils.ImageDownloader;
import com.example.satelite.utils.ImageUtils;

@Service
public class SupporteIntegrationService {

    private static final DateTimeFormatter DATA_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo"));
    private static final String PREFIXO_JPEG_BASE64 = "data:image/jpeg;base64,";

    private final SupporteClient supporteClient;
    private final ImageDownloader imageDownloader;

    @Value("${SUPPORTE_API_AUTHORIZATION:}")
    private String authorization;

    @Value("${SUPPORTE_CNPJ_TRANSPORTADORA:}")
    private String cnpjTransportadora;

    @Value("${SUPPORTE_CNPJ_PAGADORES:}")
    private String cnpjPagadores;

    @Value("${app.supporte.nfe-whitelist-enabled:false}")
    private boolean whitelistEnabled;

    @Value("${app.supporte.nfe-whitelist:}")
    private String nfeWhitelist;

    public SupporteIntegrationService(SupporteClient supporteClient, ImageDownloader imageDownloader) {
        this.supporteClient = supporteClient;
        this.imageDownloader = imageDownloader;
    }

    public ResultadoIntegracao processarOcorrencia(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        SupporteOcorrenciaRequestDTO request = converter(ocorrencia, comprovante);
        List<SupporteOcorrenciaResponseDTO> resposta = supporteClient.enviarOcorrencia(
                obrigatorio(authorization, "SUPPORTE_API_AUTHORIZATION"),
                request
        );
        SupporteRetornoDTO retorno = obterRetorno(resposta);
        if (retorno.codigo() == null || retorno.codigo() != 200 || retorno.descricao() == null || retorno.descricao().isBlank()) {
            throw new IllegalStateException("Resposta inválida da API SUPPORTE");
        }

        String descricao = normalizarDescricao(retorno.descricao());
        if (erroReenviavel(descricao)) {
            throw new IllegalStateException("SUPPORTE recusou temporariamente a ocorrência: " + retorno.descricao());
        }
        if (erroCorrigivel(descricao)) {
            throw new IllegalStateException("SUPPORTE recusou o payload: " + retorno.descricao());
        }
        if (!resultadoAceito(descricao)) {
            throw new IllegalStateException("Resposta SUPPORTE não conciliável: " + retorno.descricao());
        }

        return Boolean.TRUE.equals(retorno.comprovanteRecebido())
                ? ResultadoIntegracao.enviado()
                : ResultadoIntegracao.parcialCanhotoPendente(ResultadoIntegracao.STATUS_SUCESSO,
                        "Ocorrência aceita; comprovante ainda não recebido pela SUPPORTE");
    }

    public boolean notaFiscalPermitida(EslOcorrenciaDTO ocorrencia) {
        String chaveCte = obterChaveCte(ocorrencia);
        if (!chaveCte.matches("\\d{44}")) {
            return false;
        }
        if (!obterCnpjsPagadores().contains(chaveCte.substring(6, 20))) {
            return false;
        }
        return !whitelistEnabled || obterWhitelistNfe().contains(obterChaveNfe(ocorrencia));
    }

    SupporteOcorrenciaRequestDTO converter(EslOcorrenciaDTO ocorrencia, ComprovanteEslDTO comprovante) {
        if (ocorrencia == null || ocorrencia.invoice() == null || ocorrencia.freight() == null) {
            throw new IllegalStateException("Ocorrência SUPPORTE sem NF-e ou CT-e");
        }
        if (ocorrencia.occurrence() == null || ocorrencia.occurrence().code() == null
                || ocorrencia.occurrence().code() != 1) {
            throw new IllegalStateException("SUPPORTE aceita somente occurrence.code == 1");
        }
        String chaveNfe = obrigatorio(ocorrencia.invoice().key(), "invoice.key");
        String chaveCte = obrigatorio(ocorrencia.freight().cteKey(), "freight.cteKey");
        validarChave(chaveNfe, "NF-e");
        validarChave(chaveCte, "CT-e");
        if (!notaFiscalPermitida(ocorrencia)) {
            throw new IllegalStateException("NF-e não pertence aos pagadores SUPPORTE configurados");
        }
        OffsetDateTime dataOcorrencia = ocorrencia.occurrenceAt();
        if (dataOcorrencia == null) {
            throw new IllegalStateException("occurrence_at ausente para envio SUPPORTE");
        }

        return new SupporteOcorrenciaRequestDTO(
                DATA_FORMATTER.format(OffsetDateTime.now()),
                cnpjSomenteDigitos(obrigatorio(cnpjTransportadora, "SUPPORTE_CNPJ_TRANSPORTADORA"), "CNPJ da transportadora"),
                chaveCte.substring(6, 20),
                new SupporteNfDTO(
                        inteiroPositivo(ocorrencia.invoice().series(), "Série da NF-e"),
                        inteiroPositivo(ocorrencia.invoice().number(), "Número da NF-e"),
                        chaveNfe,
                        valorOpcional(ocorrencia.orderNumber(), ocorrencia.freight().orderNumber())
                ),
                new SupporteCteDTO(
                        Integer.parseInt(chaveCte.substring(22, 25)),
                        Integer.parseInt(chaveCte.substring(25, 34)),
                        chaveCte
                ),
                new SupporteEventoDTO(
                        DATA_FORMATTER.format(dataOcorrencia),
                        1,
                        descricaoObrigatoria(ocorrencia),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        obterImagemComprovante(comprovante, chaveCte),
                        null
                )
        );
    }

    private String obterImagemComprovante(ComprovanteEslDTO comprovante, String chaveCte) {
        if (comprovante == null || comprovante.data() == null || comprovante.data().isEmpty()) {
            return null;
        }
        ComprovanteEslItemDTO item = comprovante.data().get(0);
        if (item == null || item.imageUrl() == null || item.imageUrl().isBlank()) {
            return null;
        }
        try {
            byte[] bytes = imageDownloader.baixarImagemDaUrl(item.imageUrl().trim(), chaveCte);
            return PREFIXO_JPEG_BASE64 + Base64.getEncoder().encodeToString(ImageUtils.converterParaJpeg(bytes));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download do comprovante SUPPORTE interrompido", e);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar comprovante SUPPORTE: " + e.getMessage(), e);
        }
    }

    private SupporteRetornoDTO obterRetorno(List<SupporteOcorrenciaResponseDTO> resposta) {
        if (resposta == null || resposta.isEmpty() || resposta.get(0) == null || resposta.get(0).retorno() == null) {
            throw new IllegalStateException("API SUPPORTE retornou corpo vazio");
        }
        return resposta.get(0).retorno();
    }

    private boolean resultadoAceito(String descricao) {
        return descricao.contains("OCORRENCIA PROCESSADA COM SUCESSO")
                || descricao.contains("NOTA FISCAL JA ESTAVA FINALIZADA")
                || descricao.contains("DOCUMENTO JA FOI FINALIZADO")
                || descricao.contains("OCORRENCIA FINALIZADORA JA LANCADA");
    }

    private boolean erroReenviavel(String descricao) {
        return descricao.contains("NAO FOI POSSIVEL PROCESSAR A REQUISICAO - ERRO 1")
                || descricao.contains("NAO FOI POSSIVEL PROCESSAR A REQUISICAO - ERRO 2");
    }

    private boolean erroCorrigivel(String descricao) {
        return descricao.startsWith("ERRO:") || descricao.contains("CAMPO ");
    }

    private String normalizarDescricao(String descricao) {
        return Normalizer.normalize(descricao, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }

    private String descricaoObrigatoria(EslOcorrenciaDTO ocorrencia) {
        if (ocorrencia.occurrence() == null || ocorrencia.occurrence().description() == null
                || ocorrencia.occurrence().description().isBlank()) {
            return "ENTREGUE";
        }
        String descricao = ocorrencia.occurrence().description().trim();
        return descricao.length() > 500 ? descricao.substring(0, 500) : descricao;
    }

    private String valorOpcional(String primeiro, String segundo) {
        if (primeiro != null && !primeiro.isBlank()) {
            return primeiro.trim();
        }
        return segundo == null || segundo.isBlank() ? null : segundo.trim();
    }

    private int inteiroPositivo(String valor, String campo) {
        try {
            int numero = Integer.parseInt(obrigatorio(valor, campo));
            if (numero <= 0) {
                throw new NumberFormatException();
            }
            return numero;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(campo + " deve ser inteiro positivo", e);
        }
    }

    private String cnpjSomenteDigitos(String valor, String campo) {
        String cnpj = valor.replaceAll("\\D", "");
        if (!cnpj.matches("\\d{14}")) {
            throw new IllegalStateException(campo + " deve ter 14 dígitos");
        }
        return cnpj;
    }

    private void validarChave(String chave, String documento) {
        if (!chave.matches("\\d{44}")) {
            throw new IllegalStateException("Chave de " + documento + " deve conter 44 dígitos");
        }
    }

    private String obterChaveNfe(EslOcorrenciaDTO ocorrencia) {
        return ocorrencia == null || ocorrencia.invoice() == null || ocorrencia.invoice().key() == null
                ? "" : ocorrencia.invoice().key().trim();
    }

    private String obterChaveCte(EslOcorrenciaDTO ocorrencia) {
        return ocorrencia == null || ocorrencia.freight() == null || ocorrencia.freight().cteKey() == null
                ? "" : ocorrencia.freight().cteKey().trim();
    }

    private Set<String> obterCnpjsPagadores() {
        return Arrays.stream((cnpjPagadores == null ? "" : cnpjPagadores).split(","))
                .map(valor -> valor.replaceAll("\\D", ""))
                .filter(valor -> valor.matches("\\d{14}"))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> obterWhitelistNfe() {
        return Arrays.stream((nfeWhitelist == null ? "" : nfeWhitelist).split(","))
                .map(valor -> valor == null ? "" : valor.trim())
                .filter(valor -> !valor.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String obrigatorio(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(campo + " não configurado");
        }
        return valor.trim();
    }
}
