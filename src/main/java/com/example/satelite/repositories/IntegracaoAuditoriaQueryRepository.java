package com.example.satelite.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.satelite.dto.auditoria.PendenciaDTO;

@Repository
public class IntegracaoAuditoriaQueryRepository {

    private static final String NUMERO_NF_EXPR = "TRY_CAST(SUBSTRING(l.chave_nfe, 26, 9) AS BIGINT)";
    private static final String SERIE_NF_EXPR = "SUBSTRING(l.chave_nfe, 23, 3)";
    private static final String STATUS_DADOS_EXPR =
            "COALESCE(NULLIF(TRIM(l.status_dados), ''), NULLIF(TRIM(l.status), ''))";
    private static final String STATUS_CANHOTO_EXPR =
            "COALESCE(NULLIF(TRIM(l.status_canhoto), ''), NULLIF(TRIM(l.status), ''))";
    private static final String NUMERO_NF_TEXTO_EXPR = "CAST(" + NUMERO_NF_EXPR + " AS VARCHAR(32))";
    private static final String ESCOPO_SUCESSO = "SUCESSO";
    private static final String ESCOPO_TODOS = "TODOS";
    private static final String FILTRO_PENDENCIAS = """
            (
                l.status_canhoto = 'PENDENTE_FOTO'
                OR l.status_dados = 'ERRO_DESTINO'
                OR (l.status_canhoto IS NULL AND l.status = 'PENDENTE_FOTO')
                OR (l.status_dados IS NULL AND l.status = 'ERRO_DESTINO')
            )
            """;
    private static final String FILTRO_SUCESSO = """
            (
                l.status IN ('ENVIADO', 'PROCESSADO')
                OR l.status_dados IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
                OR l.status_canhoto IN ('SUCESSO', 'ENVIADO', 'PROCESSADO')
            )
            """;
    private static final Pattern DIGITOS = Pattern.compile("\\d+");
    private static final Map<String, ColunaFiltro> COLUNAS_FILTRO = criarColunasFiltro();
    private static final Map<String, String> COLUNAS_ORDENACAO = criarColunasOrdenacao();

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IntegracaoAuditoriaQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PendenciasResultado buscarPendencias(Filtros filtros, int pagina, int tamanho) {
        QueryParts queryParts = montarFiltros(filtros);
        String fromWhere = """
                FROM dbo.tb_log_integracao l
                WHERE %s
                """.formatted(String.join("\n  AND ", queryParts.where()));

        String countSql = "SELECT COUNT(1) " + fromWhere;
        long total = jdbcTemplate.queryForObject(countSql, queryParts.params(), Long.class);

        MapSqlParameterSource params = queryParts.params()
                .addValue("offsetTabela", Math.max(0, pagina) * tamanho)
                .addValue("tamanhoTabela", tamanho);

        String dataSql = """
                SELECT
                    l.id AS id,
                    l.sistema_destino AS sistemaDestino,
                    l.occurrence_id AS occurrenceId,
                    l.freight_id AS freightId,
                    l.chave_nfe AS chaveNfe,
                    %s AS numeroNf,
                    %s AS serieNf,
                    %s AS statusDados,
                    %s AS statusCanhoto,
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
                %s
                %s
                OFFSET :offsetTabela ROWS FETCH NEXT :tamanhoTabela ROWS ONLY
                """.formatted(
                NUMERO_NF_EXPR,
                SERIE_NF_EXPR,
                STATUS_DADOS_EXPR,
                STATUS_CANHOTO_EXPR,
                fromWhere,
                montarOrdenacao(filtros)
        );

        List<PendenciaDTO> itens = jdbcTemplate.query(dataSql, params, new PendenciaRowMapper());
        return new PendenciasResultado(itens, total);
    }

    private QueryParts montarFiltros(Filtros filtros) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        where.add("l.sistema_destino IN ('VEDACIT', 'PPG')");
        adicionarEscopo(where, filtros.escopo());

        adicionarBusca(where, params, filtros.tabelaBusca());
        adicionarCodigo(where, params, filtros.tabelaCodigo());
        adicionarStatus(where, params, filtros.tabelaStatus());
        adicionarFiltrosColuna(where, params, filtros.filtrosColuna());

