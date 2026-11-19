use crate::models::painel::{Metrica, Pendencia};
use crate::repositories::Repository;
use crate::services::painel::Filtros;
mod sql_server;
use rusqlite::{
    params_from_iter,
    types::{Type, Value},
    Connection, Row,
};

// O cálculo de pendências é distinto das métricas. Por exemplo, TODOS inclui
// os bloqueios SFTP Vedacit que não entram no numerador/denominador das métricas.
const BLOQUEIO: &str = "(l.sistema_destino = 'VEDACIT' COLLATE NOCASE AND l.sftp_cliente IS NOT NULL
    AND l.canhoto_classificacao_operacional COLLATE NOCASE IN ('BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO'))";
const PAINEL: &str = r#"
WITH origem AS (
    SELECT id, sistema_destino COLLATE NOCASE AS destino, occurrence_id, freight_id, chave_nfe,
           trim(substr(chave_nfe,26,9)) AS trecho_nf, substr(chave_nfe,23,3) AS serie,
           status COLLATE NOCASE AS estado, status_dados COLLATE NOCASE AS dados,
           status_canhoto COLLATE NOCASE AS canhoto, mensagem_erro_dados, mensagem_erro_canhoto,
           canhoto_referencia, canhoto_mime_type, sftp_cliente, canhoto_classificacao_operacional,
           data_processamento AS data_filtro, replace(data_processamento,'T',' ') AS data, replace(data_processamento_dados,'T',' ') AS data_dados,
           replace(data_processamento_canhoto,'T',' ') AS data_canhoto,
           (status = 'ERRO_DESTINO' COLLATE NOCASE AND status_dados = 'SUCESSO' COLLATE NOCASE
            AND status_canhoto = 'ERRO_DESTINO' COLLATE NOCASE AND tentativas_canhoto < 3) AS parcial
    FROM tb_log_integracao WHERE COALESCE(arquivado,0) = 0
), painel AS (
    SELECT *,
        CASE WHEN trecho_nf NOT GLOB '*[^0-9]*'
             OR (substr(trecho_nf,1,1) IN ('+','-') AND length(trecho_nf)>1
                 AND substr(trecho_nf,2) NOT GLOB '*[^0-9]*')
             THEN CAST(trecho_nf AS INTEGER) END AS numero_nf,
        COALESCE(NULLIF(trim(dados),''), NULLIF(trim(estado),'')) COLLATE NOCASE AS situacao_dados,
        CASE WHEN parcial THEN 'Erro Parcial - Aguarda Retry'
             ELSE COALESCE(NULLIF(trim(canhoto),''), NULLIF(trim(estado),'')) END COLLATE NOCASE AS situacao_canhoto
    FROM origem
)
"#;

const PENDENTES: &str = "(canhoto='PENDENTE_FOTO' OR dados='ERRO_DESTINO' OR parcial
    OR (canhoto IS NULL AND estado='PENDENTE_FOTO') OR (dados IS NULL AND estado='ERRO_DESTINO'))
    AND NOT (destino='VEDACIT' AND sftp_cliente IS NOT NULL
        AND canhoto_classificacao_operacional COLLATE NOCASE IN ('BLOQUEADO_ORIGEM','BLOQUEADO_DESTINO'))";
const SUCESSOS: &str =
    "(estado IN ('ENVIADO','PROCESSADO') OR dados IN ('SUCESSO','ENVIADO','PROCESSADO')
    OR canhoto IN ('SUCESSO','ENVIADO','PROCESSADO'))";

enum Tipo {
    Numero,
    Texto,
    Status,
    Data,
}

fn coluna(nome: &str) -> Option<(&'static str, Tipo)> {
    Some(match nome {
        "id" => ("id", Tipo::Numero),
        "sistemaDestino" => ("destino", Tipo::Texto),
        "occurrenceId" => ("occurrence_id", Tipo::Numero),
        "freightId" => ("freight_id", Tipo::Numero),
        "chaveNfe" => ("chave_nfe", Tipo::Texto),
        "numeroNf" => ("numero_nf", Tipo::Numero),
        "serieNf" => ("serie", Tipo::Texto),
        "statusDados" => ("situacao_dados", Tipo::Status),
        "statusCanhoto" => ("situacao_canhoto", Tipo::Status),
        "dataProcessamento" => ("data", Tipo::Data),
        "dataProcessamentoDados" => ("data_dados", Tipo::Data),
        "dataProcessamentoCanhoto" => ("data_canhoto", Tipo::Data),
        _ => return None,
    })
}

