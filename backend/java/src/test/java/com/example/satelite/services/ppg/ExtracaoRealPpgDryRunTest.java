package com.example.satelite.services.ppg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.satelite.clients.PpgClient;
import com.example.satelite.dto.ppg.PpgLoginRequestDTO;
import com.example.satelite.dto.ppg.PpgLoginResponseDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaRequestDTO;
import com.example.satelite.dto.ppg.PpgOcorrenciaResponseDTO;
import com.example.satelite.models.ControleCursor;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.ControleCursorRepository;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.etl.ExecucaoEtlRequest;
import com.example.satelite.services.etl.ExecucaoEtlRequest.ModoExecucao;
import com.example.satelite.services.etl.OrquestradorEtlService;
import tools.jackson.databind.ObjectMapper;

@Disabled("Teste manual de integracao - acessa rede real e disco local (S3/C:\\temp). Rodar apenas sob demanda.")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "APP_SCHEDULER_ENABLED=false",
        "APP_CICLO_UNICO=false",
        "APP_PPG_ENABLED=true",
        "APP_VEDACIT_ENABLED=false",
        "APP_E2E_IMAGE_TEST_MODE=false",
        "ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS=0",
        "IMAGE_DOWNLOAD_RETRY_DELAY_MS=0",
        "app.ppg.nfe-whitelist-enabled=true",
        "app.ppg.nfe-whitelist=35260643996693000127550170004223891100032056",
        "app.ppg.image-crop-start-ratio=0.60",
        "app.ppg.image-crop-height-ratio=0.20"
})
class ExtracaoRealPpgDryRunTest {

    private static final String CHAVE_NFE = "35260643996693000127550170004223891100032056";
    private static final String PREFIXO_BASE64_PPG = "data:image/jpeg;base64,";
    private static final Path PAYLOAD_JSON = Path.of("C:\\temp\\ppg_payload_NF_352606.json");
    private static final Path IMAGEM_JPG = Path.of("C:\\temp\\ppg_imagem_NF_352606.jpg");

    @Autowired
    private OrquestradorEtlService orquestradorEtlService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PpgClient ppgClient;

    @MockitoBean
    private LogIntegracaoRepository logIntegracaoRepository;

    @MockitoBean
    private ControleCursorRepository controleCursorRepository;

    @MockitoBean
    private IntegracaoAuditoriaQueryRepository integracaoAuditoriaQueryRepository;

    @Test
    void deveExecutarFluxoRealPpgSequestrandoPayloadAntesDoPost() throws Exception {
        prepararMocksDeBorda();

        ExecucaoEtlRequest requestDryRun = new ExecucaoEtlRequest(
                ModoExecucao.INCREMENTAL,
                null,
                null,
                Set.of("PPG"),
                false,
                false,
                false,
                1
        );

        orquestradorEtlService.executarFluxosComResultado(requestDryRun);

        ArgumentCaptor<PpgOcorrenciaRequestDTO> payloadCaptor =
                ArgumentCaptor.forClass(PpgOcorrenciaRequestDTO.class);
        org.mockito.Mockito.verify(ppgClient).enviarOcorrencia(anyString(), payloadCaptor.capture());

        PpgOcorrenciaRequestDTO payloadFinal = payloadCaptor.getValue();
        assertNotNull(payloadFinal, "Payload final da PPG nao foi capturado");
        assertEquals(CHAVE_NFE, payloadFinal.documento());
        assertNotNull(payloadFinal.ocorrenciaEntregaFoto(), "Payload final veio sem lista de fotos");
        assertFalse(payloadFinal.ocorrenciaEntregaFoto().isEmpty(), "Payload final veio sem foto do canhoto");

        String fotoBase64 = payloadFinal.ocorrenciaEntregaFoto().get(0).foto();
        assertNotNull(fotoBase64, "Foto Base64 ausente no DTO final da PPG");

        Files.createDirectories(PAYLOAD_JSON.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(PAYLOAD_JSON.toFile(), payloadFinal);

        byte[] imagemBytes = Base64.getDecoder().decode(removerPrefixoDataUri(fotoBase64));
        Files.write(IMAGEM_JPG, imagemBytes);

        System.out.printf("%n[PPG DRY RUN] Payload JSON gravado em: %s%n", PAYLOAD_JSON);
        System.out.printf("[PPG DRY RUN] Imagem JPG gravada em: %s%n", IMAGEM_JPG);
    }

    private void prepararMocksDeBorda() {
        when(ppgClient.login(any(PpgLoginRequestDTO.class)))
                .thenReturn(new PpgLoginResponseDTO("dry-run-token-sem-envio-real", 1209600, null, null));

        when(ppgClient.enviarOcorrencia(anyString(), any(PpgOcorrenciaRequestDTO.class)))
                .thenReturn(new PpgOcorrenciaResponseDTO(CHAVE_NFE, "DRY_RUN", "SIMULADO", 1, null));

        when(logIntegracaoRepository.findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(
                anyString(),
                anyString()
        )).thenReturn(List.of());

        when(logIntegracaoRepository.findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc(
                anyString(),
                any()
        )).thenReturn(Optional.empty());

        when(logIntegracaoRepository.save(any(LogIntegracaoModel.class)))
                .thenAnswer(invocation -> {
                    LogIntegracaoModel log = invocation.getArgument(0);
                    if (log.getId() == null) {
                        log.setId(1L);
                    }
                    return log;
                });

        when(controleCursorRepository.findBySistemaDestino(anyString())).thenReturn(Optional.empty());
        when(controleCursorRepository.save(any(ControleCursor.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String removerPrefixoDataUri(String fotoBase64) {
        if (fotoBase64.startsWith(PREFIXO_BASE64_PPG)) {
            return fotoBase64.substring(PREFIXO_BASE64_PPG.length());
        }

        int separadorDataUri = fotoBase64.indexOf(',');
        if (separadorDataUri >= 0) {
            return fotoBase64.substring(separadorDataUri + 1);
        }

        return fotoBase64;
    }
}
