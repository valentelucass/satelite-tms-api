package com.example.satelite.controllers;

import java.util.List;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.satelite.dto.auditoria.AuditoriaIntegracoesClientesResponseDTO;
import com.example.satelite.dto.auditoria.IntegracaoEvolucaoDiariaDTO;
import com.example.satelite.dto.auditoria.ResumoTabelaIntegracaoDTO;
import com.example.satelite.dto.auditoria.WorkSftpClienteStatusDTO;
import com.example.satelite.dto.auditoria.WorkSftpClienteExecucoesPaginadasDTO;
import com.example.satelite.utils.CsvStreamWriter;
import com.example.satelite.services.auditoria.IntegracaoAuditoriaService;

@RestController
@RequestMapping("/api/auditoria")
public class IntegracaoAuditoriaController {

    private final IntegracaoAuditoriaService integracaoAuditoriaService;

    public IntegracaoAuditoriaController(IntegracaoAuditoriaService integracaoAuditoriaService) {
        this.integracaoAuditoriaService = integracaoAuditoriaService;
    }

    @GetMapping("/integracoes-clientes")
    public AuditoriaIntegracoesClientesResponseDTO consultarIntegracoesClientes(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "100") int tamanho,
            @RequestParam(required = false) String dataInicial,
            @RequestParam(required = false) String dataFinal,
            @RequestParam MultiValueMap<String, String> params
    ) {
        return integracaoAuditoriaService.consultarIntegracoesClientes(pagina, tamanho, dataInicial, dataFinal, params);
    }

    @GetMapping(value = "/integracoes-clientes/exportacao", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportarIntegracoesClientes(
            @RequestParam(required = false) String dataInicial,
            @RequestParam(required = false) String dataFinal,
            @RequestParam MultiValueMap<String, String> params
    ) {
        StreamingResponseBody corpo = outputStream -> {
            CsvStreamWriter csv = new CsvStreamWriter(outputStream);
            csv.escreverCabecalho(
                    "ID", "Sistema destino", "Ocorrencia", "Frete", "Chave NF-e", "NF", "Serie",
                    "Status XML", "Status comprovante", "Mensagem XML", "Mensagem comprovante",
                    "Data processamento", "Data XML", "Data comprovante", "Canhoto disponivel"
            );
            integracaoAuditoriaService.exportarIntegracoesClientes(dataInicial, dataFinal, params, item ->
                    csv.escreverLinha(
                            item.id(), item.sistemaDestino(), item.occurrenceId(), item.freightId(), item.chaveNfe(),
                            item.numeroNf(), item.serieNf(), item.statusDados(), item.statusCanhoto(),
                            item.mensagemErroDados(), item.mensagemErroCanhoto(), item.dataProcessamento(),
                            item.dataProcessamentoDados(), item.dataProcessamentoCanhoto(), item.possuiImagemPayload()
                    )
            );
            csv.flush();
        };

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("integracoes.csv", StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(corpo);
    }

    @GetMapping("/integracoes-clientes/evolucao-diaria")
    public List<IntegracaoEvolucaoDiariaDTO> consultarEvolucaoDiaria(
            @RequestParam String dataInicial,
            @RequestParam String dataFinal,
            @RequestParam(required = false) List<String> destino
    ) {
        return integracaoAuditoriaService.consultarEvolucaoDiaria(dataInicial, dataFinal, destino);
    }

    @GetMapping("/integracoes-clientes/resumo-tabelas")
    public List<ResumoTabelaIntegracaoDTO> consultarResumoTabelas(
            @RequestParam String dataInicial,
            @RequestParam String dataFinal,
            @RequestParam(required = false) List<String> destino
    ) {
        return integracaoAuditoriaService.consultarResumoTabelas(dataInicial, dataFinal, destino);
    }

    @GetMapping("/vedacit-sftp/clientes")
    public List<WorkSftpClienteStatusDTO> consultarStatusVedacitSftp() {
        return integracaoAuditoriaService.consultarStatusWorkSftpClientes();
    }

    @GetMapping("/vedacit-sftp/execucoes")
    public WorkSftpClienteExecucoesPaginadasDTO consultarExecucoesVedacitSftp(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamanho,
            @RequestParam(required = false) String cliente,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dataInicial,
            @RequestParam(required = false) String dataFinal
    ) {
        return integracaoAuditoriaService.consultarHistoricoWorkSftpClientes(
                pagina, tamanho, cliente, status, dataInicial, dataFinal
        );
    }

    @GetMapping(value = "/logs/{id}/imagem", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> consultarImagemCanhoto(@PathVariable Long id) {
        return integracaoAuditoriaService.buscarImagemCanhoto(id)
                .map(imagem -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(imagem))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
