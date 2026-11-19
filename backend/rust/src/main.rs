mod controllers;
mod dto;
mod models;
mod repositories;
mod services;

use std::env;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;

use repositories::Repository;
use tokio::net::TcpListener;

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args_os().skip(1);
    let uso = "Uso: satelite-api-piloto <porta de teste, ou 0> <arquivo SQLite da bancada>";
    let porta: u16 = args
        .next()
        .and_then(|valor| valor.to_str()?.parse().ok())
        .ok_or(uso)?;
    let caminho = PathBuf::from(args.next().ok_or(uso)?);
    if args.next().is_some() || !caminho.is_absolute() {
        return Err(uso.into());
    }

    let settings = controllers::selia::SeliaSettings::from_env()?;
    let repo = Repository::open(&caminho)?;
    let app = controllers::selia::selia_router(repo.clone(), settings)
        .merge(controllers::imagem::router(repo.clone()))
        .merge(controllers::painel::router(repo));
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, porta)).await?;
    println!("API de bancada em http://{}", listener.local_addr()?);
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await?;
    Ok(())
}
