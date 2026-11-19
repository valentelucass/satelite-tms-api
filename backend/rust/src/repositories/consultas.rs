use super::SqlServer;
use jiff::{
    civil::{Date, DateTime},
    Timestamp,
};
use serde_json::{json, Value};

type Resultado = Result<Value, String>;
const ETAPAS: &str = include_str!("../../sql/auditoria/classificacao_etapas.sql");
const OPERACIONAL: &str = include_str!("../../sql/auditoria/classificacao_operacional.sql");
const RESULTADO_SFTP: &str = include_str!("../../sql/auditoria/resultado_sftp.sql");
const JSON: &str = " FOR JSON PATH, INCLUDE_NULL_VALUES";
const DECLARAR: &str = "DECLARE @destinos varchar(40)=?, @inicio datetime2=?, @fim datetime2=?; ";
const DECLARAR_PENDENCIAS: &str = "DECLARE @destinos varchar(40)=?, @etapa varchar(32)=?, @acao varchar(40)=?, @offset bigint=?, @tamanho int=?; ";
const FILTRO_PENDENCIAS: &str =
    " WHERE acao NOT IN ('CONFIRMADO','IGNORADO') AND etapa=@etapa AND acao=@acao ";

pub struct Periodo {
    pub inicio: Date,
    pub fim: Date,
    pub exclusivo: Date,
}

impl Periodo {
    fn parametros(&self, destinos: &str) -> Vec<String> {
        vec![
            destinos.to_owned(),
            self.inicio.to_string(),
            self.exclusivo.to_string(),
        ]
    }
}

pub enum Indicador {
    Evolucao,
    Resumo,
    Etapas,
    Operacional,
}

pub fn indicadores(db: &SqlServer, tipo: Indicador, periodo: Periodo, destinos: &str) -> Resultado {
    let parametros = periodo.parametros(destinos);
    let consultar = |partes: &[&str]| {
        let sql = format!("{DECLARAR}{}{JSON}", partes.join("\n"));
        db.json(&sql, &parametros)
    };
    let inicio = periodo.inicio.to_string();
    let fim = periodo.fim.to_string();
    match tipo {
        Indicador::Evolucao => {
            // A evolução legada usa parâmetros JPA em UTC. Os demais indicadores usam JDBC local.
            use crate::services::painel::limite_metricas_utc;
            let utc = [
                destinos.to_owned(),
                limite_metricas_utc(periodo.inicio)?,
                limite_metricas_utc(periodo.exclusivo)?,
            ];
            db.json(
                &format!(
                    "{DECLARAR}{}{JSON}",
                    include_str!("../../sql/auditoria/evolucao_diaria.sql")
                ),
                &utc,
            )
        }
        Indicador::Resumo => db.json(
            &format!(
                "{DECLARAR}{}",
                include_str!("../../sql/auditoria/resumo_tabelas.sql")
            ),
            &parametros,
        ),
        Indicador::Etapas => Ok(json!({
            "versao":2, "dataInicial":inicio, "dataFinal":fim,
            "etapas":consultar(&[ETAPAS, include_str!("../../sql/auditoria/indicadores_etapas.sql")])?,
            "evolucao":consultar(&[ETAPAS, include_str!("../../sql/auditoria/evolucao_etapas.sql")])?
        })),
        Indicador::Operacional => {
            let desde = consultar(&["SELECT MIN(ocorrido_em) AS desde FROM dbo.tb_integracao_etapa_evento WHERE sistema_destino IN (SELECT value FROM STRING_SPLIT(@destinos,','))"])?;
            Ok(json!({
                "versao":3, "dataInicial":inicio, "dataFinal":fim,
                "etapas":consultar(&[ETAPAS, OPERACIONAL, include_str!("../../sql/auditoria/indicadores_operacionais.sql")])?,
                "evolucao":consultar(&[ETAPAS, OPERACIONAL, include_str!("../../sql/auditoria/evolucao_operacional.sql")])?,
                "atividade":consultar(&[include_str!("../../sql/auditoria/atividade_operacional.sql")])?,
                "historicoDesde":desde[0]["desde"], "escopoTransmissoes":"VEDACIT"
            }))
        }
    }
}

pub fn pendencias(
    db: &SqlServer,
    destinos: &str,
    etapa: &str,
    acao: &str,
    pagina: i32,
    tamanho: i32,
) -> Resultado {
    let offset = i64::from(pagina) * i64::from(tamanho);
    let params = [
        destinos.to_owned(),
        etapa.to_owned(),
        acao.to_owned(),
        offset.to_string(),
        tamanho.to_string(),
    ];
    let consultar = |selecao: &str, ordem: &str| {
        db.json(
        &format!("{DECLARAR_PENDENCIAS}{ETAPAS}\n{OPERACIONAL}\n{selecao}{FILTRO_PENDENCIAS}{ordem}{JSON}"), &params)
    };
    let total = consultar("SELECT COUNT_BIG(*) AS n FROM operacionais", "")?;
    let itens = consultar(include_str!("../../sql/auditoria/pendencias_operacionais.sql"),
        " ORDER BY pendente_desde, data_etapa, id OFFSET @offset ROWS FETCH NEXT @tamanho ROWS ONLY")?;
    Ok(json!({"itens":itens, "totalElementos":total[0]["n"], "pagina":pagina, "tamanho":tamanho}))
}

