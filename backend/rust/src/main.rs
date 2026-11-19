mod config;
mod controllers;
mod dto;
mod models;
mod repositories;
mod services;

use std::env;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};

use repositories::Repository;
use tokio::net::TcpListener;

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args_os().skip(1);
    let uso = "Uso: satelite-api-piloto <porta, ou 0> <arquivo SQLite da bancada | --sql-server>";
    let porta: u16 = args
        .next()
        .and_then(|valor| valor.to_str()?.parse().ok())
        .ok_or(uso)?;
    let banco = args.next().ok_or(uso)?;
    if args.next().is_some() {
        return Err(uso.into());
    }
    let (repo, config) = if banco == "--sql-server" {
        let config = config::Settings::from_properties(Path::new(".env"))?;
        (Repository::open_sql_server(&config)?, config)
    } else {
        let caminho = PathBuf::from(banco);
        if !caminho.is_absolute() {
            return Err(uso.into());
        }
        (Repository::open(&caminho)?, config::Settings::default())
    };
    let settings = controllers::selia::SeliaSettings::from_config(&config)?;
    let sql_server = repo.sql_server().is_some();
    let mut app = controllers::selia::selia_router(repo.clone(), settings)
        .merge(controllers::imagem::router(repo.clone()))
        .merge(controllers::painel::router(repo.clone()));
    if sql_server {
        app = app
            .merge(controllers::consultas::router(repo.clone()))
            .merge(controllers::quarentena::router(repo));
    }
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, porta)).await?;
    let nome = if sql_server {
        "API do Satélite"
    } else {
        "API de bancada"
    };
    println!("{nome} em http://{}", listener.local_addr()?);
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await?;
    Ok(())
}
