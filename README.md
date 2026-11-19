# Satélite TMS

| Pasta | O que contém |
|---|---|
| [backend/java](backend/java) | Projeto Spring e Maven. A API usa os controladores em `src/api/java`. O programa de clientes usa `src/clientes/java` e os serviços de integração e reconciliação em `src/main/java`. |
| [backend/rust](backend/rust/README.md) | Versão local da API. Atende a consulta principal do dashboard, a imagem de comprovante e o recebimento de listas da SELIA. |
| [backend/rust/contracts](backend/rust/contracts/api-integracoes.json) | Catálogo das 21 rotas do Satélite, com os campos de entrada e saída, os consumidores identificados e a origem dos dados. |
| [database](database) | Scripts SQL de criação e atualização do banco de auditoria. |
| [deploy/clientes](deploy/clientes/README.md) | Configuração dos dois programas ativos no PM2, `satelite-tms/api-integracoes-19090` e `satelite-tms/clientes`. |
| [scripts](scripts) | Comandos de operação e de teste. |

Os processos usam JARs próprios em `deploy/clientes/pacotes/versoes` e gravam seus logs separadamente em `logs-residentes`. O comando `node deploy/clientes/operar.cjs status` mostra os dois serviços. O histórico de versões está em [states.md](states.md).

## Testes locais

Rode a suíte Java pela raiz do projeto com `.\scripts\testar_com_cobertura.ps1` para a API e acrescente `-Papel clientes` para testar o worker. Para a suíte Rust, entre em `backend/rust` e execute `cargo test --release --locked`.