#[derive(Default)]
struct Condicoes {
    clausulas: Vec<String>,
    valores: Vec<Value>,
    sql_server: bool,
}

impl Condicoes {
    fn parametro(&mut self, valor: impl Into<Value>) -> String {
        self.valores.push(valor.into());
        format!(
            "{}{}",
            if self.sql_server { "@filtro" } else { "?" },
            self.valores.len()
        )
    }

    fn lista(&mut self, itens: &[String]) -> String {
        itens
            .iter()
            .map(|v| self.parametro(v.clone()))
            .collect::<Vec<_>>()
            .join(",")
    }

    fn adicionar_coluna(&mut self, nome: &str, valores: &[String]) {
        let Some((expr, tipo)) = coluna(nome) else {
            return;
        };
        let numero = match valores {
            [unico] => digitos(unico),
            _ => None,
        };
        let cond = match (&tipo, numero) {
            (Tipo::Status, _) => format!("{expr} IN ({})", self.lista(valores)),
            (Tipo::Numero, Some(numero)) => {
                let v = self.parametro(numero);
                format!("{expr} = {v}")
            }
            _ => {
                // Igual a CONVERT(VARCHAR(19), data, 120) na busca por coluna do Java.
                let texto = if matches!(tipo, Tipo::Data) {
                    if self.sql_server {
                        format!("CONVERT(varchar(19), {expr}, 120)")
                    } else {
                        format!("substr({expr},1,19)")
                    }
                } else {
                    expr.to_owned()
                };
                let termos = valores
                    .iter()
                    .map(|v| {
                        let p = self.parametro(format!("%{v}%"));
                        format!("{texto} LIKE {p}")
                    })
                    .collect::<Vec<_>>();
                format!("({})", termos.join(" OR "))
            }
        };
        self.clausulas.push(cond);
    }

    fn pendencias(filtros: &Filtros, sql_server: bool) -> Self {
        let mut cond = Self {
            sql_server,
            ..Default::default()
        };
        let destinos = cond.lista(&filtros.destinos);
        cond.clausulas.push(format!("destino IN ({destinos})"));
        match filtros.escopo.as_str() {
            "TODOS" => (),
            "SUCESSO" => cond.clausulas.push(SUCESSOS.to_owned()),
            _ if sql_server => cond.clausulas.push(sql_server::PENDENTES.to_owned()),
            _ => cond.clausulas.push(PENDENTES.to_owned()),
        }
        if let Some((inicio, fim)) = limites(filtros) {
            let a = cond.parametro(inicio);
            let b = cond.parametro(fim);
            cond.clausulas
                .push(format!("data_filtro >= {a} AND data_filtro < {b}"));
        }
        if let Some(txt) = &filtros.busca {
            let p = cond.parametro(format!("%{txt}%"));
            cond.clausulas.push(format!(
                "(destino LIKE {p} OR chave_nfe LIKE {p} OR numero_nf LIKE {p} OR serie LIKE {p}
                OR situacao_dados LIKE {p} OR situacao_canhoto LIKE {p})"
            ));
        }
        if let Some(txt) = &filtros.codigo {
            let sql = if let Some(n) = digitos(txt) {
                format!("numero_nf = {}", cond.parametro(n))
            } else {
                let p = cond.parametro(format!("%{txt}%"));
                format!("(chave_nfe LIKE {p} OR numero_nf LIKE {p})")
            };
            cond.clausulas.push(sql);
        }
        if !filtros.status.is_empty() {
            let p = cond.lista(&filtros.status);
            cond.clausulas.push(format!(
                "(situacao_dados IN ({p}) OR situacao_canhoto IN ({p}))"
            ));
        }
        for (nome, valores) in &filtros.colunas {
            cond.adicionar_coluna(nome, valores);
        }
        cond
    }
}

fn digitos(txt: &str) -> Option<i64> {
    if txt.bytes().all(|c| c.is_ascii_digit()) {
        txt.parse().ok()
    } else {
        None
    }
}

