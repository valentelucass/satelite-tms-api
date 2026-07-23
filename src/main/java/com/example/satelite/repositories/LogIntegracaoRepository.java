package com.example.satelite.repositories;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.satelite.models.LogIntegracaoModel;

public interface LogIntegracaoRepository extends JpaRepository<LogIntegracaoModel, Long> {

    Optional<LogIntegracaoModel> findTopBySistemaDestinoAndOccurrenceIdOrderByDataProcessamentoDescIdDesc(
            String sistemaDestino,
            Long occurrenceId
    );

    Optional<LogIntegracaoModel> findTopBySistemaDestinoAndIntelipostPreShipmentListOrderByDataProcessamentoAscIdAsc(
            String sistemaDestino,
            Long intelipostPreShipmentList
    );

    List<LogIntegracaoModel> findBySistemaDestinoAndChaveNfeAndStatusOrderByDataProcessamentoDescIdDesc(
            String sistemaDestino,
            String chaveNfe,
            String status
    );

    List<LogIntegracaoModel> findBySistemaDestinoAndStatusCanhotoOrderByDataProcessamentoAscIdAsc(
            String sistemaDestino,
            String statusCanhoto
    );

    @Query("""
            SELECT l
            FROM LogIntegracaoModel l
            WHERE l.status = 'ERRO_DESTINO'
              AND l.statusDados = 'SUCESSO'
              AND l.statusCanhoto = 'ERRO_DESTINO'
              AND l.tentativasCanhoto < 3
            ORDER BY l.dataProcessamento ASC, l.id ASC
            """)
    List<LogIntegracaoModel> findErrosParciaisCanhotoPendentesRetry();

    @Query("""
            SELECT l
            FROM LogIntegracaoModel l
            WHERE l.sistemaDestino = :destino
              AND l.status = 'ERRO_DESTINO'
              AND (l.tentativasDados >= 3 OR l.tentativasCanhoto >= 3)
            ORDER BY l.dataProcessamento ASC, l.id ASC
            """)
    List<LogIntegracaoModel> findQuarentenaByDestino(@Param("destino") String destino);

    @Query("""
            SELECT l
            FROM LogIntegracaoModel l
            WHERE l.status = 'ERRO_DESTINO'
              AND (l.tentativasDados >= 3 OR l.tentativasCanhoto >= 3)
              AND l.dataProcessamento >= :inicioCiclo
            ORDER BY l.dataProcessamento ASC, l.id ASC
            """)
    List<LogIntegracaoModel> findErrosManuaisDesde(@Param("inicioCiclo") LocalDateTime inicioCiclo);

