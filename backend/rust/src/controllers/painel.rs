use crate::models::painel::ErroPainel;
use crate::repositories::Repository;
use crate::services::painel::{consultar, Filtros};
use axum::{
    extract::{RawQuery, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use jiff::{tz::TimeZone, Timestamp};

const ROTA: &str = "/api/auditoria/integracoes-clientes";

fn erro_http(status: StatusCode) -> Response {
    let erro = ErroPainel {
        timestamp: Timestamp::now()
            .to_zoned(TimeZone::UTC)
            .strftime("%Y-%m-%dT%H:%M:%S%.3fZ")
            .to_string(),
        status: status.as_u16(),
        error: status.canonical_reason().unwrap_or("Error"),
        path: ROTA,
    };
    (status, Json(erro)).into_response()
}

pub fn router(repo: Repository) -> Router {
    Router::new()
        .route(
            ROTA,
            get(painel).options(|| async { [(axum::http::header::ALLOW, "GET,HEAD,OPTIONS")] }),
        )
        .with_state(repo)
}

async fn painel(State(repo): State<Repository>, RawQuery(query): RawQuery) -> Response {
    let filtros = match Filtros::da_query(query.as_deref().unwrap_or_default()) {
        Ok(v) => v,
        Err(_) => return erro_http(StatusCode::BAD_REQUEST),
    };
    match tokio::task::spawn_blocking(move || consultar(&repo, &filtros)).await {
        Ok(Ok(resultado)) => Json(resultado).into_response(),
        _ => erro_http(StatusCode::INTERNAL_SERVER_ERROR),
    }
}
