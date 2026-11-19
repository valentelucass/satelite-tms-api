use axum::{
    extract::{Path, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use tokio::task;

use crate::repositories::Repository;
use crate::services::imagem::extrair_imagem;

pub fn router(repo: Repository) -> Router {
    Router::new()
        .route("/api/auditoria/logs/{id}/imagem", get(consultar))
        .with_state(repo)
}

async fn consultar(State(repo): State<Repository>, Path(id): Path<i64>) -> Response {
    let row = match task::spawn_blocking(move || repo.get_image_row(id)).await {
        Ok(Ok(row)) => row,
        _ => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if let Some((destino, Some(payload))) = row {
        if destino.trim().eq_ignore_ascii_case("PPG") {
            if let Some(imagem) = extrair_imagem(&payload) {
                return ([(header::CONTENT_TYPE, "text/plain")], imagem).into_response();
            }
        }
    }
    StatusCode::NOT_FOUND.into_response()
}
