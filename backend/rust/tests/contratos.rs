use regex::Regex;
use rusqlite::Connection;
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

fn catalogo() -> Value {
    serde_json::from_str(include_str!("../contracts/api-integracoes.json")).unwrap()
}

#[test]
fn catalogo_acompanha_as_rotas_publicadas_pelos_controllers_java() {
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let pasta = repo.join("backend/java/src/main/perfis/api/java/com/example/satelite/controllers");
    let prefixo = Regex::new(r#"@RequestMapping\("([^"]+)"\)"#).unwrap();
    let metodo =
        Regex::new(r#"@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]+)")?"#)
            .unwrap();
    let mut implementadas = BTreeSet::new();
    for item in fs::read_dir(pasta).unwrap() {
        let arquivo = item.unwrap().path();
        if arquivo.extension().and_then(|v| v.to_str()) != Some("java") {
            continue;
        }
        let codigo = fs::read_to_string(arquivo).unwrap();
        let Some(base) = prefixo.captures(&codigo) else {
            continue;
        };
        for captura in metodo.captures_iter(&codigo) {
            let sufixo = captura.get(2).map_or("", |v| v.as_str());
            implementadas.insert(format!(
                "{} {}{}",
                captura[1].to_uppercase(),
                &base[1],
                sufixo
            ));
        }
    }
    let contrato = catalogo();
    let rotas = contrato["rotas"].as_array().unwrap();
    let mut documentadas = BTreeSet::new();
    for rota in rotas {
        let chave = format!(
            "{} {}",
            rota["metodo"].as_str().unwrap(),
            rota["caminho"].as_str().unwrap()
        );
        assert!(documentadas.insert(chave), "Rota duplicada no catálogo");
        let (arquivo, handler) = rota["controller"]
            .as_str()
            .unwrap()
            .split_once('#')
            .unwrap();
        assert!(fs::read_to_string(repo.join(arquivo))
            .unwrap()
            .contains(&format!("{handler}(")));
        for tabela in rota["tabelas"].as_array().unwrap() {
            assert!(contrato["tabelas"].get(tabela.as_str().unwrap()).is_some());
        }
    }
    assert_eq!(implementadas, documentadas);
}

#[test]
fn as_colunas_de_auditoria_da_bancada_correspondem_ao_dicionario_sql() {
    let db = Connection::open_in_memory().unwrap();
    db.execute_batch(include_str!("fixtures/schema_bancada.sql"))
        .unwrap();
    let mut stmt = db
        .prepare(
            "SELECT name, (\"notnull\" = 0 AND pk = 0) FROM pragma_table_info('tb_log_integracao')",
        )
        .unwrap();
    let banco: BTreeMap<String, bool> = stmt
        .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    let contrato = catalogo();
    let dicionario: BTreeMap<_, _> = contrato["tabelas"]["dbo.tb_log_integracao"]["colunas"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(nome, campo)| (nome.clone(), campo["permiteNulo"].as_bool().unwrap()))
        .collect();
    assert_eq!(banco, dicionario);
}