fn limites(filtros: &Filtros) -> Option<(String, String)> {
    let (inicio, fim) = filtros.periodo?;
    // Datas ISO no SQLite mantêm ordem cronológica. Preserva a coluna sem
    // funções na cláusula de período e inclui todo o último dia.
    let exclusivo = fim
        .tomorrow()
        .map(|d| d.to_string())
        .unwrap_or_else(|_| "9999-12-31~".to_string());
    Some((inicio.to_string(), exclusivo))
}

fn obter_metricas(db: &Connection, filtros: &Filtros) -> rusqlite::Result<Vec<Metrica>> {
    let mut cond = Condicoes::default();
    let destinos = cond.lista(&filtros.destinos);
    let (inicio, fim) = limites(filtros).unwrap_or_else(|| {
        (
            "1900-01-01".to_string(),
            "9999-12-31 23:59:59.999".to_string(),
        )
    });
    let a = cond.parametro(inicio);
    let b = cond.parametro(fim);
    let sql = format!("WITH destinos(nome) AS (VALUES ('VEDACIT'), ('PPG'), ('SELIA'), ('SUPPORTE'))
        SELECT d.nome, count(l.id),
            COALESCE(round(100.0 * SUM(CASE WHEN upper(COALESCE(NULLIF(trim(l.status_dados),''),NULLIF(trim(l.status),'')))
                IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 1 ELSE 0 END) / NULLIF(count(l.id),0), 2), 0.0),
            COALESCE(round(100.0 * SUM(CASE WHEN upper(COALESCE(NULLIF(trim(l.status_canhoto),''),NULLIF(trim(l.status),'')))
                IN ('SUCESSO','ENVIADO','PROCESSADO') THEN 1 ELSE 0 END) / NULLIF(count(l.id),0), 2), 0.0)
        FROM destinos d LEFT JOIN tb_log_integracao l ON l.sistema_destino = d.nome COLLATE NOCASE
            AND COALESCE(l.arquivado,0)=0 AND NOT {BLOQUEIO} AND l.data_processamento >= {a} AND l.data_processamento < {b}
        WHERE d.nome IN ({destinos}) GROUP BY d.nome ORDER BY d.nome");
    let mut s = db.prepare(&sql)?;
    let rows = s.query_map(params_from_iter(&cond.valores), |r| {
        let destino: String = r.get(0)?;
        let (rotulo_dados, rotulo_comprovante) = match destino.as_str() {
            "SELIA" => ("AddEvents", "POD/Comprovante"),
            "SUPPORTE" => ("Ocorrência", "Comprovante"),
            _ => ("XML/Dados", "Canhoto"),
        };
        Ok(Metrica {
            sistema_destino: destino,
            total_registros: r.get(1)?,
            percentual_xml_sucesso: r.get(2)?,
            percentual_canhoto_sucesso: r.get(3)?,
            rotulo_dados,
            rotulo_comprovante,
        })
    })?;
    rows.collect()
}

// Os campos do painel são LocalDateTime sem offset, como PendenciaRowMapper#getTimestamp.
fn data_json(r: &Row<'_>, index: usize) -> rusqlite::Result<Option<String>> {
    r.get::<_, Option<String>>(index)?
        .map(|txt| {
            txt.parse::<jiff::civil::DateTime>()
                .map(|v| v.to_string())
                .map_err(|e| {
                    rusqlite::Error::FromSqlConversionFailure(index, Type::Text, Box::new(e))
                })
        })
        .transpose()
}

fn pendencia(r: &Row<'_>) -> rusqlite::Result<Pendencia> {
    Ok(Pendencia {
        id: r.get(0)?,
        sistema_destino: r.get(1)?,
        occurrence_id: r.get(2)?,
        freight_id: r.get(3)?,
        chave_nfe: r.get(4)?,
        numero_nf: r.get(5)?,
        serie_nf: r.get(6)?,
        status_dados: r.get(7)?,
        status_canhoto: r.get(8)?,
        mensagem_erro_dados: r.get(9)?,
        mensagem_erro_canhoto: r.get(10)?,
        canhoto_referencia: r.get(11)?,
        canhoto_mime_type: r.get(12)?,
        data_processamento: data_json(r, 13)?,
        data_processamento_dados: data_json(r, 14)?,
        data_processamento_canhoto: data_json(r, 15)?,
        possui_imagem_payload: r.get(16)?,
    })
}

impl Repository {
    pub fn consultar_painel(
        &self,
        f: &Filtros,
    ) -> Result<(Vec<Metrica>, Vec<Pendencia>, i64), String> {
        if let Some(db) = self.sql_server() {
            return sql_server::consultar(db, f);
        }
        self.consultar_painel_sqlite(f)
            .map_err(|_| "Falha na consulta ao painel da bancada".into())
    }

    pub fn exportar_pendencias(
        &self,
        filtros: &Filtros,
        linha: impl FnMut(Pendencia) -> Result<(), String>,
    ) -> Result<(), String> {
        sql_server::exportar(
            self.sql_server().ok_or("Exportação requer SQL Server")?,
            filtros,
            linha,
        )
    }

    fn consultar_painel_sqlite(
        &self,
        f: &Filtros,
    ) -> rusqlite::Result<(Vec<Metrica>, Vec<Pendencia>, i64)> {
        let mut db = self.connection()?;
        let tx = db.transaction()?;
        let metricas = obter_metricas(&tx, f)?;
        let mut cond = Condicoes::pendencias(f, false);
        let base = format!("FROM painel WHERE {}", cond.clausulas.join(" AND "));
        let total = tx.query_row(
            &format!("{PAINEL} SELECT count(*) {base}"),
            params_from_iter(&cond.valores),
            |r| r.get(0),
        )?;
        let ordem = f
            .ordenar
            .as_deref()
            .and_then(coluna)
            .map(|(col, _)| format!("{col} {}", if f.crescente { "ASC" } else { "DESC" }))
            .unwrap_or_else(|| "data DESC".to_owned());
        let limite = cond.parametro(i64::from(f.tamanho));
        let inicio = cond.parametro(i64::from(f.pagina) * i64::from(f.tamanho));
        let sql = format!("{PAINEL} SELECT id,destino,occurrence_id,freight_id,chave_nfe,numero_nf,serie,
            situacao_dados,situacao_canhoto,mensagem_erro_dados,mensagem_erro_canhoto,canhoto_referencia,canhoto_mime_type,
            data,data_dados,data_canhoto,(canhoto_referencia IS NOT NULL)
            {base} ORDER BY {ordem}, id DESC LIMIT {limite} OFFSET {inicio}");
        let itens = tx
            .prepare(&sql)?
            .query_map(params_from_iter(&cond.valores), pendencia)?
            .collect::<rusqlite::Result<_>>()?;
        tx.commit()?;
        Ok((metricas, itens, total))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::painel::consultar;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    struct Banco(PathBuf);
    impl Banco {
        fn abrir() -> (Self, Repository) {
            static SERIAL: AtomicU64 = AtomicU64::new(0);
            let file = std::env::temp_dir().join(format!(
                "satelite-painel-{}-{}-{}.sqlite",
                std::process::id(),
                jiff::Timestamp::now().as_nanosecond(),
                SERIAL.fetch_add(1, Ordering::Relaxed)
            ));
            let db = Connection::open(&file).unwrap();
            db.execute_batch(include_str!("../../tests/fixtures/schema_bancada.sql"))
                .unwrap();
            db.execute_batch(include_str!("../../tests/fixtures/painel_bancada.sql"))
                .unwrap();
            let repo = Repository::open(&file).unwrap();
            (Self(file), repo)
        }
    }
    impl Drop for Banco {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    #[test]
    fn metricas_pendencias_e_campos_do_dashboard() {
        let (_db, repo) = Banco::abrir();
        let resposta = consultar(
            &repo,
            &Filtros::da_query("dataInicial=2026-10-01&dataFinal=2026-10-31").unwrap(),
        )
        .unwrap();
        let m = &resposta.metricas_consolidadas;
        assert_eq!(
            m.iter()
                .map(|x| (x.sistema_destino.as_str(), x.total_registros))
                .collect::<Vec<_>>(),
            [("PPG", 2), ("SELIA", 1), ("SUPPORTE", 0), ("VEDACIT", 0)]
        );
        assert_eq!(
            (m[0].percentual_xml_sucesso, m[0].percentual_canhoto_sucesso),
            (100.0, 50.0)
        );
        assert_eq!(
            (m[1].rotulo_dados, m[1].rotulo_comprovante),
            ("AddEvents", "POD/Comprovante")
        );
        let p = resposta.pendencias;
        assert_eq!(
            p.itens.iter().map(|r| r.id).collect::<Vec<_>>(),
            [1004, 1001]
        );
        let item = serde_json::to_value(&p.itens[1]).unwrap();
        assert_eq!(item.as_object().unwrap().len(), 17);
        assert_eq!(item["occurrenceId"], 41);
        assert_eq!(item["freightId"], 51);
        assert_eq!(item["numeroNf"], 422389);
        assert_eq!(item["serieNf"], "017");
        assert_eq!(item["statusCanhoto"], "Erro Parcial - Aguarda Retry");
        assert_eq!(item["mensagemErroDados"], "aviso sintético");
        assert_eq!(item["mensagemErroCanhoto"], "foto sintética");
        assert_eq!(item["canhotoReferencia"], "imagem-de-teste");
        assert_eq!(item["canhotoMimeType"], "image/jpeg");
        assert_eq!(item["possuiImagemPayload"], true);
        assert_eq!(item["dataProcessamento"], "2026-10-01T10:00:00.123");
        assert_eq!(item["dataProcessamentoDados"], "2026-10-01T10:00:01");
        assert_eq!(item["dataProcessamentoCanhoto"], "2026-10-01T10:00:02");
        assert!(p.itens[0].chave_nfe.is_none());
        assert!(p.itens[0].numero_nf.is_none());
        assert_eq!(
            (p.paginacao.total_elementos, p.paginacao.total_paginas),
            (2, 1)
        );
    }

    #[test]
    fn escopos_multivalor_periodos_e_ordem_estavel() {
        let (_db, repo) = Banco::abrir();
        let buscar = |q: &str| {
            let p = consultar(&repo, &Filtros::da_query(q).unwrap())
                .unwrap()
                .pendencias;
            (
                p.itens.iter().map(|r| r.id).collect::<Vec<_>>(),
                p.paginacao,
            )
        };
        let (ids, pg) = buscar("escopo=TODOS&tamanho=2&pagina=1");
        assert_eq!(ids, [1003, 1002]);
        assert_eq!(pg.total_elementos, 5);
        assert!(!pg.primeira_pagina && !pg.ultima_pagina);
        assert_eq!(
            buscar("escopo=sucesso&destino=ppg&destino=SELIA").0,
            [1002, 1001]
        );
        assert_eq!(buscar("escopo=TODOS&sortField=id&sortDirection=asc&f.tabelaStatus=PENDENTE_FOTO&f.tabelaStatus=PROCESSADO").0,[1002,1003,1004]);
        assert_eq!(buscar("escopo=TODOS&f.tabelaBusca=aguarda+retry").0, [1001]);
        assert_eq!(
            buscar("escopo=TODOS&f.tabelaCodigo=000422389&f.tabelaColuna.occurrenceId=43").0,
            [1003]
        );
        assert_eq!(buscar("escopo=TODOS&f.tabelaColuna.sistemaDestino=VED&f.tabelaColuna.sistemaDestino=SEL&f.tabelaColuna.dataProcessamento=2026-10").0,[1004,1003]);
        assert_eq!(
            buscar(
                "escopo=TODOS&f.tabelaColuna.statusCanhoto=Erro%20Parcial%20-%20Aguarda%20Retry"
            )
            .0,
            [1001]
        );
        assert_eq!(
            buscar("escopo=TODOS&dataInicial=2026-11-01&dataFinal=2026-11-01").0,
            [1007]
        );
        let (ids, pg) = buscar("escopo=TODOS&tamanho=1&pagina=99");
        assert!(ids.is_empty() && pg.ultima_pagina);
        assert_eq!(pg.total_paginas, 5);
    }

    #[test]
    fn parametros_invalidos_e_limites_de_paginacao() {
        for q in [
            "destino=OUTRO",
            "dataInicial=2026-01-01",
            "dataInicial=2026-10-01&dataFinal=2026-09-30",
            "dataInicial=2026-02-30&dataFinal=2026-03-10",
            "pagina=x",
            "tamanho=99999999999",
            "dataInicial=2026-10-01T00:00:00&dataFinal=2026-10-02",
        ] {
            assert!(Filtros::da_query(q).is_err(), "{q}");
        }
        let f = Filtros::da_query("tamanho=501&pagina=-1").unwrap();
        assert_eq!((f.tamanho, f.pagina), (500, 0));
        assert_eq!(Filtros::da_query("tamanho=0").unwrap().tamanho, 100);
    }
}
