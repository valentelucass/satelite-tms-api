use crate::config::Settings;
use odbc_api::{environment, Connection, ConnectionOptions, Cursor, IntoParameter};
use r2d2::{ManageConnection, Pool};
use serde_json::Value;
use std::{io, time::Duration};

const DATABASE: &str = "SATELITE_TMS_AUDITORIA";
const QUERY_ERROR: &str = "Falha ao consultar a auditoria do Satélite";

// Nunca inclua a string de conexão ou a mensagem completa do driver nos logs.
struct Driver(String);
struct Session {
    connection: Connection<'static>,
    discard: bool,
}

impl ManageConnection for Driver {
    type Connection = Session;
    type Error = io::Error;
    fn connect(&self) -> io::Result<Session> {
        let env = environment().map_err(|_| io::Error::other("ODBC indisponível"))?;
        let connection = env
            .connect_with_connection_string(
                &self.0,
                ConnectionOptions {
                    login_timeout_sec: Some(8),
                    ..Default::default()
                },
            )
            .map_err(|_| io::Error::other("Conexão com a auditoria indisponível"))?;
        Ok(Session {
            connection,
            discard: false,
        })
    }
    fn is_valid(&self, session: &mut Session) -> io::Result<()> {
        if self.has_broken(session) {
            return Err(io::Error::other("Conexão com a auditoria encerrada"));
        }
        Ok(())
    }
    fn has_broken(&self, session: &mut Session) -> bool {
        session.discard || session.connection.is_dead().unwrap_or(true)
    }
}

#[derive(Clone)]
pub struct SqlServer(Pool<Driver>);

fn attr(value: &str) -> String {
    format!("{{{}}}", value.replace('}', "}}"))
}

fn connection_string(url: &str, user: &str, password: &str) -> Result<String, String> {
    let error = "URL SQL Server do Satélite inválida ou não suportada";
    let mut parts = url
        .strip_prefix("jdbc:sqlserver://")
        .ok_or(error)?
        .split(';');
    let endpoint = parts.next().ok_or(error)?;
    let (host, port) = endpoint.rsplit_once(':').unwrap_or((endpoint, "1433"));
    if host.is_empty()
        || !host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b".-_".contains(&b))
    {
        return Err(error.into());
    }
    let port: u16 = port.parse().map_err(|_| error)?;
    let mut database = None;
    let mut trust = false;
    for opt in parts.filter(|s| !s.is_empty()) {
        let (key, val) = opt.split_once('=').ok_or(error)?;
        match key.to_ascii_lowercase().as_str() {
            "databasename" if database.is_none() => database = Some(val),
            "trustservercertificate" => trust = val.parse().map_err(|_| error)?,
            "encrypt" if val.eq_ignore_ascii_case("true") => (),
            _ => return Err(error.into()),
        }
    }
    if !database.is_some_and(|v| v.eq_ignore_ascii_case(DATABASE)) {
        return Err("O banco configurado precisa ser SATELITE_TMS_AUDITORIA".into());
    }
    if user.is_empty()
        || password.is_empty()
        || user.contains('\0')
        || password.contains('\0')
        || port == 0
    {
        return Err("Credenciais ou porta SQL Server inválidas".into());
    }
    Ok(format!("Driver={{ODBC Driver 18 for SQL Server}};Server=tcp:{host},{port};Database={DATABASE};Encrypt=yes;TrustServerCertificate={};UID={};PWD={};APP=SATELITE-TMS-RUST",
        if trust { "yes" } else { "no" }, attr(user), attr(password)))
}

impl SqlServer {
    pub fn new(settings: &Settings) -> Result<Self, String> {
        let get = |name| {
            settings
                .get(&format!("SATELITE_DB_{name}"))
                .or_else(|| settings.get(&format!("DB_{name}")))
                .ok_or_else(|| format!("Configuração SATELITE_DB_{name} ausente"))
        };
        let driver = Driver(connection_string(
            &get("URL")?,
            &get("USER")?,
            &get("PASSWORD")?,
        )?);
        let pool = Pool::builder()
            .max_size(5)
            .min_idle(Some(1))
            .connection_timeout(Duration::from_secs(12))
            .build(driver)
            .map_err(|_| "Não foi possível abrir a conexão SQL Server da auditoria")?;
        let server = Self(pool);
        let row = server.json(
            "SELECT DB_NAME() AS banco FOR JSON PATH, WITHOUT_ARRAY_WRAPPER",
            &[],
        )?;
        if !row["banco"]
            .as_str()
            .is_some_and(|s| s.eq_ignore_ascii_case(DATABASE))
        {
            return Err("A conexão SQL Server não apontou para a auditoria do Satélite".into());
        }
        Ok(server)
    }