pub enum Reconciliacao {
    Execucoes(String),
    Resumo(String, i64),
    Itens(i64, i64, i32),
}

pub fn reconciliacao(db: &SqlServer, consulta: Reconciliacao) -> Resultado {
    let (sql, params, datas_jdbc) = match consulta {
        Reconciliacao::Execucoes(cliente) if cliente == "VEDACIT" => (
            "SELECT TOP (30) e.*, (SELECT COUNT_BIG(*) FROM dbo.tb_reconciliacao_vedacit_item i WHERE i.execucao_id=e.id) revisados FROM dbo.tb_reconciliacao_vedacit_execucao e ORDER BY e.id DESC",
            vec![], true),
        Reconciliacao::Execucoes(cliente) => (
            "SELECT TOP (30) e.*, (SELECT COUNT_BIG(*) FROM dbo.tb_reconciliacao_cliente_item i WHERE i.execucao_id=e.id) revisados FROM dbo.tb_reconciliacao_cliente_execucao e WHERE cliente=? ORDER BY e.id DESC", vec![cliente], true),
        Reconciliacao::Resumo(cliente,id) if cliente == "VEDACIT" => (
            "DECLARE @id bigint=?; SELECT etapa,resultado,quantidade FROM (
                SELECT 'XML' etapa,resultado_xml resultado,COUNT_BIG(*) quantidade FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=@id GROUP BY resultado_xml
                UNION ALL SELECT 'COMPROVANTE',resultado_comprovante,COUNT_BIG(*) FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=@id GROUP BY resultado_comprovante
                UNION ALL SELECT 'ACAO',acao,COUNT_BIG(*) FROM dbo.tb_reconciliacao_vedacit_item WHERE execucao_id=@id GROUP BY acao
            ) r", vec![id.to_string()], false),
        Reconciliacao::Resumo(cliente,id) => (
            "SELECT i.resultado_dados,i.resultado_comprovante,i.acao,COUNT_BIG(*) quantidade
             FROM dbo.tb_reconciliacao_cliente_item i JOIN dbo.tb_reconciliacao_cliente_execucao e ON e.id=i.execucao_id
             WHERE e.id=CAST(? AS bigint) AND e.cliente=? GROUP BY i.resultado_dados,i.resultado_comprovante,i.acao",
            vec![id.to_string(), cliente], false),
        Reconciliacao::Itens(id, depois, tamanho) => (
            "DECLARE @tamanho int=?; SELECT TOP (@tamanho) * FROM dbo.tb_reconciliacao_vedacit_item
             WHERE execucao_id=CAST(? AS bigint) AND log_id>CAST(? AS bigint) ORDER BY log_id",
            vec![tamanho.clamp(1,200).to_string(), id.to_string(), depois.max(0).to_string()], true)
    };
    let mut rows = db.json(&format!("{sql}{JSON}"), &params)?;
    // Os mapas do JDBC expõem DATE e TIMESTAMP como instantes UTC com milissegundos.
    if datas_jdbc {
        for row in rows
            .as_array_mut()
            .ok_or("Lista de reconciliação inválida")?
        {
            for (campo, valor) in row
                .as_object_mut()
                .ok_or("Registro de reconciliação inválido")?
            {
                if campo.ends_with("_em")
                    || matches!(campo.as_str(), "dia_referencia" | "fontes_dia")
                {
                    if let Some(texto) = valor.as_str() {
                        *valor = Value::String(data_jdbc(texto)?);
                    }
                }
            }
        }
    }
    Ok(rows)
}

