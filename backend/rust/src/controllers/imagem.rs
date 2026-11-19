use axum::{
    extract::{rejection::PathRejection, Path, State},
    http::{header, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::get,
    Router,
};
use tokio::task;

use crate::repositories::Repository;
use crate::services::imagem::extrair_imagem;

pub fn router(repo: Repository) -> Router {
    Router::new()
        .route(
            "/api/auditoria/logs/{id}/imagem",
            get(consultar).options(|| async { [(header::ALLOW, "GET,HEAD,OPTIONS")] }),
        )
        .with_state(repo)
}

async fn consultar(
    State(repo): State<Repository>,
    uri: Uri,
    id: Result<Path<i64>, PathRejection>,
) -> Response {
    let Ok(Path(id)) = id else {
        return (
            StatusCode::BAD_REQUEST,
            axum::Json(serde_json::json!({
                "timestamp": jiff::Timestamp::now().strftime("%Y-%m-%dT%H:%M:%S%.3fZ").to_string(),
                "status":400,"error":"Bad Request","path":uri.path()
            })),
        )
            .into_response();
    };
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
