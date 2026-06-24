package com.example.satelite.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.satelite.dto.auditoria.AuditoriaIntegracoesClientesResponseDTO;
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
            @RequestParam(defaultValue = "100") int tamanho
    ) {
        return integracaoAuditoriaService.consultarIntegracoesClientes(pagina, tamanho);
    }
}
