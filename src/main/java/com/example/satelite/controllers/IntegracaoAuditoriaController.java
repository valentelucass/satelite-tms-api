package com.example.satelite.controllers;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.satelite.dto.auditoria.AuditoriaIntegracoesClientesResponseDTO;
import com.example.satelite.dto.auditoria.IntegracaoEvolucaoDiariaDTO;
import com.example.satelite.dto.auditoria.ResumoTabelaIntegracaoDTO;
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

    @GetMapping("/integracoes-clientes/evolucao-diaria")
    public List<IntegracaoEvolucaoDiariaDTO> consultarEvolucaoDiaria(
            @RequestParam String dataInicial,
            @RequestParam String dataFinal
    ) {
        return integracaoAuditoriaService.consultarEvolucaoDiaria(dataInicial, dataFinal);
    }

    @GetMapping("/integracoes-clientes/resumo-tabelas")
    public List<ResumoTabelaIntegracaoDTO> consultarResumoTabelas(
            @RequestParam String dataInicial,
            @RequestParam String dataFinal
    ) {
        return integracaoAuditoriaService.consultarResumoTabelas(dataInicial, dataFinal);
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
