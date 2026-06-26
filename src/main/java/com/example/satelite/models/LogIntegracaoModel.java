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
@Table(name = "tb_log_integracao", schema = "dbo")
public class LogIntegracaoModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "occurrence_id")
    private Long occurrenceId;
    @Column(name = "chave_nfe", length = 44)
    private String chaveNfe;
    @Column(name = "freight_id")
    private Long freightId;
    @Column(name = "cursor_next_id")
    private Long cursorNextId;
    @Column(length = 50, nullable = false)
    private String status;
    @Column(name = "sistema_destino", length = 20)
    private String sistemaDestino;
    @Column(name = "request_payload", columnDefinition = "NVARCHAR(MAX)")
    private String requestPayload;
    @Column(name = "response_payload", columnDefinition = "NVARCHAR(MAX)")
    private String responsePayload;
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String erro;
    @Column(name = "status_dados", length = 50)
    private String statusDados;
    @Column(name = "status_canhoto", length = 50)
    private String statusCanhoto;
    @Column(name = "mensagem_erro_dados", columnDefinition = "NVARCHAR(MAX)")
    private String mensagemErroDados;
    @Column(name = "mensagem_erro_canhoto", columnDefinition = "NVARCHAR(MAX)")
    private String mensagemErroCanhoto;
    @Column(name = "canhoto_referencia", length = 2048)
    private String canhotoReferencia;
    @Column(name = "canhoto_mime_type", length = 100)
    private String canhotoMimeType;
    @Column(name = "canhoto_origem", length = 30)
    private String canhotoOrigem;
    @Column(name = "data_processamento_dados")
    private LocalDateTime dataProcessamentoDados;
    @Column(name = "data_processamento_canhoto")
    private LocalDateTime dataProcessamentoCanhoto;
    @Column(name = "tentativas_dados")
    private Integer tentativasDados;
    @Column(name = "tentativas_canhoto")
    private Integer tentativasCanhoto;
    @Column(name = "data_processamento", nullable = false)
    private LocalDateTime dataProcessamento;


}
