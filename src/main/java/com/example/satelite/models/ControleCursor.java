package com.example.satelite.models;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "tb_controle_cursor",
        schema = "dbo",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_tb_controle_cursor_sistema_destino",
                columnNames = "sistema_destino"
        )
)
public class ControleCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "sistema_destino", length = 50, nullable = false)
    private String sistemaDestino;

    @Column(name = "cursor_next_id")
    private Long cursorNextId;

    @Column(name = "data_atualizacao", nullable = false)
    private LocalDateTime dataAtualizacao;

    @PrePersist
    @PreUpdate
    void atualizarDataAtualizacao() {
        dataAtualizacao = LocalDateTime.now();
    }
}
