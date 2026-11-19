package com.example.satelite.dto.auditoria;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class ReconciliacaoLocalDTO {
    private ReconciliacaoLocalDTO() { }
    public record Execucao(long id, String cliente, LocalDate dia, long ultimoId, long limiteId) { }
    public record Candidato(long id, boolean arquivado, String dados, String comprovante,
            LocalDateTime dataDados, LocalDateTime dataComprovante) { }
    public record Revisao(String dados, String comprovante, String acao) { }
}
