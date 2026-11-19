package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.util.function.Supplier;
import java.time.OffsetDateTime;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.dto.rodogarcia.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecuperacaoDirigidaVedacitRunnerTest {

    @Test void manifestoNaoTruncaMilParesERejeitaLinhaInvalida() throws Exception {
        Path arquivo = diretorioTemporario.resolve("pares.txt");
        Files.write(arquivo, java.util.stream.IntStream.rangeClosed(1, 1000)
                .mapToObj(i -> String.format("%044d;%044d", i, i)).toList());
        assertEquals(1000, RecuperacaoDirigidaVedacitRunner.carregarAlvos(arquivo).size());
        Files.writeString(arquivo, "123;456");
        assertThrows(IllegalArgumentException.class, () -> RecuperacaoDirigidaVedacitRunner.carregarAlvos(arquivo));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void percorrePaginasEEnviaSomenteCteExatoDoManifesto(boolean previa) {
        String nfe = "1".repeat(44), cte = "2".repeat(44), outro = "3".repeat(44);
        var origem = mock(RodogarciaClient.class);
        var politica = mock(EslRequestPolicyService.class);
        var registros = mock(EtlRegistroService.class);
        var env = new MockEnvironment().withProperty("vedacit.recovery.preview", Boolean.toString(previa))
                .withProperty("vedacit.recovery.interval-ms", "0");
        when(politica.executarComTelemetria(any(), any())).thenAnswer(i -> {
            Supplier<EslLoteResponseDTO> chamada = i.getArgument(1); return chamada.get();
        });
        var errado = ocorrencia(nfe, outro);
        var certo = ocorrencia(nfe, cte);
        when(origem.buscarOcorrencias("Bearer teste", null, nfe, null, 110))
                .thenReturn(new EslLoteResponseDTO(List.of(errado), new EslPagingDTO(50L, 1)));
        when(origem.buscarOcorrencias("Bearer teste", 50L, nfe, null, 110))
                .thenReturn(new EslLoteResponseDTO(List.of(certo, certo), new EslPagingDTO(null, 2)));
        when(registros.ehCteEmitido(any())).thenReturn(true);
        when(registros.processarEmissaoXmlVedacit(any(), any(), eq(certo))).thenReturn(ResultadoRegistro.ENVIADO);
        var runner = new RecuperacaoDirigidaVedacitRunner(origem, politica, registros, env, mock(ConfigurableApplicationContext.class));
        var resultado = runner.recuperar(List.of(nfe + ";" + cte), "teste");
        assertEquals(previa ? 0 : 1, resultado.enviadas());
        verify(registros, times(previa ? 0 : 1)).processarEmissaoXmlVedacit(any(), any(), eq(certo));
        verify(registros, never()).processarEmissaoXmlVedacit(any(), any(), eq(errado));
    }

    private EslOcorrenciaDTO ocorrencia(String nfe, String cte) {
        return new EslOcorrenciaDTO(10L, OffsetDateTime.parse("2026-09-01T10:00:00-03:00"),
                new EslInvoiceDTO(1L, nfe, "1", "1"), new EslFreightDTO(2L, cte), new EslOccurrenceDefDTO(3L, 110, "Emissão"));
    }

    @TempDir
    Path diretorioTemporario;

    @Test
    void deveLerSomenteChavesNfeValidasSemDuplicidadeERespeitandoLimite() throws Exception {
        String chaveA = "35260860642774001209550010002344161634484264";
        String chaveB = "35260860642774001209550010002344171634484265";
        Path arquivo = diretorioTemporario.resolve("nfes.txt");
        Files.writeString(arquivo, String.join(System.lineSeparator(), chaveA, "invalida", chaveA, chaveB));

        assertEquals(List.of(chaveA), RecuperacaoDirigidaVedacitRunner.carregarChaves(arquivo, 1));
        assertEquals(List.of(chaveA, chaveB), RecuperacaoDirigidaVedacitRunner.carregarChaves(arquivo, 50));
    }
}
