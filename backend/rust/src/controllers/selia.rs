use crate::config::Settings;
use crate::dto::selia::PreShipment;
use crate::repositories::selia::ReceiveError;
use crate::repositories::Repository;
use crate::services::selia::{accepted, rejected, validate};
use axum::body::to_bytes;
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::{Json, Router};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;

const ROUTE: &str = "/api/selia/intelipost/pre-shipment-list";
const INVALID: &str = "PLP rejeitada por dados obrigatórios inválidos.";

pub struct SeliaSettings {
    key_hash: Option<[u8; 32]>,
    enabled: bool,
    max_body: usize,
    rate_enabled: bool,
    window: Duration,
    max_per_key: usize,
    max_per_ip: usize,
    max_buckets: usize,
}

impl SeliaSettings {
    pub fn from_config(config: &Settings) -> Result<Self, String> {
        let key = config
            .get("SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY")
            .unwrap_or_default();
        let key_hash = if key.trim().is_empty() {
            None
        } else {
            Some(Sha256::digest(key.trim()).into())
        };
        Ok(Self {
            key_hash,
            enabled: setting(config, "SELIA_INTELIPOST_PLP_ENABLED", false)?,
            max_body: setting(
                config,
                "SELIA_INTELIPOST_PLP_MAX_BODY_BYTES",
                5_242_880usize,
            )?
            .max(1024),
            rate_enabled: setting(config, "SELIA_INTELIPOST_PLP_RATE_LIMIT_ENABLED", true)?,
            window: Duration::from_millis(
                setting(
                    config,
                    "SELIA_INTELIPOST_PLP_RATE_LIMIT_WINDOW_MS",
                    60_000u64,
                )?
                .max(1000),
            ),
            max_per_key: setting(
                config,
                "SELIA_INTELIPOST_PLP_RATE_LIMIT_MAX_REQUESTS_PER_KEY",
                60usize,
            )?
            .max(1),
            max_per_ip: setting(
                config,
                "SELIA_INTELIPOST_PLP_RATE_LIMIT_MAX_REQUESTS_PER_IP",
                120usize,
            )?
            .max(1),
            max_buckets: setting(
                config,
                "SELIA_INTELIPOST_PLP_RATE_LIMIT_MAX_BUCKETS",
                10_000usize,
            )?
            .max(100),
        })
    }

    fn authenticates(&self, received: &str) -> bool {
        let received_hash: [u8; 32] = Sha256::digest(received.trim()).into();
        self.key_hash
            .is_some_and(|expected| bool::from(expected.ct_eq(&received_hash)))
    }
}

