package com.example.satelite.controllers;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;

/** Somente consulta; não inicia conferências nem libera reenvios. Rota Vedacit legada preservada. */
@RestController
@RequestMapping("/api/auditoria/reconciliacoes")
public class ReconciliacaoClientesController {
    private final ReconciliacaoLocalRepository local;
    private final ReconciliacaoVedacitRepository vedacit;
    public ReconciliacaoClientesController(ReconciliacaoLocalRepository local,ReconciliacaoVedacitRepository vedacit) {
        this.local=local; this.vedacit=vedacit;
    }
    @GetMapping("/{cliente}") public Object execucoes(@PathVariable String cliente) {
        String c=validar(cliente);
        return "VEDACIT".equals(c) ? vedacit.execucoes() : local.execucoes(c);
    }
    @GetMapping("/{cliente}/{id}/resumo") public Object resumo(@PathVariable String cliente,@PathVariable long id) {
        String c=validar(cliente);
        return "VEDACIT".equals(c) ? vedacit.resumo(id) : local.resumo(c,id);
    }
    private static String validar(String cliente) {
        String c=cliente.toUpperCase(Locale.ROOT);
        if(!Set.of("VEDACIT","PPG","SELIA","SUPPORTE").contains(c))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cliente de reconciliação desconhecido");
        return c;
    }
}
