package com.example.satelite.controllers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.satelite.dto.etl.QuarentenaErroManualDTO;
import com.example.satelite.dto.etl.QuarentenaReprocessamentoResponseDTO;
import com.example.satelite.services.etl.QuarentenaService;
import com.example.satelite.services.etl.QuarentenaService.ResultadoReprocessamento;

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
            @RequestParam(defaultValue = "100") int tamanho
    ) {
        int paginaNormalizada = Math.max(0, pagina);
        int tamanhoNormalizado = Math.max(1, Math.min(tamanho, 500));
        return quarentenaService.buscarErrosManuais(PageRequest.of(paginaNormalizada, tamanhoNormalizado));
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