    fn read_rows(
        &self,
        sql: &str,
        params: &[String],
        mut consume: impl FnMut(&[u16]) -> Result<(), String>,
    ) -> Result<(), String> {
        let mut session = self
            .0
            .get()
            .map_err(|_| "Conexões SQL Server ocupadas ou indisponíveis")?;
        let bindings: Vec<_> = params.iter().map(|p| p.as_str().into_parameter()).collect();
        session.discard = true;
        {
            let mut cursor = session
                .connection
                .execute(sql, bindings.as_slice(), Some(90))
                .map_err(query_error)?
                .ok_or(QUERY_ERROR)?;
            let mut data = Vec::new();
            while let Some(mut row) = cursor.next_row().map_err(query_error)? {
                if row.get_wide_text(1, &mut data).map_err(query_error)? {
                    consume(&data)?;
                }
            }
        }
        session.discard = false;
        Ok(())
    }

    pub fn json(&self, sql: &str, params: &[String]) -> Result<Value, String> {
        // FOR JSON pode dividir um único documento entre várias linhas, inclusive entre surrogates.
        let mut text = Vec::new();
        self.read_rows(sql, params, |chunk| {
            text.extend_from_slice(chunk);
            Ok(())
        })?;
        if text.is_empty() {
            return Ok(Value::Array(vec![]));
        }
        parse_json(&text)
    }

    pub fn rows_json(
        &self,
        sql: &str,
        params: &[String],
        mut consume: impl FnMut(Value) -> Result<(), String>,
    ) -> Result<(), String> {
        self.read_rows(sql, params, |chunk| consume(parse_json(chunk)?))
    }
}

fn parse_json(text: &[u16]) -> Result<Value, String> {
    let json = String::from_utf16(text).map_err(|_| QUERY_ERROR)?;
    serde_json::from_str(&json).map_err(|_| QUERY_ERROR.to_owned())
}

fn query_error(error: odbc_api::Error) -> String {
    // Código numérico e SQLSTATE auxiliam a operação sem expor dados dos comandos.
    if let odbc_api::Error::Diagnostics { record, .. } = error {
        eprintln!(
            "Falha SQL Server: estado {}, código {}",
            record.state.as_str(),
            record.native_error
        );
    }
    QUERY_ERROR.into()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requer SATELITE_TEST_PROPERTIES e uma conexão SQL Server"]
    fn valor_unicode_longo_e_json_dividido_em_linhas_preservam_o_conteudo() {
        let file = std::env::var("SATELITE_TEST_PROPERTIES").unwrap();
        let settings = Settings::from_properties(std::path::Path::new(&file)).unwrap();
        let db = SqlServer::new(&settings).unwrap();
        let text = "Ação 🚚 / ".repeat(30_000);
        let json = db
            .json(
                "SELECT CAST(? AS nvarchar(max)) AS texto FOR JSON PATH, WITHOUT_ARRAY_WRAPPER",
                std::slice::from_ref(&text),
            )
            .unwrap();
        assert!(json["texto"].as_str() == Some(text.as_str()));
    }
    #[test]
    fn restringe_banco_criptografia_e_isola_credenciais_odbc() {
        let url =
            "jdbc:sqlserver://localhost:1433;databaseName=SATELITE_TMS_AUDITORIA;encrypt=true";
        let s = connection_string(url, "u", "p};Teste=x").unwrap();
        assert!(s.contains("PWD={p}};Teste=x}"));
        assert!(s.contains("TrustServerCertificate=no"));
        assert!(connection_string(&url.replace(DATABASE, "outro"), "u", "p").is_err());
        assert!(
            connection_string(&url.replace("encrypt=true", "encrypt=false"), "u", "p").is_err()
        );
        assert!(connection_string(&format!("{url};databaseName=outro"), "u", "p").is_err());
    }
}
