use super::{coluna, Condicoes, Filtros, Metrica, Pendencia, Value};
use crate::repositories::SqlServer;
use crate::services::painel::limite_metricas_utc;
use serde::Deserialize;

#[cfg(test)]
mod tests {
    use super::limite_metricas_utc;

    #[test]
    fn metricas_respeitam_fuso_e_horario_de_verao_historico_da_fonte() {
        assert_eq!(
            limite_metricas_utc("2026-11-10".parse().unwrap()).unwrap(),
            "2026-11-10T03:00:00"
        );
        assert_eq!(
            limite_metricas_utc("2018-12-10".parse().unwrap()).unwrap(),
            "2018-12-10T02:00:00"
        );
    }
}

const PAINEL: &str = r#";WITH painel AS (
    SELECT id, sistema_destino AS destino, occurrence_id, freight_id, chave_nfe,
           TRY_CAST(SUBSTRING(chave_nfe,26,9) AS bigint) AS numero_nf, SUBSTRING(chave_nfe,23,3) AS serie,
           status AS estado, status_dados AS dados, status_canhoto AS canhoto, mensagem_erro_dados, mensagem_erro_canhoto,
           canhoto_referencia, canhoto_mime_type, sftp_cliente, canhoto_classificacao_operacional,
           data_processamento AS data_filtro, data_processamento AS data, data_processamento_dados AS data_dados,
           data_processamento_canhoto AS data_canhoto,
           CASE WHEN status='ERRO_DESTINO' AND status_dados='SUCESSO' AND status_canhoto='ERRO_DESTINO'
             AND tentativas_canhoto<3 THEN 1 ELSE 0 END AS parcial,
           COALESCE(NULLIF(TRIM(status_dados),''),NULLIF(TRIM(status),'')) AS situacao_dados,
           CASE WHEN status='ERRO_DESTINO' AND status_dados='SUCESSO' AND status_canhoto='ERRO_DESTINO'
             AND tentativas_canhoto<3 THEN 'Erro Parcial - Aguarda Retry'
             ELSE COALESCE(NULLIF(TRIM(status_canhoto),''),NULLIF(TRIM(status),'')) END AS situacao_canhoto
    FROM dbo.tb_log_integracao WHERE COALESCE(arquivado,0)=0
)"#;

pub(super) const PENDENTES: &str = "(canhoto='PENDENTE_FOTO' OR dados='ERRO_DESTINO' OR parcial=1
    OR (canhoto IS NULL AND estado='PENDENTE_FOTO') OR (dados IS NULL AND estado='ERRO_DESTINO'))
    AND NOT (destino='VEDACIT' AND sftp_cliente IS NOT NULL
        AND canhoto_classificacao_operacional IN ('BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO'))";

const CAMPOS: &str = "p.id,p.destino AS sistemaDestino,p.occurrence_id AS occurrenceId,p.freight_id AS freightId,
    p.chave_nfe AS chaveNfe,p.numero_nf AS numeroNf,p.serie AS serieNf,p.situacao_dados AS statusDados,
    p.situacao_canhoto AS statusCanhoto,p.mensagem_erro_dados AS mensagemErroDados,
    p.mensagem_erro_canhoto AS mensagemErroCanhoto,p.canhoto_referencia AS canhotoReferencia,
    p.canhoto_mime_type AS canhotoMimeType,p.data AS dataProcessamento,p.data_dados AS dataProcessamentoDados,
    p.data_canhoto AS dataProcessamentoCanhoto,CAST(CASE WHEN p.canhoto_referencia IS NOT NULL THEN 1 ELSE 0 END AS bit) AS possuiImagemPayload";

// Um valor pode ocorrer mais de uma vez na condição. DECLARE o vincula uma única vez por ODBC.
fn parametros(cond: &Condicoes) -> (String, Vec<String>) {
    let mut sql = String::new();
    let mut args = Vec::new();
    for (i, value) in cond.valores.iter().enumerate() {
        let (tipo, texto) = match value {
            Value::Integer(v) => ("bigint", v.to_string()),
            Value::Text(s) => (
                if s.len() > 4000 {
                    "nvarchar(max)"
                } else {
                    "nvarchar(4000)"
                },
                s.clone(),
            ),
            _ => unreachable!("O painel vincula apenas texto e inteiros"),
        };
        sql.push_str(&format!("DECLARE @filtro{} {tipo} = ?; ", i + 1));
        args.push(texto);
    }
    (sql, args)
}

