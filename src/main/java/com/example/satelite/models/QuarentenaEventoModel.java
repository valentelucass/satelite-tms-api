package com.example.satelite.models;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tb_log_integracao_quarentena_evento", schema = "dbo")
public class QuarentenaEventoModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_integracao_id", nullable = false)
    private Long logIntegracaoId;

    @Column(name = "tipo_evento", length = 30, nullable = false)
    private String tipoEvento;

    @Column(length = 20, nullable = false)
    private String resultado;

    @Column(length = 20, nullable = false)
    private String etapa;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String mensagem;

    @Column(name = "data_evento", nullable = false)
    private LocalDateTime dataEvento;
}
