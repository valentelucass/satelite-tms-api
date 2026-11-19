use super::csv;
use crate::repositories::{
    quarentena::{Consulta, Lista},
    Repository,
};
use crate::services::painel::DESTINOS;
use axum::{
    extract::{RawQuery, State},
    http::{header, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use jiff::{civil::Date, Timestamp, ToSpan};
use serde_json::json;

fn opcoes_get() -> impl IntoResponse {
    ([(header::ALLOW, "GET,HEAD,OPTIONS")], "")
}

pub fn router(repo: Repository) -> Router {
    Router::new()
        .route(
            "/api/etl/quarentena/erros",
            get(consultar).options(|| async { opcoes_get() }),
        )
        .route(
            "/api/etl/quarentena/historico",
            get(consultar).options(|| async { opcoes_get() }),
        )
        .route(
            "/api/etl/quarentena/erros/exportacao",
            get(consultar).options(|| async { opcoes_get() }),
        )
        .route(
            "/api/etl/quarentena/historico/exportacao",
            get(consultar).options(|| async { opcoes_get() }),
        )
        .route(
            "/api/etl/quarentena/{destino}/reprocessar",
            post(bloquear).options(|| async { ([(header::ALLOW, "POST,OPTIONS")], "") }),
        )
        .with_state(repo)
}

fn erro(status: StatusCode, path: &str) -> Response {
    (
        status,
        Json(
            json!({"status":status.as_u16(),"error":status.canonical_reason(),"path":path,
        "timestamp":Timestamp::now().strftime("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()}),
        ),
    )
        .into_response()
}

async fn bloquear(uri: Uri) -> Response {
    erro(StatusCode::FORBIDDEN, uri.path())
}

fn parametros(query: &str, lista: Lista, exportar: bool) -> Result<Consulta, ()> {
    let params = form_urlencoded::parse(query.as_bytes()).collect::<Vec<_>>();
    let valor = |nome: &str| {
        params
            .iter()
            .find(|(k, _)| k == nome)
            .map(|(_, v)| v.trim())
            .filter(|v| !v.is_empty())
    };
    let inteiro = |k, default| match valor(k) {
        Some(v) => v.parse::<i32>().map_err(|_| ()),
        None => Ok(default),
    };
    let (pagina, tamanho) = if exportar {
        (0, 100)
    } else {
        (
            inteiro("pagina", 0)?.max(0),
            inteiro("tamanho", 100)?.clamp(1, 500),
        )
    };
    let mut destinos = Vec::new();
    let recebidos = params
        .iter()
        .filter(|(k, _)| k == "destino")
        .map(|(_, v)| v.as_ref())
        .collect::<Vec<_>>();
    let recebidos = match recebidos.as_slice() {
        [s] => s.split(',').collect::<Vec<_>>(),
        _ => recebidos,
    };
    for s in recebidos {
        let destino = s.trim().to_ascii_uppercase();
        if destino.is_empty() {
            continue;
        }
        if !DESTINOS.contains(&destino.as_str()) {
            return Err(());
        }
        if !destinos.contains(&destino) {
            destinos.push(destino);
        }
    }
    if destinos.is_empty() {
        destinos = DESTINOS.iter().map(|s| s.to_string()).collect();
    }
    let periodo = if matches!(lista, Lista::Historico) {
        let data = |k| {
            valor(k)
                .map(|s| s.parse::<Date>().map_err(|_| ()))
                .transpose()
        };
        let fim = data("dataFinal")?.unwrap_or(
            Timestamp::now()
                .in_tz("America/Sao_Paulo")
                .map_err(|_| ())?
                .date(),
        );
        let inicio = match data("dataInicial")? {
            Some(d) => d,
            None => fim.checked_sub(89.days()).map_err(|_| ())?,
        };
        if inicio > fim {
            return Err(());
        }
        let exclusivo = fim.tomorrow().map_err(|_| ())?;
        // A consulta nativa do histórico recebe os limites que o Hibernate vincula em UTC.
        let utc = crate::services::painel::limite_metricas_utc;
        Some((
            utc(inicio).map_err(|_| ())?,
            utc(exclusivo).map_err(|_| ())?,
        ))
    } else {
        None
    };
    Ok(Consulta {
        lista,
        destinos,
        periodo,
        pagina,
        tamanho,
    })
}

async fn consultar(State(repo): State<Repository>, uri: Uri, RawQuery(raw): RawQuery) -> Response {
    let path = uri.path();
    let lista = if path.contains("/historico") {
        Lista::Historico
    } else {
        Lista::Erros
    };
    let exportar = path.ends_with("/exportacao");
    let q = match parametros(raw.as_deref().unwrap_or(""), lista, exportar) {
        Ok(q) => q,
        Err(()) => return erro(StatusCode::BAD_REQUEST, path),
    };
    if exportar {
        let (arquivo, cabecalho): (&str, &[&str]) = match lista {
            Lista::Historico => (
                "historico-repescagens-integracoes.csv",
                &[
                    "Destino",
                    "NF",
                    "Etapa",
                    "Entrou em quarentena",
                    "Repescagem",
                    "Resultado",
                    "Motivo",
                ],
            ),
            Lista::Erros => (
                "quarentena-integracoes.csv",
                &[
                    "Destino",
                    "NF",
                    "Acao necessaria",
                    "Chave NF-e",
                    "Tentativas",
                    "Ultima tentativa",
                ],
            ),
        };
        csv::resposta(arquivo, cabecalho, move |emitir| q.exportar(&repo, emitir))
    } else {
        match tokio::task::spawn_blocking(move || q.pagina(&repo)).await {
            Ok(Ok(p)) => Json(p).into_response(),
            _ => erro(StatusCode::INTERNAL_SERVER_ERROR, path),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn normaliza_paginacao_e_destinos() {
        let q = parametros(
            "pagina=-1&tamanho=800&destino=ppg,Selia",
            Lista::Erros,
            false,
        )
        .unwrap();
        assert_eq!((q.pagina, q.tamanho), (0, 500));
        assert_eq!(q.destinos, ["PPG", "SELIA"]);
        assert!(parametros("destino=invalido", Lista::Erros, false).is_err());
        assert!(parametros("pagina=abc", Lista::Erros, false).is_err());
    }
    #[test]
    fn intervalo_historico_inclusivo() {
        let q = parametros(
            "dataInicial=2026-01-12&dataFinal=2026-02-28",
            Lista::Historico,
            true,
        )
        .unwrap();
        assert_eq!(
            q.periodo,
            Some(("2026-01-12T03:00:00".into(), "2026-03-01T03:00:00".into()))
        );
        assert!(parametros(
            "dataInicial=2026-02-02&dataFinal=2026-02-01",
            Lista::Historico,
            false
        )
        .is_err());
    }
}
