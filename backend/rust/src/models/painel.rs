use serde::Serialize;

/// Envelope de erro HTTP do painel, seguindo o contrato do Spring Boot.
#[derive(Serialize)]
pub struct ErroPainel {
    pub timestamp: String,
    pub status: u16,
    pub error: &'static str,
    pub path: &'static str,
}

/// Nomes e tipos públicos dos records de dto.auditoria usados pelo Dashboard.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditoriaIntegracoes {
    pub gerado_em: String,
    pub metricas_consolidadas: Vec<Metrica>,
    pub pendencias: Pendencias,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Metrica {
    pub sistema_destino: String,
    pub total_registros: i64,
    pub percentual_xml_sucesso: f64,
    pub percentual_canhoto_sucesso: f64,
    pub rotulo_dados: &'static str,
    pub rotulo_comprovante: &'static str,
}

#[derive(Serialize)]
pub struct Pendencias {
    pub itens: Vec<Pendencia>,
    pub paginacao: Paginacao,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Paginacao {
    pub pagina: i32,
    pub tamanho: i32,
    pub total_elementos: i64,
    pub total_paginas: i64,
    pub primeira_pagina: bool,
    pub ultima_pagina: bool,
}

#[derive(Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Pendencia {
    pub id: i64,
    pub sistema_destino: String,
    pub occurrence_id: Option<i64>,
    pub freight_id: Option<i64>,
    pub chave_nfe: Option<String>,
    pub numero_nf: Option<i64>,
    pub serie_nf: Option<String>,
    pub status_dados: Option<String>,
    pub status_canhoto: Option<String>,
    pub mensagem_erro_dados: Option<String>,
    pub mensagem_erro_canhoto: Option<String>,
    pub canhoto_referencia: Option<String>,
    pub canhoto_mime_type: Option<String>,
    pub data_processamento: Option<String>,
    pub data_processamento_dados: Option<String>,
    pub data_processamento_canhoto: Option<String>,
    pub possui_imagem_payload: bool,
}
