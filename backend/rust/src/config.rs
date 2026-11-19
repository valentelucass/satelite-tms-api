use std::{collections::HashMap, env, fs::File, io::ErrorKind, path::Path};

#[derive(Default)]
pub struct Settings(HashMap<String, String>);

impl Settings {
    pub fn from_properties(file: &Path) -> Result<Self, String> {
        let input = match File::open(file) {
            Ok(file) => file,
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(Self::default()),
            Err(_) => return Err("Falha ao abrir as configurações do Satélite".into()),
        };
        let values = java_properties::read(input)
            .map_err(|_| "Arquivo de propriedades do Satélite inválido")?;
        Ok(Self(values))
    }

    pub fn get(&self, key: &str) -> Option<String> {
        env::var(key).ok().or_else(|| self.0.get(key).cloned())
    }
}
