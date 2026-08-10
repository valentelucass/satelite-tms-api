package com.example.satelite.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.example.satelite.models.QuarentenaEventoModel;

public interface QuarentenaEventoRepository extends JpaRepository<QuarentenaEventoModel, Long> {
    boolean existsByLogIntegracaoIdAndTipoEvento(Long logIntegracaoId, String tipoEvento);

    @Query(value = """
            SELECT e.id AS id,
                   l.sistema_destino AS destino,
                   l.chave_nfe AS chaveNfe,
                   e.etapa AS etapa,
                   (
                       SELECT MIN(entrada.data_evento)
                       FROM dbo.tb_log_integracao_quarentena_evento entrada
                       WHERE entrada.log_integracao_id = e.log_integracao_id
                         AND entrada.tipo_evento = 'ENTRADA_QUARENTENA'
                   ) AS entradaQuarentenaEm,
                   e.data_evento AS reprocessadoEm,
                   e.resultado AS resultado,
                   e.mensagem AS mensagem
            FROM dbo.tb_log_integracao_quarentena_evento e
            INNER JOIN dbo.tb_log_integracao l ON l.id = e.log_integracao_id
            WHERE e.tipo_evento = 'REPESCAGEM'
              AND e.data_evento >= :inicio
              AND e.data_evento < :fimExclusivo
              AND l.sistema_destino IN (:destinos)
            ORDER BY e.data_evento DESC, e.id DESC
            """, countQuery = """
            SELECT COUNT_BIG(1)
            FROM dbo.tb_log_integracao_quarentena_evento e
            INNER JOIN dbo.tb_log_integracao l ON l.id = e.log_integracao_id
            WHERE e.tipo_evento = 'REPESCAGEM'
              AND e.data_evento >= :inicio
              AND e.data_evento < :fimExclusivo
              AND l.sistema_destino IN (:destinos)
            """, nativeQuery = true)
    Page<HistoricoProjection> buscarHistoricoRepescagens(
            @Param("inicio") LocalDateTime inicio,
            @Param("fimExclusivo") LocalDateTime fimExclusivo,
            @Param("destinos") List<String> destinos,
            Pageable pageable
    );

    @Query(value = """
            SELECT e.id AS id,
                   l.sistema_destino AS destino,
                   l.chave_nfe AS chaveNfe,
                   e.etapa AS etapa,
                   (SELECT MIN(entrada.data_evento)
                      FROM dbo.tb_log_integracao_quarentena_evento entrada
                     WHERE entrada.log_integracao_id = e.log_integracao_id
                       AND entrada.tipo_evento = 'ENTRADA_QUARENTENA') AS entradaQuarentenaEm,
                   e.data_evento AS reprocessadoEm,
                   e.resultado AS resultado,
                   e.mensagem AS mensagem
            FROM dbo.tb_log_integracao_quarentena_evento e
            INNER JOIN dbo.tb_log_integracao l ON l.id = e.log_integracao_id
            WHERE e.tipo_evento = 'REPESCAGEM'
              AND e.data_evento >= :inicio
              AND e.data_evento < :fimExclusivo
              AND l.sistema_destino IN (:destinos)
            ORDER BY e.data_evento DESC, e.id DESC
            """, nativeQuery = true)
    Stream<HistoricoProjection> exportarHistoricoRepescagens(
            @Param("inicio") LocalDateTime inicio,
            @Param("fimExclusivo") LocalDateTime fimExclusivo,
            @Param("destinos") List<String> destinos
    );

    interface HistoricoProjection {
        Long getId();
        String getDestino();
        String getChaveNfe();
        String getEtapa();
        LocalDateTime getEntradaQuarentenaEm();
        LocalDateTime getReprocessadoEm();
        String getResultado();
        String getMensagem();
    }
}
