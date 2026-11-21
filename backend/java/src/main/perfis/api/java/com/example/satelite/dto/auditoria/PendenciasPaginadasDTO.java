package com.example.satelite.dto.auditoria;

import java.util.List;

public record PendenciasPaginadasDTO(
    List<PendenciaDTO> itens,
    PaginacaoDTO paginacao) {
}
