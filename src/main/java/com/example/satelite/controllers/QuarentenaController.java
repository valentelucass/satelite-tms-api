package com.example.satelite.controllers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.satelite.dto.etl.QuarentenaErroManualDTO;
import com.example.satelite.dto.etl.QuarentenaHistoricoDTO;
import com.example.satelite.dto.etl.QuarentenaReprocessamentoResponseDTO;
import com.example.satelite.services.etl.QuarentenaService;
import com.example.satelite.services.etl.QuarentenaService.ResultadoReprocessamento;
import com.example.satelite.utils.CsvStreamWriter;

@RestController
@RequestMapping("/api/etl/quarentena")
public class QuarentenaController {

    private final QuarentenaService quarentenaService;

    public QuarentenaController(QuarentenaService quarentenaService) {
        this.quarentenaService = quarentenaService;
    }

    @GetMapping("/erros")
    public Page<QuarentenaErroManualDTO> listarErrosManuais(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "100") int tamanho,
            @RequestParam(required = false) List<String> destino
    ) {
        int paginaNormalizada = Math.max(0, pagina);
        int tamanhoNormalizado = Math.max(1, Math.min(tamanho, 500));
        return quarentenaService.buscarErrosManuais(PageRequest.of(paginaNormalizada, tamanhoNormalizado), destino);
    }

    @GetMapping("/historico")
    public Page<QuarentenaHistoricoDTO> listarHistoricoRepescagens(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "100") int tamanho,
            @RequestParam(required = false) LocalDate dataInicial,
            @RequestParam(required = false) LocalDate dataFinal,
            @RequestParam(required = false) List<String> destino
    ) {
        return quarentenaService.buscarHistoricoRepescagens(
                PageRequest.of(Math.max(0, pagina), Math.max(1, Math.min(tamanho, 500))),
                destino,
                dataInicial,
                dataFinal
        );
    }

    @GetMapping(value = "/historico/exportacao", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportarHistoricoRepescagens(
            @RequestParam(required = false) LocalDate dataInicial,
            @RequestParam(required = false) LocalDate dataFinal,
            @RequestParam(required = false) List<String> destino
    ) {
        StreamingResponseBody corpo = outputStream -> {
            CsvStreamWriter csv = new CsvStreamWriter(outputStream);
            csv.escreverCabecalho("Destino", "NF", "Etapa", "Entrou em quarentena", "Repescagem", "Resultado", "Motivo");
            quarentenaService.exportarHistoricoRepescagens(destino, dataInicial, dataFinal, item -> csv.escreverLinha(
                    item.destino(), item.numeroNf(), item.etapa(), item.entradaQuarentenaEm(),
                    item.reprocessadoEm(), item.resultado(), item.motivo()
            ));
            csv.flush();
        };
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("historico-repescagens-integracoes.csv", StandardCharsets.UTF_8).build().toString())
                .body(corpo);
    }

    @GetMapping(value = "/erros/exportacao", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportarErrosManuais(
            @RequestParam(required = false) List<String> destino
    ) {
        StreamingResponseBody corpo = outputStream -> {
            CsvStreamWriter csv = new CsvStreamWriter(outputStream);
            csv.escreverCabecalho("Destino", "NF", "Acao necessaria", "Chave NF-e", "Tentativas", "Ultima tentativa");
            quarentenaService.exportarErrosManuais(destino, item -> csv.escreverLinha(
                    item.destino(), item.numeroNf(), item.erroLimpo(), item.chaveNfe(),
                    item.tentativas(), item.dataUltimaTentativa()
            ));
            csv.flush();
        };

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("quarentena-integracoes.csv", StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(corpo);
    }

    @PostMapping("/{destino}/reprocessar")
    public ResponseEntity<QuarentenaReprocessamentoResponseDTO> reprocessar(@PathVariable String destino) {
        try {
            ResultadoReprocessamento resultado = quarentenaService.reprocessar(destino);
            return ResponseEntity.ok(new QuarentenaReprocessamentoResponseDTO(
                    resultado.destino(),
                    resultado.quantidadeNotas(),
                    resultado.quantidadeNotas()
                            + " nota(s) retirada(s) da quarentena e prontas para o proximo ciclo do robo."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new QuarentenaReprocessamentoResponseDTO(
                    null,
                    0,
                    e.getMessage()
            ));
        }
    }
}
