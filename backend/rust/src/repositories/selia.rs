use super::Repository;
use crate::models::selia::{Correlation, Receipt};
use jiff::{civil::DateTime, tz::TimeZone, Timestamp};
use rusqlite::{params, OptionalExtension, TransactionBehavior};
use std::collections::BTreeSet;

const RECEIVE_SQL_SERVER: &str = concat!(
    "DECLARE @lista bigint = CONVERT(bigint, ?), @mapas nvarchar(max) = CONVERT(nvarchar(max), ?), ",
    "@recibo bigint, @data datetime2(7);\n",
    include_str!("selia_receber.sql"),
    "\nSELECT @recibo AS id, CONVERT(varchar(32), @data, 121) AS data ",
    "FOR JSON PATH, WITHOUT_ARRAY_WRAPPER;"
);

#[derive(Debug, PartialEq, Eq)]
pub enum ReceiveError {
    Conflict,
    MissingProviderId,
    Storage,
}

impl From<rusqlite::Error> for ReceiveError {
    fn from(_: rusqlite::Error) -> Self {
        Self::Storage
    }
}

impl Repository {
    pub fn receive(
        &self,
        list_id: i64,
        correlations: &[Correlation],
    ) -> Result<Receipt, ReceiveError> {
        if let Some(db) = self.sql_server() {
            // Um único lote mantém o lock, o cabeçalho e os vínculos na mesma conexão.
            let mappings =
                serde_json::to_string(correlations).map_err(|_| ReceiveError::Storage)?;
            let row = db
                .json(RECEIVE_SQL_SERVER, &[list_id.to_string(), mappings])
                .map_err(|_| ReceiveError::Storage)?;
            return Ok(Receipt {
                provider_list_id: row["id"].as_i64().ok_or(ReceiveError::MissingProviderId)?,
                created_at: receipt_date(row["data"].as_str().ok_or(ReceiveError::Storage)?)?,
            });
        }
        let proposed: BTreeSet<_> = correlations.iter().collect();
        let mut db = self.connection()?;
        // Em SQLite BEGIN IMMEDIATE serializa os escritores inclusive em processos distintos.
        // A comparação e toda a gravação ocorrem sob a mesma transação.
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let previous = tx
            .query_row(
                "SELECT logistics_provider_shipment_list, data_processamento
               FROM tb_log_integracao
              WHERE sistema_destino='SELIA_PLP' AND intelipost_pre_shipment_list=?1
              ORDER BY data_processamento, id LIMIT 1",
                [list_id],
                |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)),
            )
            .optional()?;
        let (id, db_time) = if let Some((id, db_time)) = previous {
            let mut query = tx.prepare(
                "SELECT chave_nfe, order_number, volume_number FROM tb_log_integracao
                  WHERE sistema_destino='SELIA_PLP_MAP' AND intelipost_pre_shipment_list=?1
                    AND logistics_provider_shipment_list=?2",
            )?;
            let saved = query
                .query_map(params![list_id, id], |r| {
                    Ok(Correlation {
                        invoice_key: r.get(0)?,
                        order_number: r.get(1)?,
                        volume_number: r.get(2)?,
                    })
                })?
                .collect::<rusqlite::Result<BTreeSet<_>>>()?;
            if !saved.iter().eq(proposed.iter().copied()) {
                return Err(ReceiveError::Conflict);
            }
            (id, db_time)
        } else {
            // A bancada JDBC persiste timestamps em UTC (hibernate.jdbc.time_zone).
            let now = Timestamp::now().to_zoned(TimeZone::UTC);
            let db_time = now.strftime("%Y-%m-%d %H:%M:%S%.3f").to_string();
            tx.execute(
                "INSERT INTO tb_log_integracao
                     (intelipost_pre_shipment_list,sistema_destino,status,status_dados,status_canhoto,
                      tentativas_dados,tentativas_canhoto,data_processamento,arquivado)
                 VALUES (?1,'SELIA_PLP','ACEITO_PLP','SUCESSO','NAO_APLICAVEL',0,0,?2,0)",
                params![list_id, db_time],
            )?;
            let id = tx.last_insert_rowid();
            tx.execute(
                "UPDATE tb_log_integracao SET logistics_provider_shipment_list=id WHERE id=?1",
                [id],
            )?;
            let mut insert = tx.prepare(
                "INSERT INTO tb_log_integracao
                     (intelipost_pre_shipment_list,logistics_provider_shipment_list,chave_nfe,order_number,volume_number,
                      sistema_destino,status,status_dados,status_canhoto,tentativas_dados,tentativas_canhoto,
                      data_processamento,arquivado)
                 VALUES (?1,?2,?3,?4,?5,'SELIA_PLP_MAP','ACEITO_PLP','SUCESSO','NAO_APLICAVEL',0,0,?6,0)",
            )?;
            let mut remaining = proposed;
            for mapping in correlations {
                if remaining.remove(mapping) {
                    insert.execute(params![
                        list_id,
                        id,
                        mapping.invoice_key,
                        mapping.order_number,
                        mapping.volume_number,
                        db_time
                    ])?;
                }
            }
            (id, db_time)
        };
        let receipt = Receipt {
            provider_list_id: id,
            created_at: receipt_date(&db_time)?,
        };
        tx.commit()?;
        Ok(receipt)
    }
}

