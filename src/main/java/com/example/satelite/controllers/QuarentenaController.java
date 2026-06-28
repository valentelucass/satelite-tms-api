package com.example.satelite.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
