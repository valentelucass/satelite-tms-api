use crate::repositories::Repository;
use jiff::{civil::DateTime, tz::TimeZone};
use regex::Regex;
use serde_json::{json, Value};
use std::sync::LazyLock;

#[derive(Clone, Copy)]
pub enum Lista {
    Erros,
    Historico,
}

pub struct Consulta {
    pub lista: Lista,
    pub destinos: Vec<String>,
    pub periodo: Option<(String, String)>,
    pub pagina: i32,
    pub tamanho: i32,
}

const ERROS: &str = "FROM dbo.tb_log_integracao l WHERE l.status='ERRO_DESTINO'
    AND COALESCE(l.arquivado,0)=0 AND (l.tentativas_dados>=3 OR l.tentativas_canhoto>=3)
    AND l.sistema_destino IN ({destinos})
    AND NOT EXISTS (SELECT 1 FROM dbo.tb_log_integracao posterior
        WHERE posterior.sistema_destino=l.sistema_destino AND (
          (l.chave_cte IS NOT NULL AND l.chave_cte<>'' AND posterior.chave_cte=l.chave_cte)
          OR ((l.chave_cte IS NULL OR l.chave_cte='') AND l.occurrence_id IS NOT NULL AND posterior.occurrence_id=l.occurrence_id))
        AND (posterior.data_processamento>l.data_processamento OR
            (posterior.data_processamento=l.data_processamento AND posterior.id>l.id)))";
const HISTORICO: &str = "FROM dbo.tb_log_integracao_quarentena_evento e
    JOIN dbo.tb_log_integracao l ON l.id=e.log_integracao_id
    WHERE e.tipo_evento='REPESCAGEM' AND l.sistema_destino IN ({destinos})
    AND e.data_evento>=CONVERT(datetime2,?,126) AND e.data_evento<CONVERT(datetime2,?,126)";
const CAMPOS_ERROS:&str = "l.id,l.sistema_destino destino,l.chave_nfe chaveNfe,
    CASE WHEN COALESCE(l.tentativas_dados,0)>COALESCE(l.tentativas_canhoto,0)
         THEN COALESCE(l.tentativas_dados,0) ELSE COALESCE(l.tentativas_canhoto,0) END tentativas,
    l.mensagem_erro_dados msgDados,l.mensagem_erro_canhoto msgCanhoto,l.erro msgErro,
    (SELECT MAX(d) FROM (VALUES(l.data_processamento),(l.data_processamento_dados),(l.data_processamento_canhoto)) a(d)) dataUltimaTentativa";
const CAMPOS_HISTORICO:&str = "e.id,l.sistema_destino destino,l.chave_nfe chaveNfe,e.etapa,
    (SELECT MIN(entrada.data_evento) FROM dbo.tb_log_integracao_quarentena_evento entrada
     WHERE entrada.log_integracao_id=e.log_integracao_id AND entrada.tipo_evento='ENTRADA_QUARENTENA') entradaQuarentenaEm,
    e.data_evento reprocessadoEm,e.resultado,e.mensagem msgErro";

static TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>]+>").unwrap());
static PREFIXO: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?:Caused by:\s*)?[a-zA-Z0-9_.$]+(?:Exception|Error):\s*").unwrap()
});

fn limpar(mensagem: &str) -> String {
    let texto = TAG.replace_all(mensagem, " ");
    let mut partes = Vec::new();
    for linha in texto
        .split([
            '\r', '\n', '\u{b}', '\u{c}', '\u{85}', '\u{2028}', '\u{2029}',
        ])
        .map(str::trim)
    {
        if !linha.starts_with("at ") && !linha.starts_with("Suppressed:") {
            partes.extend(
                PREFIXO
                    .replace(linha, "")
                    .split_ascii_whitespace()
                    .map(str::to_owned),
            );
        }
    }
    if partes.is_empty() {
        "Motivo indisponivel".into()
    } else {
        partes.join(" ")
    }
}

fn data_formatada(valor: &mut Value, utc: bool, csv: bool) -> Result<(), String> {
    let Some(texto) = valor.as_str() else {
        return Ok(());
    };
    let original: DateTime = texto.parse().map_err(|_| "Data inválida")?;
    let data = if utc {
        original
            .to_zoned(TimeZone::UTC)
            .and_then(|z| z.in_tz("America/Sao_Paulo"))
            .map_err(|_| "Fuso inválido")?
            .datetime()
    } else {
        original
    };
    let mut s = data.strftime("%Y-%m-%dT%H:%M:%S").to_string();
    let nanos = data.subsec_nanosecond();
    if nanos > 0 {
        let fracao = format!("{nanos:09}");
        let significativos = fracao.trim_end_matches('0').len();
        let len = if csv {
            significativos.div_ceil(3) * 3
        } else {
            significativos
        };
        s.push('.');
        s.push_str(&fracao[..len]);
    } else if csv && data.second() == 0 {
        s.truncate(16);
    }
    *valor = Value::String(s);
    Ok(())
}

