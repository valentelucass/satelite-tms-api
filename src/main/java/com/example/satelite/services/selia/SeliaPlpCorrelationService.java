package com.example.satelite.services.selia;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;

@Service
public class SeliaPlpCorrelationService {

    static final String DESTINO_PLP_MAPA = "SELIA_PLP_MAP";
    static final String STATUS_ACEITO_PLP = "ACEITO_PLP";

    private final LogIntegracaoRepository logIntegracaoRepository;

    public SeliaPlpCorrelationService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    public List<IdentificacaoEntrega> buscarPorChaveNfe(String chaveNfe) {
        if (chaveNfe == null || chaveNfe.isBlank()) {
            return List.of();
        }

        List<LogIntegracaoModel> correlacoes = logIntegracaoRepository
                .findBySistemaDestinoAndChaveNfeAndStatusOrderByDataProcessamentoDescIdDesc(
                        DESTINO_PLP_MAPA,
                        chaveNfe.trim(),
                        STATUS_ACEITO_PLP
                );
        if (correlacoes.isEmpty()) {
            return List.of();
        }

        Long listaMaisRecente = correlacoes.get(0).getIntelipostPreShipmentList();
        LinkedHashSet<IdentificacaoEntrega> identificacoes = new LinkedHashSet<>();
        for (LogIntegracaoModel correlacao : correlacoes) {
            if (!Objects.equals(listaMaisRecente, correlacao.getIntelipostPreShipmentList())) {
                break;
            }
            if (textoPreenchido(correlacao.getOrderNumber()) && textoPreenchido(correlacao.getVolumeNumber())) {
                identificacoes.add(new IdentificacaoEntrega(
                        correlacao.getOrderNumber().trim(),
                        correlacao.getVolumeNumber().trim()
                ));
            }
        }
        return List.copyOf(identificacoes);
    }

    private boolean textoPreenchido(String valor) {
        return valor != null && !valor.isBlank();
    }

    public record IdentificacaoEntrega(String orderNumber, String volumeNumber) {
    }
}
