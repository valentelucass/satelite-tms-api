use super::Repository;
use rusqlite::OptionalExtension;

#[cfg(test)]
#[test]
#[ignore = "requer SATELITE_TEST_PROPERTIES e uma conexão SQL Server"]
fn comprovante_ppg_sintetico_no_sql_server() {
    use crate::{config::Settings, repositories::SqlServer, services::imagem::extrair_imagem};
    let file = std::env::var("SATELITE_TEST_PROPERTIES").unwrap();
    let server =
        SqlServer::new(&Settings::from_properties(std::path::Path::new(&file)).unwrap()).unwrap();
    let image = format!("data:image/jpeg;base64,{}", "QUJD".repeat(4_000));
    let payload = serde_json::json!({"foto": image}).to_string();
    let value = server.json("SET NOCOUNT ON;
        DECLARE @amostra table(id bigint, sistema_destino varchar(8), request_payload nvarchar(max));
        INSERT INTO @amostra VALUES(123, 'PPG', CAST(? AS nvarchar(max)));
        SELECT COALESCE(sistema_destino,'') AS destino, request_payload AS payload
        FROM @amostra WHERE id = CAST(? AS bigint) AND sistema_destino='PPG'
        FOR JSON PATH, INCLUDE_NULL_VALUES", &[payload, "123".into()]).unwrap();
    assert_eq!(value[0]["destino"], "PPG");
    assert!(
        extrair_imagem(value[0]["payload"].as_str().unwrap()).as_deref() == Some(image.as_str())
    );
}

impl Repository {
    pub fn get_image_row(&self, id: i64) -> Result<Option<(String, Option<String>)>, String> {
        if let Some(server) = self.sql_server() {
            #[derive(serde::Deserialize)]
            struct Imagem {
                destino: String,
                payload: Option<String>,
            }
            let value = server.json(
                "SELECT COALESCE(sistema_destino, '') AS destino, request_payload AS payload
                FROM dbo.tb_log_integracao WHERE id = CAST(? AS bigint) AND sistema_destino = 'PPG'
                FOR JSON PATH, INCLUDE_NULL_VALUES",
                &[id.to_string()],
            )?;
            let rows: Vec<Imagem> =
                serde_json::from_value(value).map_err(|_| "Resposta de imagem inválida")?;
            return Ok(rows.into_iter().next().map(|r| (r.destino, r.payload)));
        }
        let db = self
            .connection()
            .map_err(|_| "Banco da bancada inacessível")?;
        db.query_row(
            "SELECT COALESCE(sistema_destino, ''), request_payload FROM tb_log_integracao WHERE id = ?1",
            [id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
        .map_err(|_| "Falha na consulta da imagem".to_owned())
    }
}
