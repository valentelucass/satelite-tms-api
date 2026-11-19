//! Acesso ao arquivo SQLite exclusivo da bancada.
mod imagem;
pub mod painel;
pub mod selia;

use rusqlite::{Connection, OpenFlags};
use std::path::{Path, PathBuf};
use std::time::Duration;

#[derive(Clone)]
pub struct Repository {
    file: PathBuf,
}

impl Repository {
    pub fn open(path: &Path) -> Result<Self, String> {
        let file = path
            .canonicalize()
            .map_err(|_| "Banco da bancada ausente")?;
        let repository = Self { file };
        let db = repository
            .connection()
            .map_err(|_| "Banco da bancada inacessível")?;
        db.prepare("SELECT id, intelipost_pre_shipment_list, request_payload FROM tb_log_integracao LIMIT 0")
            .map_err(|_| "Tabela da bancada ausente")?;
        Ok(repository)
    }

    fn connection(&self) -> rusqlite::Result<Connection> {
        let connection =
            Connection::open_with_flags(&self.file, OpenFlags::SQLITE_OPEN_READ_WRITE)?;
        connection.busy_timeout(Duration::from_secs(12))?;
        Ok(connection)
    }
}
