use super::Repository;
use rusqlite::OptionalExtension;

impl Repository {
    pub fn get_image_row(&self, id: i64) -> Result<Option<(String, Option<String>)>, String> {
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