fn metricas(db: &SqlServer, filtros: &Filtros) -> Result<Vec<Metrica>, String> {
    let mut cond = Condicoes {
        sql_server: true,
        ..Default::default()
    };
    let destinos = cond.lista(&filtros.destinos);
    let (inicio, fim) = if let Some((a, b)) = filtros.periodo {
        let fim = match b.tomorrow() {
            Ok(d) => limite_metricas_utc(d)?,
            Err(_) => "9999-12-31T23:59:59.9999999".into(),
        };
        (limite_metricas_utc(a)?, fim)
    } else {
        ("1900-01-01".into(), "9999-12-31T23:59:59.999".into())
    };
    let a = cond.parametro(inicio);
    let b = cond.parametro(fim);
    let (declare, args) = parametros(&cond);
    let p = |col: &str| {
        format!("CAST(COALESCE(ROUND(100.0 * SUM(CASE WHEN
        COALESCE(UPPER(NULLIF(TRIM(l.{col}),'')),UPPER(NULLIF(TRIM(l.status),'')))
        IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 1.0 ELSE 0.0 END)/NULLIF(COUNT(l.id),0),2),0) AS decimal(5,2))")
    };
    let sql = format!("{declare} SELECT d.nome, COUNT(l.id) AS total, {} AS dados, {} AS canhoto
        FROM (VALUES ('VEDACIT'),('PPG'),('SELIA'),('SUPPORTE')) AS d(nome)
        LEFT JOIN dbo.tb_log_integracao l ON l.sistema_destino=d.nome AND COALESCE(l.arquivado,0)=0
            AND NOT (l.sistema_destino='VEDACIT' AND l.sftp_cliente IS NOT NULL
                AND l.canhoto_classificacao_operacional IN ('BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO'))
            AND l.data_processamento>=CONVERT(datetime2,{a},126) AND l.data_processamento<CONVERT(datetime2,{b},126)
        WHERE d.nome IN ({destinos}) GROUP BY d.nome ORDER BY d.nome FOR JSON PATH",
        p("status_dados"),p("status_canhoto"));
    #[derive(Deserialize)]
    struct Metric {
        nome: String,
        total: i64,
        dados: f64,
        canhoto: f64,
    }
    let rows: Vec<Metric> = serde_json::from_value(db.json(&sql, &args)?)
        .map_err(|_| "Resposta de métricas inválida")?;
    Ok(rows
        .into_iter()
        .map(|r| {
            let (rotulo_dados, rotulo_comprovante) = match r.nome.as_str() {
                "SELIA" => ("AddEvents", "POD/Comprovante"),
                "SUPPORTE" => ("Ocorrência", "Comprovante"),
                _ => ("XML/Dados", "Canhoto"),
            };
            Metrica {
                sistema_destino: r.nome,
                total_registros: r.total,
                percentual_xml_sucesso: r.dados,
                percentual_canhoto_sucesso: r.canhoto,
                rotulo_dados,
                rotulo_comprovante,
            }
        })
        .collect())
}

fn where_order(f: &Filtros) -> (Condicoes, String, String) {
    let cond = Condicoes::pendencias(f, true);
    let filtro = cond.clausulas.join(" AND ");
    let ordem = match f.ordenar.as_deref().and_then(coluna) {
        Some((col, _)) => format!(
            "{} {}{}",
            col,
            if f.crescente { "ASC" } else { "DESC" },
            if col == "id" { "" } else { ", id DESC" }
        ),
        None => "data DESC, id DESC".into(),
    };
    (cond, filtro, ordem)
}

fn ler_pendencia(valor: serde_json::Value) -> Result<Pendencia, String> {
    let mut row: Pendencia =
        serde_json::from_value(valor).map_err(|_| "Resposta de pendência inválida")?;
    for date in [
        &mut row.data_processamento,
        &mut row.data_processamento_dados,
        &mut row.data_processamento_canhoto,
    ]
    .into_iter()
    .flatten()
    {
        *date = date
            .parse::<jiff::civil::DateTime>()
            .map_err(|_| "Data de processamento inválida")?
            .to_string();
    }
    Ok(row)
}

pub(super) fn consultar(
    db: &SqlServer,
    f: &Filtros,
) -> Result<(Vec<Metrica>, Vec<Pendencia>, i64), String> {
    let metricas = metricas(db, f)?;
    let (mut cond, filtro, ordem) = where_order(f);
    let (declare, args) = parametros(&cond);
    let count = db.json(
        &format!(
            "{declare} {PAINEL} SELECT COUNT_BIG(*) AS total FROM painel WHERE {filtro}
        FOR JSON PATH, WITHOUT_ARRAY_WRAPPER"
        ),
        &args,
    )?;
    let total = count["total"].as_i64().ok_or("Contagem inválida")?;
    let offset = cond.parametro(i64::from(f.pagina) * i64::from(f.tamanho));
    let limite = cond.parametro(i64::from(f.tamanho));
    let (declare, args) = parametros(&cond);
    let sql = format!(
        "{declare} {PAINEL} SELECT {CAMPOS} FROM painel p WHERE {filtro} ORDER BY {ordem}
        OFFSET {offset} ROWS FETCH NEXT {limite} ROWS ONLY FOR JSON PATH, INCLUDE_NULL_VALUES"
    );
    let data = db.json(&sql, &args)?;
    let serde_json::Value::Array(rows) = data else {
        return Err("Lista de pendências inválida".into());
    };
    Ok((
        metricas,
        rows.into_iter()
            .map(ler_pendencia)
            .collect::<Result<_, _>>()?,
        total,
    ))
}

pub(super) fn exportar(
    db: &SqlServer,
    f: &Filtros,
    mut linha: impl FnMut(Pendencia) -> Result<(), String>,
) -> Result<(), String> {
    let (cond, filtro, ordem) = where_order(f);
    let (declare, args) = parametros(&cond);
    let sql=format!("{declare} {PAINEL} SELECT (SELECT {CAMPOS} FOR JSON PATH, INCLUDE_NULL_VALUES, WITHOUT_ARRAY_WRAPPER)
        FROM painel p WHERE {filtro} ORDER BY {ordem}");
    db.rows_json(&sql, &args, |obj| linha(ler_pendencia(obj)?))
}