fn receipt_date(stored: &str) -> Result<String, ReceiveError> {
    let instant = DateTime::strptime("%Y-%m-%d %H:%M:%S%.f", stored)
        .and_then(|utc| utc.to_zoned(TimeZone::UTC))
        .and_then(|utc| utc.in_tz("America/Sao_Paulo"))
        .map_err(|_| ReceiveError::Storage)?;
    // DateTimeFormatter.ISO_OFFSET_DATE_TIME omite zeros fracionários à direita.
    Ok(instant.strftime("%Y-%m-%dT%H:%M:%S%.f%:z").to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use std::path::PathBuf;
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Barrier,
    };
    use std::thread;

    struct Fixture {
        repository: Repository,
        path: PathBuf,
    }

    impl Fixture {
        fn new() -> Self {
            static RUN: AtomicUsize = AtomicUsize::new(0);
            let path = std::env::temp_dir().join(format!(
                "satelite-plp-{}-{}-{}.db",
                std::process::id(),
                Timestamp::now().as_microsecond(),
                RUN.fetch_add(1, Ordering::Relaxed)
            ));
            let db = Connection::open(&path).unwrap();
            db.execute_batch(include_str!("../../tests/fixtures/schema_bancada.sql"))
                .unwrap();
            drop(db);
            Self {
                repository: Repository::open(&path).unwrap(),
                path,
            }
        }

        fn row_count(&self, list: i64) -> i64 {
            self.repository
                .connection()
                .unwrap()
                .query_row(
                    "SELECT count(*) FROM tb_log_integracao WHERE intelipost_pre_shipment_list=?1",
                    [list],
                    |r| r.get(0),
                )
                .unwrap()
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.path);
        }
    }

    fn mapping(id: u32) -> Correlation {
        Correlation {
            invoice_key: format!("{:044}", id),
            order_number: format!("PED-{id}"),
            volume_number: format!("VOL-{id}"),
        }
    }

    #[test]
    fn receipt_date_converts_utc_to_business_zone_with_java_fraction_format() {
        assert_eq!(
            receipt_date("2026-11-18 16:00:01.120").unwrap(),
            "2026-11-18T13:00:01.12-03:00"
        );
        assert_eq!(
            receipt_date("2026-11-18 16:00:01.000").unwrap(),
            "2026-11-18T13:00:01-03:00"
        );
    }

    #[test]
    fn concurrent_retries_share_one_persisted_receipt() {
        let data = Fixture::new();
        let clients = 8;
        let start = Arc::new(Barrier::new(clients));
        let tasks: Vec<_> = (0..clients)
            .map(|_| {
                let repo = data.repository.clone();
                let barrier = Arc::clone(&start);
                thread::spawn(move || {
                    barrier.wait();
                    repo.receive(37, &[mapping(2), mapping(1), mapping(2)])
                        .unwrap()
                })
            })
            .collect();
        let stored: Vec<_> = tasks.into_iter().map(|task| task.join().unwrap()).collect();
        assert!(stored.iter().all(|r| r == &stored[0]));
        assert_eq!(data.row_count(37), 3);
        let reopened = Repository::open(&data.path).unwrap();
        assert_eq!(
            reopened.receive(37, &[mapping(1), mapping(2)]).unwrap(),
            stored[0]
        );
        assert_eq!(
            reopened.receive(37, &[mapping(3)]),
            Err(ReceiveError::Conflict)
        );
        assert_eq!(data.row_count(37), 3);
        let db = reopened.connection().unwrap();
        let mut stmt = db.prepare("SELECT order_number FROM tb_log_integracao WHERE sistema_destino='SELIA_PLP_MAP' ORDER BY id").unwrap();
        let original_order = stmt
            .query_map([], |r| r.get::<_, String>(0))
            .unwrap()
            .collect::<rusqlite::Result<Vec<_>>>()
            .unwrap();
        assert_eq!(original_order, ["PED-2", "PED-1"]);
    }

    #[test]
    fn rollback_on_second_mapping_and_retry_after_recovery() {
        let data = Fixture::new();
        let db = data.repository.connection().unwrap();
        db.execute_batch(
            "CREATE TRIGGER fault BEFORE INSERT ON tb_log_integracao
                  WHEN NEW.sistema_destino='SELIA_PLP_MAP' AND NEW.order_number='PED-2'
                  BEGIN SELECT RAISE(ABORT, 'fault'); END;",
        )
        .unwrap();
        let maps = [mapping(1), mapping(2)];
        assert_eq!(
            data.repository.receive(38, &maps),
            Err(ReceiveError::Storage)
        );
        assert_eq!(data.row_count(38), 0);
        db.execute_batch("DROP TRIGGER fault").unwrap();
        assert!(data.repository.receive(38, &maps).is_ok());
        assert_eq!(data.row_count(38), 3);
    }

    #[test]
    #[ignore = "requer SATELITE_TEST_PROPERTIES e uma conexão SQL Server"]
    fn sql_server_reenvio_preserva_recibo_e_vinculos_e_falha_desfaz_a_lista() {
        let path = std::env::var_os("SATELITE_TEST_PROPERTIES").expect("SATELITE_TEST_PROPERTIES");
        let settings =
            crate::config::Settings::from_properties(std::path::Path::new(&path)).unwrap();
        let server = super::super::SqlServer::new(&settings).unwrap();
        let query =
            include_str!("selia_receber.sql").replace("dbo.tb_log_integracao", "#plp_satelite");
        assert!(!query.contains("dbo."));
        let mut first = mapping(1);
        first.order_number = "Ação".into();
        first.volume_number = "B".into();
        let mut second = mapping(2);
        second.order_number = "P2".into();
        second.volume_number = "A".into();
        let original = serde_json::to_string(&[&first, &second]).unwrap();
        let changed = serde_json::to_string(&[mapping(3)]).unwrap();
        second.order_number = "FALHAR".into();
        let invalid = serde_json::to_string(&[&first, &second]).unwrap();
        let result = server
            .json(
                include_str!("../../tests/fixtures/selia_sql_server_temporario.sql"),
                &[query, original, changed, invalid],
            )
            .unwrap();
        assert_eq!(result["verificado"], true);
    }

    #[test]
    #[ignore = "requer SATELITE_TEST_PROPERTIES e uma conexão SQL Server"]
    fn sql_server_concorrencia_entre_conexoes_gera_um_unico_aceite() {
        let path = std::env::var_os("SATELITE_TEST_PROPERTIES").expect("SATELITE_TEST_PROPERTIES");
        let settings =
            crate::config::Settings::from_properties(std::path::Path::new(&path)).unwrap();
        let server = super::super::SqlServer::new(&settings).unwrap();
        let row = server.json(
            "SELECT REPLACE(CONVERT(char(36), NEWID()), '-', '') AS guid FOR JSON PATH, WITHOUT_ARRAY_WRAPPER",
            &[],
        ).unwrap();
        let guid = row["guid"].as_str().unwrap();
        assert!(guid.len() == 32 && guid.bytes().all(|b| b.is_ascii_hexdigit()));
        let table = format!("##satelite_plp_teste_{guid}");
        // Apenas o identificador aleatório da tabela é interpolado. Os dados da lista são parâmetros.
        let setup = format!("SET NOCOUNT ON; SELECT TOP (0) id, intelipost_pre_shipment_list, logistics_provider_shipment_list,
            chave_nfe, order_number, volume_number, sistema_destino, status, status_dados, status_canhoto, tentativas_dados,
            tentativas_canhoto, data_processamento, arquivado INTO {table} FROM dbo.tb_log_integracao;
            SELECT 1 AS criada FOR JSON PATH, WITHOUT_ARRAY_WRAPPER;");
        assert_eq!(server.json(&setup, &[]).unwrap()["criada"], 1);
        let query = RECEIVE_SQL_SERVER.replace("dbo.tb_log_integracao", &table);
        assert!(!query.contains("dbo."));
        let list = (i64::from_str_radix(&guid[..14], 16).unwrap() + (1_i64 << 62)).to_string();
        let barrier = Arc::new(Barrier::new(8));
        let tasks: Vec<_> = (1..=8)
            .map(|i| {
                let (db, sql, list, barrier) =
                    (server.clone(), query.clone(), list.clone(), barrier.clone());
                thread::spawn(move || {
                    let correlations = serde_json::to_string(&[mapping(i)]).unwrap();
                    barrier.wait();
                    db.json(&sql, &[list, correlations])
                })
            })
            .collect();
        let responses: Vec<_> = tasks.into_iter().map(|t| t.join()).collect();
        let verify = format!("SELECT COUNT(*) AS linhas, COUNT(DISTINCT logistics_provider_shipment_list) AS recibos,
            COUNT(DISTINCT order_number) AS pedidos FROM {table} FOR JSON PATH, WITHOUT_ARRAY_WRAPPER");
        let counts = server.json(&verify, &[]);
        server
            .json(
                &format!("SET NOCOUNT ON; DROP TABLE {table}; SELECT 1 AS removida FOR JSON PATH"),
                &[],
            )
            .unwrap();
        let counts = counts.unwrap();
        assert_eq!(
            counts,
            serde_json::json!({ "linhas": 2, "recibos": 1, "pedidos": 1 })
        );
        let ids: BTreeSet<_> = responses
            .into_iter()
            .map(|r| {
                let receipt = r.unwrap().unwrap();
                receipt_date(receipt["data"].as_str().unwrap()).unwrap();
                receipt["id"].as_i64().unwrap()
            })
            .collect();
        assert_eq!(ids.len(), 1);
    }
}
