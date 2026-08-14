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
@Table(name = "tb_esl_request_telemetria", schema = "dbo")
public class EslRequestTelemetryModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_evento", nullable = false)
    private LocalDateTime dataEvento;

    @Column(nullable = false, length = 30)
    private String origem;

    @Column(nullable = false, length = 30)
    private String destino;

    @Column(nullable = false, length = 50)
    private String rota;

    @Column(nullable = false, length = 80)
    private String template;

    @Column(name = "status_http")
    private Integer statusHttp;

    @Column(nullable = false)
    private int tentativa;

    @Column(nullable = false)
    private boolean retry;

    @Column(nullable = false)
    private boolean fallback;

    @Column(name = "cache_status", nullable = false, length = 20)
    private String cacheStatus;

    @Column(name = "duracao_ms", nullable = false)
    private long duracaoMs;
}
