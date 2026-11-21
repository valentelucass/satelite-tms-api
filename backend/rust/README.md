# API de integrações em Rust

O executável atende as 21 rotas do Satélite ao usar SQL Server. O Dashboard consulta a auditoria e baixa relatórios pela porta 19090. A SELIA envia as listas de pré-embarque à mesma API. O [catálogo de contratos](contracts/api-integracoes.json) identifica o método, os parâmetros e os campos de cada rota.

## Banco do Satélite

O modo `--sql-server` escuta em `127.0.0.1` e usa o Microsoft ODBC Driver 18 para SQL Server. O processo lê as propriedades do arquivo `.env` em seu diretório de trabalho. `SATELITE_DB_URL`, `SATELITE_DB_USER` e `SATELITE_DB_PASSWORD` seguem os nomes usados no Spring, com fallback para `DB_URL`, `DB_USER` e `DB_PASSWORD`. A URL JDBC precisa apontar para `SATELITE_TMS_AUDITORIA`. A conexão usa criptografia e respeita a configuração de certificado da URL. O limite é de cinco conexões por processo.

O [procedimento do PM2](../../deploy/clientes/README.md) descreve a operação na máquina do Satélite. Para conferir uma instância em uma porta local disponível, execute o binário com `<porta> --sql-server` a partir da raiz do projeto. O SQL das métricas usa o período do painel em São Paulo com conversão para UTC, como o acesso JPA da aplicação Java. A tabela de pendências e os arquivos CSV usam datas locais, como nas consultas JDBC correspondentes.

Uma lista SELIA repetida no SQL Server recebe o número e a data do aceite anterior e preserva os vínculos já gravados. Essa regra também vale quando o conteúdo reenviado mudou.

## Bancada SQLite

A bancada com arquivo SQLite exercita três rotas.

| Rota | Comportamento exercitado |
|---|---|
| `GET /api/auditoria/integracoes-clientes` | Consulta principal do Dashboard. Retorna as métricas dos clientes, os registros filtrados e a paginação. |
| `POST /api/selia/intelipost/pre-shipment-list` | Autenticação e recebimento das listas de pré-embarque SELIA, com gravação transacional e tratamento de repetição. |
| `GET /api/auditoria/logs/{id}/imagem` | Leitura pelo Dashboard de um canhoto PPG salvo na auditoria. Entrega o texto da imagem ou HTTP 404. |

O modo SQLite usa um arquivo de teste indicado na linha de comando. Na bancada, um reenvio com vínculos diferentes responde `409`. O [projeto Java](../java) contém a implementação de referência e o processamento dos clientes.

## Onde encontrar cada parte

- `src/controllers` recebe as chamadas HTTP e devolve o status e o corpo da resposta.
- `src/dto` descreve a lista de pré-embarque e o recibo que a SELIA troca com o Satélite.
- `src/models` descreve os dados do painel, do recibo e dos vínculos entre nota, pedido e volume.
- `src/services` aplica as regras de cada consulta ou recebimento.
- `src/repositories` executa as consultas ao banco.
- `sql/auditoria` reúne as consultas do painel. [sql/selia/receber.sql](sql/selia/receber.sql) grava a lista de pré-embarque e seus vínculos em uma transação. O Rust incorpora esses arquivos ao executável durante a compilação.
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