        return new QueryParts(where, params);
    }

    private void adicionarEscopo(List<String> where, String escopo) {
        String escopoNormalizado = normalizarTexto(escopo);
        if (ESCOPO_TODOS.equalsIgnoreCase(escopoNormalizado)) {
            return;
        }

        if (ESCOPO_SUCESSO.equalsIgnoreCase(escopoNormalizado)) {
            where.add(FILTRO_SUCESSO);
            return;
        }

        where.add(FILTRO_PENDENCIAS);
    }

    private void adicionarBusca(List<String> where, MapSqlParameterSource params, String valor) {
        String texto = normalizarTexto(valor);
        if (texto == null) {
            return;
        }

        params.addValue("filtroTabelaBusca", like(texto));
        where.add("""
                (
                    l.sistema_destino LIKE :filtroTabelaBusca
                    OR l.chave_nfe LIKE :filtroTabelaBusca
                    OR %s LIKE :filtroTabelaBusca
                    OR %s LIKE :filtroTabelaBusca
                    OR %s LIKE :filtroTabelaBusca
                    OR %s LIKE :filtroTabelaBusca
                )
                """.formatted(NUMERO_NF_TEXTO_EXPR, SERIE_NF_EXPR, STATUS_DADOS_EXPR, STATUS_CANHOTO_EXPR));
    }

    private void adicionarCodigo(List<String> where, MapSqlParameterSource params, String valor) {
        String texto = normalizarTexto(valor);
        if (texto == null) {
            return;
        }

        if (DIGITOS.matcher(texto).matches()) {
            try {
                params.addValue("filtroTabelaCodigoNumero", Long.parseLong(texto));
                where.add(NUMERO_NF_EXPR + " = :filtroTabelaCodigoNumero");
                return;
            } catch (NumberFormatException ignored) {
                // Valores numericos grandes demais caem no filtro textual da chave.
            }
        }

        params.addValue("filtroTabelaCodigoTexto", like(texto));
        where.add("(l.chave_nfe LIKE :filtroTabelaCodigoTexto OR "
                + NUMERO_NF_TEXTO_EXPR
                + " LIKE :filtroTabelaCodigoTexto)");
    }

    private void adicionarStatus(List<String> where, MapSqlParameterSource params, List<String> valores) {
        List<String> status = normalizarValores(valores);
        if (status.isEmpty()) {
            return;
        }

        params.addValue("filtroTabelaStatus", status);
        where.add("("
                + STATUS_DADOS_EXPR
                + " IN (:filtroTabelaStatus) OR "
                + STATUS_CANHOTO_EXPR
                + " IN (:filtroTabelaStatus))");
    }

    private void adicionarFiltrosColuna(
            List<String> where,
            MapSqlParameterSource params,
            Map<String, List<String>> filtrosColuna
    ) {
        for (Map.Entry<String, List<String>> entry : filtrosColuna.entrySet()) {
            ColunaFiltro coluna = COLUNAS_FILTRO.get(entry.getKey());
            List<String> valores = normalizarValores(entry.getValue());
            if (coluna == null || valores.isEmpty()) {
                continue;
            }

            String nomeParam = "filtroTabelaColuna_" + entry.getKey().replaceAll("[^A-Za-z0-9]", "_");
            if (coluna.tipo() == TipoFiltro.STATUS) {
                params.addValue(nomeParam, valores);
                where.add(coluna.expressao() + " IN (:" + nomeParam + ")");
            } else if (coluna.tipo() == TipoFiltro.NUMERO && valores.size() == 1 && DIGITOS.matcher(valores.get(0)).matches()) {
                try {
                    params.addValue(nomeParam, Long.parseLong(valores.get(0)));
                    where.add(coluna.expressao() + " = :" + nomeParam);
                } catch (NumberFormatException ignored) {
                    adicionarFiltroTexto(where, params, coluna.expressaoTexto(), nomeParam, valores);
                }
            } else {
                adicionarFiltroTexto(where, params, coluna.expressaoTexto(), nomeParam, valores);
            }
        }
    }

    private void adicionarFiltroTexto(
            List<String> where,
            MapSqlParameterSource params,
            String expressao,
            String nomeParam,
            List<String> valores
    ) {
        List<String> condicoes = new ArrayList<>();
        for (int i = 0; i < valores.size(); i++) {
            String param = nomeParam + "_" + i;
            params.addValue(param, like(valores.get(i)));
            condicoes.add(expressao + " LIKE :" + param);
        }
        where.add("(" + String.join(" OR ", condicoes) + ")");
    }

    private String montarOrdenacao(Filtros filtros) {
        String sortField = normalizarTexto(filtros.sortField());
        if (sortField == null) {
            return "ORDER BY l.data_processamento DESC, l.id DESC";
        }

        String expressao = COLUNAS_ORDENACAO.get(sortField);
        if (expressao == null) {
            return "ORDER BY l.data_processamento DESC, l.id DESC";
        }

        String direcao = "asc".equalsIgnoreCase(filtros.sortDirection()) ? "ASC" : "DESC";
        return "ORDER BY " + expressao + " " + direcao + ", l.id DESC";
    }

    private static Map<String, ColunaFiltro> criarColunasFiltro() {
        Map<String, ColunaFiltro> colunas = new LinkedHashMap<>();
        colunas.put("id", ColunaFiltro.numero("l.id"));
        colunas.put("sistemaDestino", ColunaFiltro.texto("l.sistema_destino"));
        colunas.put("occurrenceId", ColunaFiltro.numero("l.occurrence_id"));
        colunas.put("freightId", ColunaFiltro.numero("l.freight_id"));
        colunas.put("chaveNfe", ColunaFiltro.texto("l.chave_nfe"));
        colunas.put("numeroNf", ColunaFiltro.numero(NUMERO_NF_EXPR, NUMERO_NF_TEXTO_EXPR));
        colunas.put("serieNf", ColunaFiltro.texto(SERIE_NF_EXPR));
        colunas.put("statusDados", ColunaFiltro.status(STATUS_DADOS_EXPR));
        colunas.put("statusCanhoto", ColunaFiltro.status(STATUS_CANHOTO_EXPR));
        colunas.put("dataProcessamento", ColunaFiltro.texto("CONVERT(VARCHAR(19), l.data_processamento, 120)"));
        colunas.put("dataProcessamentoDados", ColunaFiltro.texto("CONVERT(VARCHAR(19), l.data_processamento_dados, 120)"));
        colunas.put("dataProcessamentoCanhoto", ColunaFiltro.texto("CONVERT(VARCHAR(19), l.data_processamento_canhoto, 120)"));
        return Map.copyOf(colunas);
    }

    private static Map<String, String> criarColunasOrdenacao() {
        Map<String, String> colunas = new LinkedHashMap<>();
        colunas.put("id", "l.id");
        colunas.put("sistemaDestino", "l.sistema_destino");
        colunas.put("occurrenceId", "l.occurrence_id");
        colunas.put("freightId", "l.freight_id");
        colunas.put("chaveNfe", "l.chave_nfe");
        colunas.put("numeroNf", NUMERO_NF_EXPR);
        colunas.put("serieNf", SERIE_NF_EXPR);
        colunas.put("statusDados", STATUS_DADOS_EXPR);
        colunas.put("statusCanhoto", STATUS_CANHOTO_EXPR);
        colunas.put("dataProcessamento", "l.data_processamento");
        colunas.put("dataProcessamentoDados", "l.data_processamento_dados");
        colunas.put("dataProcessamentoCanhoto", "l.data_processamento_canhoto");
        return Map.copyOf(colunas);
    }

    private static List<String> normalizarValores(List<String> valores) {
        if (valores == null || valores.isEmpty()) {
            return List.of();
        }

        return valores.stream()
                .map(IntegracaoAuditoriaQueryRepository::normalizarTexto)
                .filter(valor -> valor != null)
                .distinct()
                .toList();
    }

    private static String normalizarTexto(String valor) {
        if (valor == null) {
            return null;
        }

        String texto = valor.trim();
        return texto.isEmpty() ? null : texto;
    }

    private static String like(String valor) {
        return "%" + valor + "%";
    }

    private static Long getLongOuNull(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static Boolean getBooleanOuNull(ResultSet rs, String coluna) throws SQLException {
        boolean valor = rs.getBoolean(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static LocalDateTime getLocalDateTimeOuNull(ResultSet rs, String coluna) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(coluna);
        return timestamp != null ? timestamp.toLocalDateTime() : null;
    }

    public record Filtros(
            String tabelaBusca,
            String tabelaCodigo,
            List<String> tabelaStatus,
            Map<String, List<String>> filtrosColuna,
            String escopo,
            String sortField,
            String sortDirection
    ) {
    }

    public record PendenciasResultado(List<PendenciaDTO> itens, long totalElementos) {
    }

    private record QueryParts(List<String> where, MapSqlParameterSource params) {
    }

    private enum TipoFiltro {
        TEXTO,
        NUMERO,
        STATUS
    }

    private record ColunaFiltro(String expressao, String expressaoTexto, TipoFiltro tipo) {
        static ColunaFiltro texto(String expressao) {
            return new ColunaFiltro(expressao, expressao, TipoFiltro.TEXTO);
        }

        static ColunaFiltro numero(String expressao) {
            return numero(expressao, "CAST(" + expressao + " AS VARCHAR(64))");
        }

        static ColunaFiltro numero(String expressao, String expressaoTexto) {
            return new ColunaFiltro(expressao, expressaoTexto, TipoFiltro.NUMERO);
        }

        static ColunaFiltro status(String expressao) {
            return new ColunaFiltro(expressao, expressao, TipoFiltro.STATUS);
        }
    }

    private static final class PendenciaRowMapper implements RowMapper<PendenciaDTO> {
        @Override
        public PendenciaDTO mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PendenciaDTO(
                    getLongOuNull(rs, "id"),
                    rs.getString("sistemaDestino"),
                    getLongOuNull(rs, "occurrenceId"),
                    getLongOuNull(rs, "freightId"),
                    rs.getString("chaveNfe"),
                    getLongOuNull(rs, "numeroNf"),
                    rs.getString("serieNf"),
                    rs.getString("statusDados"),
                    rs.getString("statusCanhoto"),
                    rs.getString("mensagemErroDados"),
                    rs.getString("mensagemErroCanhoto"),
                    rs.getString("canhotoReferencia"),
                    rs.getString("canhotoMimeType"),
                    getLocalDateTimeOuNull(rs, "dataProcessamento"),
                    getLocalDateTimeOuNull(rs, "dataProcessamentoDados"),
                    getLocalDateTimeOuNull(rs, "dataProcessamentoCanhoto"),
                    getBooleanOuNull(rs, "possuiImagemPayload")
            );
        }
    }
}
