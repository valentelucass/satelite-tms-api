use super::csv;
use crate::repositories::{
    consultas::{self, FiltrosSftp, Indicador, Periodo, Reconciliacao},
    Repository, SqlServer,
};
use crate::services::painel::{Filtros, DESTINOS};
use axum::{
    extract::{Path, State},
    http::{StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use jiff::{civil::Date, tz::TimeZone, Timestamp};
use serde_json::{json, Value};
use std::collections::HashMap;

async fn metodos() -> impl IntoResponse {
    [(axum::http::header::ALLOW, "GET,HEAD,OPTIONS")]
}

pub fn router(repo: Repository) -> Router {
    Router::new()
        .route(
            "/api/auditoria/integracoes-clientes/exportacao",
            get(exportacao).options(metodos),
        )
        .route(
            "/api/auditoria/integracoes-clientes/evolucao-diaria",
            get(evolucao).options(metodos),
        )
        .route(
            "/api/auditoria/integracoes-clientes/resumo-tabelas",
            get(resumo_tabelas).options(metodos),
        )
        .route(
            "/api/auditoria/integracoes-clientes/indicadores-etapas",
            get(etapas).options(metodos),
        )
        .route(
            "/api/auditoria/integracoes-clientes/indicadores-operacionais",
            get(operacionais).options(metodos),
        )
        .route(
            "/api/auditoria/integracoes-clientes/pendencias-etapas",
            get(pendencias).options(metodos),
        )
        .route(
            "/api/auditoria/vedacit-sftp/clientes",
            get(sftp_clientes).options(metodos),
        )
        .route(
            "/api/auditoria/vedacit-sftp/execucoes",
            get(sftp_execucoes).options(metodos),
        )
        .route(
            "/api/auditoria/vedacit/reconciliacoes",
            get(vedacit_execucoes).options(metodos),
        )
        .route(
            "/api/auditoria/vedacit/reconciliacoes/{id}/resumo",
            get(vedacit_resumo).options(metodos),
        )
        .route(
            "/api/auditoria/vedacit/reconciliacoes/{id}/itens",
            get(vedacit_itens).options(metodos),
        )
        .route(
            "/api/auditoria/reconciliacoes/{cliente}",
            get(cliente_execucoes).options(metodos),
        )
        .route(
            "/api/auditoria/reconciliacoes/{cliente}/{id}/resumo",
            get(cliente_resumo).options(metodos),
        )
        .with_state(repo)
}

struct Params(HashMap<String, Vec<String>>);

impl Params {
    fn new(uri: &Uri) -> Self {
        let mut map: HashMap<String, Vec<String>> = HashMap::new();
        for (k, v) in form_urlencoded::parse(uri.query().unwrap_or_default().as_bytes()) {
            map.entry(k.into_owned()).or_default().push(v.into_owned());
        }
        Self(map)
    }

    fn texto(&self, nome: &str) -> Option<&str> {
        self.0
            .get(nome)?
            .first()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
    }

    fn inteiro<T: std::str::FromStr>(&self, nome: &str, padrao: T) -> Result<T, ()> {
        self.texto(nome)
            .map_or(Ok(padrao), |v| v.parse().map_err(|_| ()))
    }

    fn pagina(&self, padrao: i32, maximo: i32) -> Result<(i32, i32), ()> {
        let pagina = self.inteiro("pagina", 0i32)?.max(0);
        let tamanho = self.inteiro("tamanho", padrao)?;
        Ok((pagina, if tamanho <= 0 { 100 } else { tamanho }.min(maximo)))
    }

    fn destinos(&self) -> Result<String, ()> {
        let valores = self.0.get("destino").map(Vec::as_slice).unwrap_or_default();
        // Spring converte uma ocorrência separada por vírgulas e aceita chaves repetidas.
        let lista = match valores {
            [unico] => unico.split(',').collect::<Vec<_>>(),
            varios => varios.iter().map(String::as_str).collect(),
        };
        let mut selecionados = Vec::new();
        for valor in lista {
            let destino = valor.trim().to_ascii_uppercase();
            if destino.is_empty() {
                continue;
            }
            if !DESTINOS.contains(&destino.as_str()) {
                return Err(());
            }
            if !selecionados.contains(&destino) {
                selecionados.push(destino);
            }
        }
        if selecionados.is_empty() {
            return Ok(DESTINOS.join(","));
        }
        Ok(selecionados.join(","))
    }

    fn periodo(&self) -> Result<Option<Periodo>, ()> {
        let data = |str: &str| -> Result<Date, ()> {
            let dia: Date = str.parse().map_err(|_| ())?;
            (dia.to_string() == str && str.len() == 10)
                .then_some(dia)
                .ok_or(())
        };
        match (self.texto("dataInicial"), self.texto("dataFinal")) {
            (None, None) => Ok(None),
            (Some(a), Some(b)) => {
                let inicio = data(a)?;
                let fim = data(b)?;
                if fim < inicio {
                    return Err(());
                }
                Ok(Some(Periodo {
                    inicio,
                    fim,
                    exclusivo: fim.tomorrow().map_err(|_| ())?,
                }))
            }
            _ => Err(()),
        }
    }
}

fn erro(uri: &Uri, status: StatusCode) -> Response {
    let timestamp = Timestamp::now()
        .to_zoned(TimeZone::UTC)
        .strftime("%Y-%m-%dT%H:%M:%S%.3fZ")
        .to_string();
    (status,Json(json!({"timestamp":timestamp,"status":status.as_u16(),"error":status.canonical_reason(),"path":uri.path()}))).into_response()
}

async fn executar<F>(repo: Repository, uri: &Uri, consulta: F) -> Response
where
    F: FnOnce(&SqlServer) -> Result<Value, String> + Send + 'static,
{
    let resultado = tokio::task::spawn_blocking(move || {
        consulta(repo.sql_server().ok_or("SQL Server indisponível")?)
    })
    .await;
    match resultado {
        Ok(Ok(valor)) => Json(valor).into_response(),
        _ => erro(uri, StatusCode::INTERNAL_SERVER_ERROR),
    }
}

async fn indicador(repo: Repository, uri: Uri, tipo: Indicador) -> Response {
    let q = Params::new(&uri);
    let (Ok(Some(periodo)), Ok(destinos)) = (q.periodo(), q.destinos()) else {
        return erro(&uri, StatusCode::BAD_REQUEST);
    };
    executar(repo, &uri, move |db| {
        consultas::indicadores(db, tipo, periodo, &destinos)
    })
    .await
}
async fn evolucao(State(r): State<Repository>, u: Uri) -> Response {
    indicador(r, u, Indicador::Evolucao).await
}
async fn resumo_tabelas(State(r): State<Repository>, u: Uri) -> Response {
    indicador(r, u, Indicador::Resumo).await
}
async fn etapas(State(r): State<Repository>, u: Uri) -> Response {
    indicador(r, u, Indicador::Etapas).await
}
async fn operacionais(State(r): State<Repository>, u: Uri) -> Response {
    indicador(r, u, Indicador::Operacional).await
}

async fn pendencias(State(repo): State<Repository>, uri: Uri) -> Response {
    let q = Params::new(&uri);
    let (Ok(destinos), Ok((pagina, tamanho)), Some(etapa), Some(acao)) = (
        q.destinos(),
        q.pagina(25, 100),
        q.0.get("etapa").and_then(|v| v.first()).map(String::as_str),
        q.0.get("acao").and_then(|v| v.first()).map(String::as_str),
    ) else {
        return erro(&uri, StatusCode::BAD_REQUEST);
    };
    if !["DADOS", "COMPROVANTE"].contains(&etapa)
        || ![
            "AGUARDANDO_PROCESSAMENTO",
            "NOVA_TENTATIVA",
            "AGUARDANDO_ORIGEM",
            "AGUARDANDO_CONFIRMACAO",
            "INTERVENCAO",
            "A_CONFERIR",
        ]
        .contains(&acao)
    {
        return erro(&uri, StatusCode::BAD_REQUEST);
    }
    let (etapa, acao) = (etapa.to_owned(), acao.to_owned());
    executar(repo, &uri, move |db| {
        consultas::pendencias(db, &destinos, &etapa, &acao, pagina, tamanho)
    })
    .await
}

async fn sftp_clientes(State(repo): State<Repository>, uri: Uri) -> Response {
    executar(repo, &uri, consultas::sftp_clientes).await
}

async fn sftp_execucoes(State(repo): State<Repository>, uri: Uri) -> Response {
    let q = Params::new(&uri);
    let (Ok(periodo), Ok((pagina, tamanho))) = (q.periodo(), q.pagina(50, 500)) else {
        return erro(&uri, StatusCode::BAD_REQUEST);
    };
    let filtros = FiltrosSftp {
        periodo,
        pagina,
        tamanho,
        cliente: q.texto("cliente").map(str::to_owned),
        status: q.texto("status").map(str::to_owned),
        origem: q.texto("origem").map(str::to_owned),
    };
    executar(repo, &uri, move |db| consultas::sftp_execucoes(db, filtros)).await
}

async fn reconciliar(repo: Repository, uri: Uri, consulta: Reconciliacao) -> Response {
    executar(repo, &uri, move |db| consultas::reconciliacao(db, consulta)).await
}

async fn vedacit_execucoes(State(repo): State<Repository>, uri: Uri) -> Response {
    reconciliar(repo, uri, Reconciliacao::Execucoes("VEDACIT".to_owned())).await
}
async fn cliente_execucoes(
    State(repo): State<Repository>,
    uri: Uri,
    Path(cliente): Path<String>,
) -> Response {
    let cliente = cliente.to_ascii_uppercase();
    if !DESTINOS.contains(&cliente.as_str()) {
        return erro(&uri, StatusCode::BAD_REQUEST);
    }
    reconciliar(repo, uri, Reconciliacao::Execucoes(cliente)).await
}
async fn vedacit_resumo(
    State(repo): State<Repository>,
    uri: Uri,
    Path(id): Path<String>,
) -> Response {
    resumo(repo, uri, "VEDACIT".to_owned(), id).await
}
async fn cliente_resumo(
    State(repo): State<Repository>,
    uri: Uri,
    Path((cliente, id)): Path<(String, String)>,
) -> Response {
    resumo(repo, uri, cliente.to_ascii_uppercase(), id).await
}
async fn resumo(repo: Repository, uri: Uri, cliente: String, id: String) -> Response {
    let Ok(id) = id.parse::<i64>() else {
        return erro(&uri, StatusCode::BAD_REQUEST);
    };
    if !DESTINOS.contains(&cliente.as_str()) {
        return erro(&uri, StatusCode::BAD_REQUEST);
    }
    reconciliar(repo, uri, Reconciliacao::Resumo(cliente, id)).await
}
async fn vedacit_itens(
    State(repo): State<Repository>,
    uri: Uri,
    Path(id): Path<String>,
) -> Response {
    let q = Params::new(&uri);
    let (Ok(id), Ok(depois), Ok(tamanho)) = (
        id.parse::<i64>(),
        q.inteiro("depois", 0i64),
        q.inteiro("tamanho", 100i32),
    ) else {
        return erro(&uri, StatusCode::BAD_REQUEST);
    };
    reconciliar(repo, uri, Reconciliacao::Itens(id, depois, tamanho)).await
}

async fn exportacao(State(repo): State<Repository>, uri: Uri) -> Response {
    // O endpoint de exportação aceita filtros do painel e ignora a paginação solicitada.
    let mut serializer = form_urlencoded::Serializer::new(String::new());
    for (k, v) in form_urlencoded::parse(uri.query().unwrap_or_default().as_bytes()) {
        if k != "pagina" && k != "tamanho" {
            serializer.append_pair(&k, &v);
        }
    }
    let Ok(filtros) = Filtros::da_query(&serializer.finish()) else {
        return erro(&uri, StatusCode::BAD_REQUEST);
    };
    csv::resposta(
        "integracoes.csv",
        &[
            "ID",
            "Sistema destino",
            "Ocorrencia",
            "Frete",
            "Chave NF-e",
            "NF",
            "Serie",
            "Status XML",
            "Status comprovante",
            "Mensagem XML",
            "Mensagem comprovante",
            "Data processamento",
            "Data XML",
            "Data comprovante",
            "Canhoto disponivel",
        ],
        move |emitir| {
            repo.exportar_pendencias(&filtros, |r| {
                emitir(vec![
                    r.id.to_string(),
                    r.sistema_destino,
                    r.occurrence_id.map(|v| v.to_string()).unwrap_or_default(),
                    r.freight_id.map(|v| v.to_string()).unwrap_or_default(),
                    r.chave_nfe.unwrap_or_default(),
                    r.numero_nf.map(|v| v.to_string()).unwrap_or_default(),
                    r.serie_nf.unwrap_or_default(),
                    r.status_dados.unwrap_or_default(),
                    r.status_canhoto.unwrap_or_default(),
                    r.mensagem_erro_dados.unwrap_or_default(),
                    r.mensagem_erro_canhoto.unwrap_or_default(),
                    csv::data_local(r.data_processamento.as_deref()),
                    csv::data_local(r.data_processamento_dados.as_deref()),
                    csv::data_local(r.data_processamento_canhoto.as_deref()),
                    r.possui_imagem_payload.to_string(),
                ])
            })
        },
    )
}
