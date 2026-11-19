# Satélite TMS

| Pasta | O que contém |
|---|---|
| [backend/java](backend/java) | Programa de clientes em `src/clientes/java`, com integrações e reconciliação em `src/main/java`. A API Java guardada para reversão tem seus controladores em `src/api/java`. |
| [backend/rust](backend/rust/README.md) | API do dashboard e do recebimento SELIA. O executável Windows atende as rotas diretamente no SQL Server. O modo com SQLite serve aos testes locais. |
| [backend/rust/contracts](backend/rust/contracts/api-integracoes.json) | Catálogo das 21 rotas do Satélite, com os campos de entrada e saída, os consumidores identificados e a origem dos dados. |
| [database](database) | Scripts SQL de criação e atualização do banco de auditoria. |
| [deploy/clientes](deploy/clientes/README.md) | Configuração dos dois programas ativos no PM2, `satelite-tms/api-integracoes-19090` e `satelite-tms/SATELITE-TMS-CLIENTES`. |
| [scripts](scripts) | Comandos de operação e de teste. |

A API usa um executável Rust e o programa de clientes usa um JAR Java. Os pacotes ficam em `deploy/clientes/pacotes`. O comando `node deploy/clientes/operar.cjs status` mostra os dois serviços. O histórico de versões está em [states.md](states.md).

## Testes locais

Rode a suíte Java pela raiz do projeto com `.\scripts\testar_com_cobertura.ps1` para a API e acrescente `-Papel clientes` para testar o worker. Para a suíte Rust, entre em `backend/rust` e execute `cargo test --release --locked`.
