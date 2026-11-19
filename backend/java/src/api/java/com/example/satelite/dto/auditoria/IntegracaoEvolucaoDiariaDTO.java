package com.example.satelite.dto.auditoria;

import java.time.LocalDate;

public record IntegracaoEvolucaoDiariaDTO(
        LocalDate data,
        Integer total,
        Integer sucessos,
        Integer erros
) {
}
