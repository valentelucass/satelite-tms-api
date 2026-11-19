# Piloto da API de integrações

O executável de bancada atende três rotas do Satélite.

| Rota | Comportamento exercitado |
|---|---|
| `GET /api/auditoria/integracoes-clientes` | Consulta principal do Dashboard. Retorna as métricas dos clientes, os registros filtrados e a paginação. |
| `POST /api/selia/intelipost/pre-shipment-list` | Autenticação e recebimento das listas de pré-embarque SELIA, com gravação transacional e tratamento de repetição. |
| `GET /api/auditoria/logs/{id}/imagem` | Leitura pelo Dashboard de um canhoto PPG salvo na auditoria. Entrega o texto da imagem ou HTTP 404. |

O Rust escuta em `127.0.0.1` e acessa diretamente um arquivo SQLite de teste. O [projeto Java](../java) contém a API de referência e o processamento dos clientes. A homologação com SQL Server e as demais 18 rotas fazem parte da avaliação para uma futura substituição.

No recebimento SELIA, uma lista repetida com os mesmos vínculos recebe o número e a hora do aceite anterior. Se o mesmo número chegar com vínculos diferentes, o piloto responde `409` e preserva os dados gravados. O Java atual responde `200` nessa situação. A adoção dessa mudança depende do comportamento esperado pela SELIA.

## Onde encontrar cada parte

- `src/controllers` recebe as chamadas HTTP e devolve o status e o corpo da resposta.
- `src/dto` descreve a lista de pré-embarque e o recibo que a SELIA troca com o Satélite.
- `src/models` descreve os dados do painel, do recibo e dos vínculos entre nota, pedido e volume.
- `src/services` aplica as regras de cada consulta ou recebimento.
- `src/repositories` contém as consultas ao banco e a transação que grava a lista e seus vínculos.
- O [catálogo de contratos](contracts/api-integracoes.json) descreve o formato das chamadas Java, os campos do dashboard e as colunas do banco. Os [dados de teste](tests/fixtures) reproduzem os casos usados na bancada.

## Rodar localmente

Na pasta `backend/rust`, rode estes comandos com Cargo e Python disponíveis no PowerShell.

```powershell
cargo test --release --locked
cargo build --release --locked
$banco = Join-Path ([IO.Path]::GetTempPath()) ("satelite-plp-" + [Guid]::NewGuid() + ".sqlite")
python -c "import sqlite3,sys; from pathlib import Path; c=sqlite3.connect(sys.argv[1]); [c.executescript(Path('tests/fixtures', f).read_text(encoding='utf-8')) for f in ('schema_bancada.sql','imagens_bancada.sql','painel_bancada.sql')]; c.commit(); c.close()" $banco
$env:SELIA_INTELIPOST_PLP_ENABLED = 'true'
$env:SELIA_INTELIPOST_LOGISTIC_PROVIDER_API_KEY = 'credencial-ficticia-bancada'
cargo run --release --locked -- 0 $banco
```

O processo imprime a porta escolhida pelo Windows. A imagem de teste usa o ID `-20`. Os IDs `-21` e `-22` exercitam dois casos de imagem indisponível. Para concluir a bancada, encerre o processo com Ctrl+C.
