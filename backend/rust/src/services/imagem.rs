use regex::Regex;
use std::sync::LazyLock;

// Os formatos históricos nem sempre são JSON válido. Preservamos a prioridade
// dos campos e a normalização de barras do serviço Java que lê os canhotos PPG.
static CAMPOS: LazyLock<[Regex; 6]> = LazyLock::new(|| {
    [
        "foto",
        "imagemBase64",
        "imagem",
        "imageBase64",
        "canhotoBase64",
        "conteudoBase64",
    ]
    .map(|campo| Regex::new(&format!(r#"(?i)"{campo}"\s*:\s*"([^"]+)""#)).expect("campo fixo"))
});

pub fn extrair_imagem(payload: &str) -> Option<String> {
    let payload = payload.trim();
    if payload.starts_with("data:image/") {
        return Some(payload.to_owned());
    }
    for campo in CAMPOS.iter() {
        for captura in campo.captures_iter(payload) {
            let valor = captura[1].trim().replace("\\/", "/");
            if !valor.is_empty() && !valor.eq_ignore_ascii_case("null") {
                return Some(valor);
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::extrair_imagem;

    #[test]
    fn seleciona_campo_na_ordem_do_java_e_aceita_formatos_historicos() {
        let texto = r#"registro "imagem":"segunda", "FOTO":"null", "foto":" data:image\/jpeg;base64,abc " "#;
        assert_eq!(
            extrair_imagem(texto).as_deref(),
            Some("data:image/jpeg;base64,abc")
        );
        assert_eq!(
            extrair_imagem(" data:image/png;base64,abc ").as_deref(),
            Some("data:image/png;base64,abc")
        );
        assert_eq!(extrair_imagem(r#"{"outra":"ignorar"}"#), None);
    }
}
