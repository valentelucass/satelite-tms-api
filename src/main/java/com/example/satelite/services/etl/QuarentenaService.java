package com.example.satelite.services.etl;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;

@Service
public class QuarentenaService {

    private static final Set<String> DESTINOS_VALIDOS = Set.of("PPG", "VEDACIT");

    private final LogIntegracaoRepository logIntegracaoRepository;

    public QuarentenaService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    public List<LogIntegracaoModel> findQuarentenaByDestino(String destino) {
        return logIntegracaoRepository.findQuarentenaByDestino(normalizarDestino(destino));
    }

    @Transactional
    public ResultadoReprocessamento reprocessar(String destino) {
        String destinoNormalizado = normalizarDestino(destino);
        int quantidade = logIntegracaoRepository.resetarQuarentenaByDestino(destinoNormalizado);
        return new ResultadoReprocessamento(destinoNormalizado, quantidade);
    }

    private String normalizarDestino(String destino) {
        if (destino == null || destino.isBlank()) {
            throw new IllegalArgumentException("Destino invalido. Use PPG ou VEDACIT.");
        }

        String destinoNormalizado = destino.trim().toUpperCase(Locale.ROOT);
        if (!DESTINOS_VALIDOS.contains(destinoNormalizado)) {
            throw new IllegalArgumentException("Destino invalido. Use PPG ou VEDACIT.");
        }

        return destinoNormalizado;
    }

    public record ResultadoReprocessamento(String destino, int quantidadeNotas) {
    }
}