fn setting<T: FromStr>(config: &Settings, name: &str, default: T) -> Result<T, String> {
    match config.get(name) {
        Some(value) => value
            .trim()
            .parse()
            .map_err(|_| format!("Configuração inválida: {name}")),
        None => Ok(default),
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Bucket {
    AuthenticatedKey,
    Address([u8; 32]),
}

struct RateLimiter {
    counters: HashMap<Bucket, (Instant, usize)>,
    last_cleanup: Instant,
}

impl RateLimiter {
    fn new() -> Self {
        Self {
            counters: HashMap::new(),
            last_cleanup: Instant::now(),
        }
    }

    fn allows(
        &mut self,
        settings: &SeliaSettings,
        address: IpAddr,
        authenticated: bool,
        now: Instant,
    ) -> bool {
        if !settings.rate_enabled {
            return true;
        }
        if now.duration_since(self.last_cleanup) >= settings.window {
            self.counters
                .retain(|_, (start, _)| now.duration_since(*start) < settings.window);
            self.last_cleanup = now;
        }
        let ip = Bucket::Address(Sha256::digest(address.to_string().as_bytes()).into());
        let buckets = [
            (ip, settings.max_per_ip),
            (Bucket::AuthenticatedKey, settings.max_per_key),
        ];
        let required = if authenticated {
            &buckets[..]
        } else {
            &buckets[..1]
        };
        let missing = required
            .iter()
            .filter(|(id, _)| !self.counters.contains_key(id))
            .count();
        if self.counters.len() + missing > settings.max_buckets {
            return false;
        }
        for (id, limit) in required {
            if let Some((start, count)) = self.counters.get(id) {
                if now.duration_since(*start) < settings.window && *count >= *limit {
                    return false;
                }
            }
        }
        for (id, _) in required {
            let count = self.counters.entry(*id).or_insert((now, 0));
            if now.duration_since(count.0) >= settings.window {
                *count = (now, 0);
            }
            count.1 += 1;
        }
        true
    }
}

struct SeliaState {
    repo: Repository,
    settings: SeliaSettings,
    limiter: Mutex<RateLimiter>,
}

pub fn selia_router(repo: Repository, settings: SeliaSettings) -> Router {
    let state = Arc::new(SeliaState {
        repo,
        settings,
        limiter: Mutex::new(RateLimiter::new()),
    });
    Router::new()
        .route(
            ROUTE,
            post(receive).options(|| async { ([(header::ALLOW, "POST,OPTIONS")], "") }),
        )
        .with_state(state)
}

fn problem(status: StatusCode, id: Option<i64>, message: &'static str) -> Response {
    (status, Json(rejected(id, message))).into_response()
}

fn limit_response(status: StatusCode, text: &'static str, code: &'static str) -> Response {
    (
        status,
        Json(json!({"status":"ERROR","messages":[{"type":"ERROR","text":text,"code":code}]})),
    )
        .into_response()
}

fn over_size() -> Response {
    limit_response(
        StatusCode::PAYLOAD_TOO_LARGE,
        "Corpo da requisicao excede o limite permitido.",
        "selia.plp.payload_too_large",
    )
}

async fn receive(
    State(state): State<Arc<SeliaState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    request: Request,
) -> Response {
    let (parts, body) = request.into_parts();
    let settings = &state.settings;
    let declared_length = parts
        .headers
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok());
    if declared_length.is_some_and(|length| length > settings.max_body as u64) {
        return over_size();
    }
    let Ok(body) = to_bytes(body, settings.max_body).await else {
        return over_size();
    };
    let key = parts
        .headers
        .get("logistic-provider-api-key")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    let authenticated = settings.authenticates(key);
    let allowed = state
        .limiter
        .lock()
        .ok()
        .map(|mut limiter| limiter.allows(settings, peer.ip(), authenticated, Instant::now()));
    match allowed {
        Some(true) => {}
        Some(false) => {
            return limit_response(
                StatusCode::TOO_MANY_REQUESTS,
                "Limite temporario de requisicoes excedido.",
                "selia.plp.rate_limit",
            )
        }
        None => {
            return problem(
                StatusCode::SERVICE_UNAVAILABLE,
                None,
                "Serviço temporariamente indisponível.",
            )
        }
    }
    if !body.is_empty() {
        let media_type = parts
            .headers
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        if media_type != "application/json"
            && !(media_type.starts_with("application/") && media_type.ends_with("+json"))
        {
            return problem(
                StatusCode::UNSUPPORTED_MEDIA_TYPE,
                None,
                "Tipo de conteúdo inválido.",
            );
        }
    }
    let input: Option<PreShipment> = if body.is_empty() {
        None
    } else {
        match serde_json::from_slice(&body) {
            Ok(input) => input,
            Err(_) => return problem(StatusCode::BAD_REQUEST, None, INVALID),
        }
    };
    let id = input
        .as_ref()
        .and_then(|input| input.intelipost_pre_shipment_list);
    if settings.key_hash.is_none() {
        return problem(
            StatusCode::SERVICE_UNAVAILABLE,
            id,
            "Autenticação de PLP não configurada.",
        );
    }
    if !authenticated {
        return problem(StatusCode::UNAUTHORIZED, id, "Não autorizado.");
    }
    if !settings.enabled {
        return problem(
            StatusCode::SERVICE_UNAVAILABLE,
            id,
            "Recebimento de PLP indisponível para homologação.",
        );
    }
    let Some(list) = input.and_then(validate) else {
        return problem(StatusCode::BAD_REQUEST, id, INVALID);
    };
    let repo = state.repo.clone();
    let saved =
        tokio::task::spawn_blocking(move || repo.receive(list.id, &list.correlations)).await;
    match saved {
        Ok(Ok(receipt)) => Json(accepted(list.id, list.orders, receipt)).into_response(),
        Ok(Err(ReceiveError::Conflict)) => problem(
            StatusCode::CONFLICT,
            id,
            "A lista já foi recebida com associações diferentes.",
        ),
        Ok(Err(ReceiveError::MissingProviderId)) => problem(
            StatusCode::INTERNAL_SERVER_ERROR,
            id,
            "Não foi possível gerar a lista técnica da transportadora.",
        ),
        _ => problem(
            StatusCode::INTERNAL_SERVER_ERROR,
            id,
            "Não foi possível registrar a lista.",
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limites_por_ip_e_chave_tem_janelas_independentes() {
        let settings = SeliaSettings {
            key_hash: None,
            enabled: true,
            max_body: 1024,
            rate_enabled: true,
            window: Duration::from_secs(5),
            max_per_key: 1,
            max_per_ip: 2,
            max_buckets: 100,
        };
        let mut limiter = RateLimiter::new();
        let first = "127.0.0.1".parse().unwrap();
        let second = "127.0.0.2".parse().unwrap();
        let now = Instant::now();
        // Uma tentativa sem chave conta apenas no IP e deixa a cota da chave intacta.
        assert!(limiter.allows(&settings, first, false, now));
        assert!(limiter.allows(&settings, first, true, now));
        assert!(!limiter.allows(&settings, first, false, now));
        assert!(!limiter.allows(&settings, second, true, now));
        assert!(limiter.allows(&settings, second, false, now));
        assert!(limiter.allows(&settings, first, true, now + settings.window));
    }
}