fn mapear(mut obj: Value, lista: Lista, csv: bool) -> Result<Value, String> {
    let valores = obj.as_object_mut().ok_or("Linha de quarentena inválida")?;
    let nf = valores
        .get("chaveNfe")
        .and_then(Value::as_str)
        .and_then(|s| s.get(25..34))
        .and_then(|s| s.parse::<i64>().ok());
    valores.insert("numeroNf".into(), json!(nf));
    let msg = ["msgDados", "msgCanhoto", "msgErro"]
        .iter()
        .filter_map(|k| valores.remove(*k))
        .find_map(|x| {
            x.as_str()
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned)
        })
        .unwrap_or_default();
    for k in ["msgDados", "msgCanhoto", "msgErro"] {
        valores.remove(k);
    }
    match lista {
        Lista::Erros => {
            valores.insert("erroLimpo".into(), limpar(&msg).into());
            if let Some(v) = valores.get_mut("dataUltimaTentativa") {
                data_formatada(v, !csv, csv)?;
            }
        }
        Lista::Historico => {
            valores.insert("motivo".into(), limpar(&msg).into());
            for k in ["entradaQuarentenaEm", "reprocessadoEm"] {
                if let Some(v) = valores.get_mut(k) {
                    data_formatada(v, true, csv)?;
                }
            }
        }
    }
    Ok(obj)
}

impl Consulta {
    fn origem(&self) -> (String, Vec<String>, &'static str, &'static str) {
        let (from, campos, ordem) = match self.lista {
            Lista::Erros => (ERROS, CAMPOS_ERROS, "l.data_processamento DESC,l.id DESC"),
            Lista::Historico => (HISTORICO, CAMPOS_HISTORICO, "e.data_evento DESC,e.id DESC"),
        };
        let mut params = self.destinos.clone();
        if let Some((ini, fim)) = &self.periodo {
            params.push(ini.clone());
            params.push(fim.clone());
        }
        let marcadores = vec!["?"; self.destinos.len()].join(",");
        (
            from.replace("{destinos}", &marcadores),
            params,
            campos,
            ordem,
        )
    }

    pub fn pagina(&self, repo: &Repository) -> Result<Value, String> {
        let db = repo.sql_server().ok_or("Quarentena requer SQL Server")?;
        let (origem, params, campos, ordem) = self.origem();
        let offset = i64::from(self.pagina) * i64::from(self.tamanho);
        let mut content = Vec::new();
        let sql = format!(
            "SELECT (SELECT {campos} FOR JSON PATH, INCLUDE_NULL_VALUES, WITHOUT_ARRAY_WRAPPER)
            {origem} ORDER BY {ordem} OFFSET {offset} ROWS FETCH NEXT {} ROWS ONLY",
            self.tamanho
        );
        db.rows_json(&sql, &params, |row| {
            content.push(mapear(row, self.lista, false)?);
            Ok(())
        })?;
        let n = content.len();
        let total = if n < self.tamanho as usize && (offset == 0 || n > 0) {
            offset + n as i64
        } else {
            let sql = format!(
                "SELECT COUNT_BIG(1) AS total {origem} FOR JSON PATH, WITHOUT_ARRAY_WRAPPER"
            );
            db.json(&sql, &params)?["total"]
                .as_i64()
                .ok_or("Total inválido")?
        };
        let paginas = (total + i64::from(self.tamanho) - 1) / i64::from(self.tamanho);
        Ok(
            json!({"content":content,"empty":n==0,"first":self.pagina==0,"last":i64::from(self.pagina)+1>=paginas,
            "number":self.pagina,"numberOfElements":n,"pageable":{"offset":offset,"pageNumber":self.pagina,
            "pageSize":self.tamanho,"paged":true,"sort":[],"unpaged":false},"size":self.tamanho,
            "sort":[],"totalElements":total,"totalPages":paginas}),
        )
    }

    pub fn exportar(
        &self,
        repo: &Repository,
        emitir: &mut dyn FnMut(Vec<String>) -> Result<(), String>,
    ) -> Result<(), String> {
        let db = repo.sql_server().ok_or("Quarentena requer SQL Server")?;
        let (origem, params, campos, ordem) = self.origem();
        let sql = format!(
            "SELECT (SELECT {campos} FOR JSON PATH, INCLUDE_NULL_VALUES, WITHOUT_ARRAY_WRAPPER)
            {origem} ORDER BY {ordem}"
        );
        let colunas: &[&str] = match self.lista {
            Lista::Erros => &[
                "destino",
                "numeroNf",
                "erroLimpo",
                "chaveNfe",
                "tentativas",
                "dataUltimaTentativa",
            ],
            Lista::Historico => &[
                "destino",
                "numeroNf",
                "etapa",
                "entradaQuarentenaEm",
                "reprocessadoEm",
                "resultado",
                "motivo",
            ],
        };
        db.rows_json(&sql, &params, |row| {
            let obj = mapear(row, self.lista, true)?;
            emitir(
                colunas
                    .iter()
                    .map(|k| match &obj[k] {
                        Value::String(s) => s.clone(),
                        Value::Null => String::new(),
                        v => v.to_string(),
                    })
                    .collect(),
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn limpa_motivo_preservando_o_texto_da_aplicacao() {
        assert_eq!(
            limpar("<b>Falhou</b>\n at com.teste.Foo\nCaused by: io.BarException: detalhe  aqui"),
            "Falhou detalhe aqui"
        );
        assert_eq!(limpar("\nSuppressed: falha"), "Motivo indisponivel");
    }
    #[test]
    fn formatos_de_data_do_jpa_e_do_csv() {
        let mut j = json!("2026-11-18T12:00:00.120");
        data_formatada(&mut j, true, false).unwrap();
        assert_eq!(j, "2026-11-18T09:00:00.12");
        let mut j = json!("2026-11-18T12:00:00.120");
        data_formatada(&mut j, false, true).unwrap();
        assert_eq!(j, "2026-11-18T12:00:00.120");
    }
}
