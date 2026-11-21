package com.example.satelite.dto.auditoria;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PendenciasEtapasDTO(List<Item> itens, long totalElementos, int pagina, int tamanho) {
    public record Item(long id, String sistemaDestino, String etapa, String documento, String acao,
            String motivo, LocalDateTime pendenteDesde, LocalDateTime ultimaAvaliacao, LocalDateTime proximaAvaliacao) {}
}
