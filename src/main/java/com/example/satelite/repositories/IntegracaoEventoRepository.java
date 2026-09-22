package com.example.satelite.repositories;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Eventos técnicos imutáveis. Repetir a persistência do mesmo UUID não duplica o evento. */
@Repository
public class IntegracaoEventoRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public IntegracaoEventoRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Evento(UUID id, UUID ciclo, Long logId, String destino, String etapa, String documento,
            String tipo, String acao, String motivo, LocalDateTime ocorridoEm, LocalDateTime proximaAvaliacao) {}

    @Transactional
    public void registrar(Evento e) {
        jdbc.update("""
            INSERT INTO dbo.tb_integracao_etapa_evento
                (evento_id, execucao_id, log_id, sistema_destino, etapa, documento, tipo, acao, motivo,
                 ocorrido_em, proxima_avaliacao_em)
            SELECT :id, :ciclo, :log, :destino, :etapa, :documento, :tipo, :acao, :motivo, :quando, :proxima
            WHERE NOT EXISTS (SELECT 1 FROM dbo.tb_integracao_etapa_evento WITH (UPDLOCK, HOLDLOCK) WHERE evento_id = :id)
            """, new MapSqlParameterSource("id", e.id().toString())
                .addValue("ciclo", e.ciclo() == null ? null : e.ciclo().toString()).addValue("log", e.logId())
                .addValue("destino", e.destino()).addValue("etapa", e.etapa()).addValue("documento", e.documento())
                .addValue("tipo", e.tipo()).addValue("acao", e.acao()).addValue("motivo", e.motivo())
                .addValue("quando", e.ocorridoEm()).addValue("proxima", e.proximaAvaliacao()));
    }

    public static String documento(String destino, String etapa, String nfe, String cte, Long ocorrencia, Long log) {
        if ("VEDACIT".equals(destino) && "DADOS".equals(etapa) && preenchido(cte)) return "CTE:" + cte;
        if ("VEDACIT".equals(destino) && "COMPROVANTE".equals(etapa) && preenchido(nfe) && preenchido(cte))
            return "POD:" + nfe + ":" + cte;
        if (!"VEDACIT".equals(destino) && ocorrencia != null) return "OCORRENCIA:" + ocorrencia;
        return "LOG:" + log;
    }
    @Transactional(readOnly=true)
    public boolean acessoXmlEmEspera(LocalDateTime agora) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT CASE WHEN EXISTS (SELECT 1 FROM dbo.tb_integracao_etapa_evento
              WHERE sistema_destino='VEDACIT' AND etapa='DADOS' AND tipo='AVALIACAO'
                AND motivo='ACESSO_ORIGEM_RECUSADO' AND proxima_avaliacao_em>:agora) THEN 1 ELSE 0 END
            """, new MapSqlParameterSource("agora",agora), Boolean.class));
    }
    private static boolean preenchido(String valor) { return valor != null && !valor.isBlank(); }
}
