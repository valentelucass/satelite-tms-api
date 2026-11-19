use std::collections::BTreeMap;

use jiff::{civil::Date, Timestamp};

use crate::models::painel::{AuditoriaIntegracoes, Paginacao, Pendencias};
use crate::repositories::Repository;

pub const DESTINOS: [&str; 4] = ["PPG", "VEDACIT", "SELIA", "SUPPORTE"];

fn data_sql(texto: &str) -> Option<Date> {
    let d = texto.parse::<Date>().ok()?;
    (texto.len() == 10 && d.to_string() == texto).then_some(d)
}

#[derive(Debug)]
pub struct Filtros {
    pub pagina: i32,
    pub tamanho: i32,
    pub periodo: Option<(Date, Date)>,
    pub destinos: Vec<String>,
    pub escopo: String,
    pub busca: Option<String>,
    pub codigo: Option<String>,
    pub status: Vec<String>,
    pub colunas: BTreeMap<String, Vec<String>>,
    pub ordenar: Option<String>,
    pub crescente: bool,
}

impl Filtros {
    pub fn da_query(query: &str) -> Result<Self, &'static str> {
        let mut params: BTreeMap<String, Vec<String>> = BTreeMap::new();
        for (campo, valor) in form_urlencoded::parse(query.as_bytes()) {
            params
                .entry(campo.into_owned())
                .or_default()
                .push(valor.trim().to_owned());
        }
        let primeiro = |key: &str| params.get(key)?.first().filter(|v| !v.is_empty()).cloned();
        let inteiro = |key: &str, padrao: i32| -> Result<i32, &'static str> {
            match primeiro(key) {
                None => Ok(padrao),
                Some(s) => s.parse().map_err(|_| "Página ou tamanho inválido."),
            }
        };
        let periodo = match (primeiro("dataInicial"), primeiro("dataFinal")) {
            (None, None) => None,
            (Some(i), Some(f)) => {
                let inicio = data_sql(&i).ok_or("dataInicial deve estar no formato yyyy-MM-dd.")?;
                let fim = data_sql(&f).ok_or("dataFinal deve estar no formato yyyy-MM-dd.")?;
                if fim < inicio {
                    return Err("dataFinal nao pode ser anterior a dataInicial.");
                }
                Some((inicio, fim))
            }
            _ => {
                return Err(
                    "Informe dataInicial e dataFinal para filtrar a auditoria de integracoes.",
                )
            }
        };
        let valores = |key: &str| -> Vec<String> {
            let mut v = params.get(key).cloned().unwrap_or_default();
            v.retain(|x| !x.is_empty());
            v.sort();
            v.dedup();
            v
        };
        let mut destinos = valores("destino");
        for d in &mut destinos {
            d.make_ascii_uppercase();
            if !DESTINOS.contains(&d.as_str()) {
                return Err("Destino de integração inválido.");
            }
        }
        if destinos.is_empty() {
            destinos = DESTINOS.iter().map(|s| s.to_string()).collect();
        }
        let colunas = params
            .keys()
            .filter_map(|key| {
                let coluna = key.strip_prefix("f.tabelaColuna.")?.trim();
                let v = valores(key);
                (!coluna.is_empty() && !v.is_empty()).then(|| (coluna.to_string(), v))
            })
            .collect();
        let tamanho = inteiro("tamanho", 100)?;
        Ok(Self {
            pagina: inteiro("pagina", 0)?.max(0),
            tamanho: if tamanho <= 0 { 100 } else { tamanho.min(500) },
            periodo,
            destinos,
            colunas,
            escopo: primeiro("escopo").unwrap_or_default().to_ascii_uppercase(),
            busca: primeiro("f.tabelaBusca"),
            codigo: primeiro("f.tabelaCodigo"),
            status: valores("f.tabelaStatus"),
            ordenar: primeiro("sortField"),
            crescente: primeiro("sortDirection").is_some_and(|s| s.eq_ignore_ascii_case("asc")),
        })
    }
}

pub fn consultar(repo: &Repository, filtros: &Filtros) -> rusqlite::Result<AuditoriaIntegracoes> {
    let (metricas_consolidadas, itens, total_elementos) = repo.consultar_painel(filtros)?;
    let tamanho = i64::from(filtros.tamanho);
    let total_paginas = total_elementos / tamanho + i64::from(total_elementos % tamanho != 0);
    let paginacao = Paginacao {
        pagina: filtros.pagina,
        tamanho: filtros.tamanho,
        total_elementos,
        total_paginas,
        primeira_pagina: filtros.pagina == 0,
        ultima_pagina: i64::from(filtros.pagina) >= total_paginas - 1,
    };
    let agora = Timestamp::now()
        .in_tz("America/Sao_Paulo")
        .map_err(|_| rusqlite::Error::InvalidQuery)?;
    Ok(AuditoriaIntegracoes {
        gerado_em: agora.datetime().to_string(),
        metricas_consolidadas,
        pendencias: Pendencias { itens, paginacao },
    })
}
