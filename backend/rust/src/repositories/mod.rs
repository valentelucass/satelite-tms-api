pub mod consultas;
mod imagem;
pub mod painel;
pub mod quarentena;
pub mod selia;
mod sql_server;
pub use sql_server::SqlServer;

use rusqlite::{Connection, OpenFlags};
use std::path::{Path, PathBuf};
use std::time::Duration;

#[derive(Clone)]
pub enum Repository {
    Sqlite(PathBuf),
    SqlServer(SqlServer),
}

impl Repository {
    pub fn open(path: &Path) -> Result<Self, String> {
        let file = path
            .canonicalize()
            .map_err(|_| "Banco da bancada ausente")?;
        let repository = Self::Sqlite(file);
        let db = repository
            .connection()
            .map_err(|_| "Banco da bancada inacessível")?;
        db.prepare("SELECT id, intelipost_pre_shipment_list, request_payload FROM tb_log_integracao LIMIT 0")
            .map_err(|_| "Tabela da bancada ausente")?;
        Ok(repository)
    }

    pub fn open_sql_server(config: &crate::config::Settings) -> Result<Self, String> {
        Ok(Self::SqlServer(SqlServer::new(config)?))
    }

    pub fn sql_server(&self) -> Option<&SqlServer> {
        match self {
            Self::SqlServer(server) => Some(server),
            Self::Sqlite(_) => None,
        }
    }

    fn connection(&self) -> rusqlite::Result<Connection> {
        let Self::Sqlite(file) = self else {
            return Err(rusqlite::Error::InvalidQuery);
        };
        let connection = Connection::open_with_flags(file, OpenFlags::SQLITE_OPEN_READ_WRITE)?;
        connection.busy_timeout(Duration::from_secs(12))?;
        Ok(connection)
    }
}