fn data_jdbc(texto: &str) -> Result<String, String> {
    let data: DateTime = if texto.len() == 10 {
        texto.parse::<Date>().map(|d| d.at(0, 0, 0, 0))
    } else {
        texto.parse()
    }
    .map_err(|_| "Data de reconciliação inválida")?;
    let utc: Timestamp = data
        .in_tz("America/Sao_Paulo")
        .map_err(|_| "Fuso de reconciliação inválido")?
        .timestamp();
    Ok(utc.strftime("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
}

#[cfg(test)]
mod testes {
    use super::data_jdbc;

    #[test]
    fn datas_dos_mapas_jdbc_seguem_o_fuso_historico_e_a_precisao_do_java() {
        assert_eq!(data_jdbc("2026-11-18").unwrap(), "2026-11-18T03:00:00.000Z");
        assert_eq!(
            data_jdbc("2018-12-15T23:59:45.67892").unwrap(),
            "2018-12-16T01:59:45.678Z"
        );
    }
}

const CAMPOS_SFTP: &str = "
    e.sftp_cliente AS cliente, e.inicio_em AS inicioUltimoCiclo, e.fim_em AS fimUltimoCiclo,
    e.conexao, e.status_ciclo AS statusCiclo, e.arquivos_validos AS arquivosValidos,
    e.arquivos_rejeitados AS arquivosRejeitados, e.selecionados, e.enviados, e.pendentes,
    e.saldo, e.bloqueios, e.timeouts_ambiguos AS timeoutsAmbiguos, e.duracao_ms AS duracaoMs,
    DATEADD(MINUTE,30,e.fim_em) AS proximaExecucaoEstimada, e.xml_habilitado AS xmlHabilitado,
    e.xml_avaliados AS xmlAvaliados, e.xml_enviados AS xmlEnviados, e.xml_ja_processados AS xmlJaProcessados,
    e.xml_pendentes AS xmlPendentes, e.xml_erros AS xmlErros, e.erros_comprovante AS errosComprovante,
    e.motivo_falha AS motivoFalha, e.atualizado_em AS atualizadoEm, 'SFTP' AS origemComprovantes";

pub fn sftp_clientes(db: &SqlServer) -> Resultado {
    db.json(&format!(
        "WITH ultimo AS (SELECT *,ROW_NUMBER() OVER (PARTITION BY sftp_cliente ORDER BY inicio_em DESC,id DESC) AS posicao
         FROM dbo.tb_work_sftp_cliente_execucao) SELECT {CAMPOS_SFTP},{RESULTADO_SFTP} AS resultadoExecucao
         FROM ultimo e WHERE posicao=1 ORDER BY sftp_cliente{JSON}"), &[])
}

pub struct FiltrosSftp {
    pub periodo: Option<Periodo>,
    pub cliente: Option<String>,
    pub status: Option<String>,
    pub origem: Option<String>,
    pub pagina: i32,
    pub tamanho: i32,
}

pub fn sftp_execucoes(db: &SqlServer, f: FiltrosSftp) -> Resultado {
    let (inicio, fim) = match f.periodo {
        Some(p) => (p.inicio.to_string(), p.exclusivo.to_string()),
        None => (
            "1900-01-01".to_owned(),
            "9999-12-31T23:59:59.999".to_owned(),
        ),
    };
    let mut where_sql = " FROM dbo.tb_work_sftp_cliente_execucao e WHERE ((e.fim_em>=@inicio AND e.fim_em<@fim) OR (e.fim_em IS NULL AND e.inicio_em>=@inicio AND e.inicio_em<@fim))".to_owned();
    let mut parametros = vec![inicio, fim];
    if let Some(cliente) = f.cliente {
        where_sql.push_str(" AND e.sftp_cliente=?");
        parametros.push(cliente);
    }
    if let Some(origem) = f.origem {
        where_sql.push_str(" AND ?='SFTP'");
        parametros.push(origem.to_uppercase());
    }
    if let Some(status) = f.status {
        let calculado = matches!(
            status.as_str(),
            "COM_PENDENCIAS" | "FALHA_TECNICA" | "INTERROMPIDO" | "SEM_PENDENCIAS"
        );
        where_sql.push_str(" AND ");
        where_sql.push_str(if calculado {
            RESULTADO_SFTP
        } else {
            "e.status_ciclo"
        });
        where_sql.push_str("=?");
        parametros.push(if status == "SEM_PENDENCIAS" {
            "CONCLUIDO".to_owned()
        } else {
            status
        });
    }
    let declarar = "DECLARE @inicio datetime2=?, @fim datetime2=?; ";
    let total = db.json(
        &format!("{declarar}SELECT COUNT_BIG(*) AS total{where_sql}{JSON}"),
        &parametros,
    )?;
    let total = total[0]["total"].as_i64().ok_or("Contagem SFTP inválida")?;
    let tamanho = i64::from(f.tamanho);
    parametros.push((i64::from(f.pagina) * tamanho).to_string());
    parametros.push(f.tamanho.to_string());
    let itens = db.json(&format!(
        "{declarar}SELECT {CAMPOS_SFTP}, {RESULTADO_SFTP} AS resultadoExecucao{where_sql}
         ORDER BY e.inicio_em DESC,e.id DESC OFFSET CAST(? AS bigint) ROWS FETCH NEXT CAST(? AS int) ROWS ONLY{JSON}"), &parametros)?;
    let paginas = total / tamanho + i64::from(total % tamanho != 0);
    Ok(
        json!({"itens":itens, "paginacao":{"pagina":f.pagina,"tamanho":f.tamanho,
        "totalElementos":total,"totalPaginas":paginas,"primeiraPagina":f.pagina==0,
        "ultimaPagina":i64::from(f.pagina)>=paginas-1}}),
    )
}
