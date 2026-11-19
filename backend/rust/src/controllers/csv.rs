use axum::body::{Body, Bytes};
use axum::http::header::{CONTENT_DISPOSITION, CONTENT_TYPE};
use axum::response::{IntoResponse, Response};
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;

fn linha<'a>(campos: impl IntoIterator<Item = &'a str>) -> Bytes {
    let mut linha = String::new();
    for (indice, valor) in campos.into_iter().enumerate() {
        if indice > 0 {
            linha.push(';');
        }
        linha.push('"');
        if valor.starts_with(['=', '+', '-', '@']) {
            linha.push('\'');
        }
        linha.push_str(&valor.replace('"', "\"\""));
        linha.push('"');
    }
    linha.push_str("\r\n");
    Bytes::from(linha)
}

/// A leitura do cursor acompanha o ritmo do download e termina se o cliente desconectar.
pub fn resposta<F>(
    arquivo: &'static str,
    cabecalho: &'static [&'static str],
    produzir: F,
) -> Response
where
    F: FnOnce(&mut dyn FnMut(Vec<String>) -> Result<(), String>) -> Result<(), String>
        + Send
        + 'static,
{
    let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(16);
    tokio::task::spawn_blocking(move || {
        if tx
            .blocking_send(Ok(linha(cabecalho.iter().copied())))
            .is_err()
        {
            return;
        }
        let resultado = produzir(&mut |valores| {
            tx.blocking_send(Ok(linha(valores.iter().map(String::as_str))))
                .map_err(|_| "Download encerrado".to_owned())
        });
        if resultado.is_err() {
            let _ = tx.blocking_send(Err(std::io::Error::other("Consulta interrompida")));
        }
    });
    let disposition = format!("attachment; filename=\"{arquivo}\"; filename*=UTF-8''{arquivo}");
    (
        [
            (CONTENT_TYPE, "text/csv;charset=UTF-8".to_owned()),
            (CONTENT_DISPOSITION, disposition),
        ],
        Body::from_stream(ReceiverStream::new(rx)),
    )
        .into_response()
}

/// LocalDateTime.toString do Java reduz os segundos e usa grupos de três casas decimais no CSV.
pub fn data_local(data: Option<&str>) -> String {
    let Some(data) = data else {
        return String::new();
    };
    let (base, fracao) = data.split_once('.').unwrap_or((data, ""));
    let fracao = fracao.trim_end_matches('0');
    if fracao.is_empty() {
        if base.len() == 19 && base.ends_with(":00") {
            return base[..16].to_owned();
        }
        return base.to_owned();
    }
    let casas = fracao.len().div_ceil(3) * 3;
    format!("{base}.{fracao:0<casas$}")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn csv_excel_preserva_aspas_e_neutraliza_formulas() {
        assert_eq!(
            linha(["=2+2", "dois;\"três", ""]),
            Bytes::from_static(b"\"'=2+2\";\"dois;\"\"tr\xc3\xaas\";\"\"\r\n")
        );
        assert_eq!(
            data_local(Some("2026-11-18T09:30:00.000")),
            "2026-11-18T09:30"
        );
        assert_eq!(
            data_local(Some("2026-11-18T09:30:00.1200000")),
            "2026-11-18T09:30:00.120"
        );
    }
}
