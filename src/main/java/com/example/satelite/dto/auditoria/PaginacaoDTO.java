package com.example.satelite.dto.auditoria;

public record PaginacaoDTO(
        int pagina,
        int tamanho,
        long totalElementos,
        int totalPaginas,
        boolean primeiraPagina,
        boolean ultimaPagina) {
}