    @Query(
            value = """
                    SELECT l
                    FROM LogIntegracaoModel l
                    WHERE l.status = 'ERRO_DESTINO'
                      AND (l.tentativasDados >= 3 OR l.tentativasCanhoto >= 3)
                    ORDER BY l.dataProcessamento DESC, l.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(l)
                    FROM LogIntegracaoModel l
                    WHERE l.status = 'ERRO_DESTINO'
                      AND (l.tentativasDados >= 3 OR l.tentativasCanhoto >= 3)
                    """
    )
    Page<LogIntegracaoModel> findErrosManuais(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogIntegracaoModel l
            SET l.status = 'PENDENTE',
                l.statusDados = 'PENDENTE',
                l.statusCanhoto = 'PENDENTE',
                l.tentativasDados = 0,
                l.tentativasCanhoto = 0,
                l.erro = null,
                l.mensagemErroDados = null,
                l.mensagemErroCanhoto = null
            WHERE l.sistemaDestino = :destino
              AND l.status = 'ERRO_DESTINO'
              AND (l.tentativasDados >= 3 OR l.tentativasCanhoto >= 3)
            """)
    int resetarQuarentenaByDestino(@Param("destino") String destino);

    @Query(value = """
            SELECT
                d.sistema_destino AS sistemaDestino,
                COUNT(l.id) AS totalRegistros,
                CAST(
                    CASE
                        WHEN COUNT(l.id) = 0 THEN 0.00
                        ELSE ROUND(
                            100.0 * SUM(CASE
                                WHEN COALESCE(UPPER(NULLIF(TRIM(l.status_dados), '')), UPPER(NULLIF(TRIM(l.status), '')))
                                     IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                                THEN 1.0 ELSE 0.0 END) / COUNT(l.id),
                            2
                        )
                    END AS DECIMAL(5, 2)
                ) AS percentualXmlSucesso,
                CAST(
                    CASE
                        WHEN COUNT(l.id) = 0 THEN 0.00
                        ELSE ROUND(
                            100.0 * SUM(CASE
                                WHEN COALESCE(UPPER(NULLIF(TRIM(l.status_canhoto), '')), UPPER(NULLIF(TRIM(l.status), '')))
                                     IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                                THEN 1.0 ELSE 0.0 END) / COUNT(l.id),
                            2
                        )
                    END AS DECIMAL(5, 2)
                ) AS percentualCanhotoSucesso
            FROM (VALUES ('VEDACIT'), ('PPG')) AS d(sistema_destino)
            LEFT JOIN dbo.tb_log_integracao l
                ON l.sistema_destino = d.sistema_destino
               AND l.data_processamento >= :dataInicial
               AND l.data_processamento < :dataFinalLimit
            GROUP BY d.sistema_destino
            ORDER BY d.sistema_destino
            """, nativeQuery = true)
    List<MetricaIntegracaoClienteProjection> buscarMetricasIntegracoesClientes(
            @Param("dataInicial") LocalDateTime dataInicial,
            @Param("dataFinalLimit") LocalDateTime dataFinalLimit
    );

    @Query(value = """
            SELECT
                CAST(l.data_processamento AS DATE) AS data,
                COUNT(l.id) AS total,
                SUM(CASE
                    WHEN (
                        COALESCE(UPPER(NULLIF(TRIM(l.status), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                        OR COALESCE(UPPER(NULLIF(TRIM(l.status_dados), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                        OR COALESCE(UPPER(NULLIF(TRIM(l.status_canhoto), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                    )
                    THEN 1 ELSE 0 END) AS sucessos,
                SUM(CASE
                    WHEN (
                        COALESCE(UPPER(NULLIF(TRIM(l.status), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                        OR COALESCE(UPPER(NULLIF(TRIM(l.status_dados), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                        OR COALESCE(UPPER(NULLIF(TRIM(l.status_canhoto), '')), '') IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                    )
                    THEN 0 ELSE 1 END) AS erros
            FROM dbo.tb_log_integracao l
            WHERE l.sistema_destino IN ('VEDACIT', 'PPG')
              AND l.data_processamento >= :dataInicial
              AND l.data_processamento < :dataFinalLimit
            GROUP BY CAST(l.data_processamento AS DATE)
            ORDER BY data ASC
            """, nativeQuery = true)
    List<IntegracaoEvolucaoDiariaProjection> buscarEvolucaoDiariaIntegracoes(
            @Param("dataInicial") LocalDateTime dataInicial,
            @Param("dataFinalLimit") LocalDateTime dataFinalLimit
    );

    @Query(
            value = """
                    SELECT
                        l.id AS id,
                        l.sistema_destino AS sistemaDestino,
                        l.occurrence_id AS occurrenceId,
                        l.freight_id AS freightId,
                        l.chave_nfe AS chaveNfe,
                        TRY_CAST(SUBSTRING(l.chave_nfe, 26, 9) AS BIGINT) AS numero_nf,
                        SUBSTRING(l.chave_nfe, 23, 3) AS serie_nf,
                        COALESCE(NULLIF(TRIM(l.status_dados), ''), NULLIF(TRIM(l.status), '')) AS statusDados,
                        CASE
                            WHEN l.status = 'ERRO_DESTINO'
                             AND l.status_dados = 'SUCESSO'
                             AND l.status_canhoto = 'ERRO_DESTINO'
                             AND l.tentativas_canhoto < 3
                            THEN 'Erro Parcial - Aguarda Retry'
                            ELSE COALESCE(NULLIF(TRIM(l.status_canhoto), ''), NULLIF(TRIM(l.status), ''))
                        END AS statusCanhoto,
                        l.mensagem_erro_dados AS mensagemErroDados,
                        l.mensagem_erro_canhoto AS mensagemErroCanhoto,
                        l.canhoto_referencia AS canhotoReferencia,
                        l.canhoto_mime_type AS canhotoMimeType,
                        l.data_processamento AS dataProcessamento,
                        l.data_processamento_dados AS dataProcessamentoDados,
                        l.data_processamento_canhoto AS dataProcessamentoCanhoto,
                        CAST(CASE
                            WHEN l.canhoto_referencia IS NOT NULL THEN 1 ELSE 0
                        END AS BIT) AS possuiImagemPayload
                    FROM dbo.tb_log_integracao l
                    WHERE l.sistema_destino IN ('VEDACIT', 'PPG')
                      AND (
                          l.status_canhoto = 'PENDENTE_FOTO'
                          OR l.status_dados = 'ERRO_DESTINO'
                          OR (
                              l.status = 'ERRO_DESTINO'
                              AND l.status_dados = 'SUCESSO'
                              AND l.status_canhoto = 'ERRO_DESTINO'
                              AND l.tentativas_canhoto < 3
                          )
                          OR (l.status_canhoto IS NULL AND l.status = 'PENDENTE_FOTO')
                          OR (l.status_dados IS NULL AND l.status = 'ERRO_DESTINO')
                      )
                    ORDER BY l.data_processamento DESC, l.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(1)
                    FROM dbo.tb_log_integracao l
                    WHERE l.sistema_destino IN ('VEDACIT', 'PPG')
                      AND (
                          l.status_canhoto = 'PENDENTE_FOTO'
                          OR l.status_dados = 'ERRO_DESTINO'
                          OR (
                              l.status = 'ERRO_DESTINO'
                              AND l.status_dados = 'SUCESSO'
                              AND l.status_canhoto = 'ERRO_DESTINO'
                              AND l.tentativas_canhoto < 3
                          )
                          OR (l.status_canhoto IS NULL AND l.status = 'PENDENTE_FOTO')
                          OR (l.status_dados IS NULL AND l.status = 'ERRO_DESTINO')
                      )
                    """,
            nativeQuery = true
    )
    Page<PendenciaIntegracaoClienteProjection> buscarPendenciasIntegracoesClientes(Pageable pageable);

    interface MetricaIntegracaoClienteProjection {
        String getSistemaDestino();

        Long getTotalRegistros();

        BigDecimal getPercentualXmlSucesso();

        BigDecimal getPercentualCanhotoSucesso();
    }

    interface IntegracaoEvolucaoDiariaProjection {
        LocalDate getData();

        Integer getTotal();

        Integer getSucessos();

        Integer getErros();
    }

    interface PendenciaIntegracaoClienteProjection {
        Long getId();

        String getSistemaDestino();

        Long getOccurrenceId();

        Long getFreightId();

        String getChaveNfe();

        Long getNumeroNf();

        String getSerieNf();

        String getStatusDados();

        String getStatusCanhoto();

        String getMensagemErroDados();

        String getMensagemErroCanhoto();

        String getCanhotoReferencia();

        String getCanhotoMimeType();

        LocalDateTime getDataProcessamento();

        LocalDateTime getDataProcessamentoDados();

        LocalDateTime getDataProcessamentoCanhoto();

        Boolean getPossuiImagemPayload();
    }
}
