package com.example.satelite.controllers;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.Resumo;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;

/** Consulta de auditoria: nao permite iniciar revisao, remover bloqueios ou reenviar documentos. */
@RestController
@RequestMapping("/api/auditoria/vedacit/reconciliacoes")
public class ReconciliacaoVedacitController {
    private final ReconciliacaoVedacitRepository repository;
    public ReconciliacaoVedacitController(ReconciliacaoVedacitRepository repository) { this.repository=repository; }
    @GetMapping public List<Map<String,Object>> execucoes() { return repository.execucoes(); }
    @GetMapping("/{id}/resumo") public List<Resumo> resumo(@PathVariable long id) { return repository.resumo(id); }
    @GetMapping("/{id}/itens") public List<Map<String,Object>> itens(@PathVariable long id,
            @RequestParam(defaultValue="0") long depois,@RequestParam(defaultValue="100") int tamanho) {
        return repository.itens(id,Math.max(0,depois),tamanho);
    }
}
