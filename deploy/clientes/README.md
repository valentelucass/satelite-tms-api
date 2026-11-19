# Satélite TMS em dois processos

| Processo no grupo `satelite-tms` | Responsabilidade |
|---|---|
| `api-integracoes-19090` | Consultas do dashboard e recepção das listas de pré-embarque SELIA/Intelipost em `127.0.0.1:19090`. |
| `clientes` | Integração de eventos e comprovantes SELIA, processamento documental Vedacit por SFTP e reconciliação de Vedacit, PPG, SELIA e SUPPORTE. |

A API inicia em modo dashboard com `satelite-api.jar`. O segundo processo usa `satelite-clientes.jar`, compilado com o perfil Maven `clientes-worker`, e inicia sem porta HTTP. Os controladores do painel e do recebimento SELIA pertencem ao pacote da API. SFTP e SELIA têm uma agenda por rotina e cada uma aguarda 30 minutos depois do próprio término. A reconciliação conserva as janelas de revisão por cliente. A seleção explícita de SELIA mantém a leitura do seu cursor e evita iniciar uma segunda passagem de Vedacit. PPG e SUPPORTE participam da revisão local. Seus envios continuam desabilitados neste perfil.

O worker registra o resultado de cada ciclo no log. Uma falha normal da integração termina o lote e permite a próxima tentativa. Uma falha fatal do agendador provoca saída do processo para recuperação pelo PM2. O índice de XML da passagem SFTP é liberado ao terminar. Os dois papéis gravam logs separados em `logs-residentes`, com rotação diária e por tamanho.

## Preparação local

Execute na raiz do projeto. A suíte usa JDK 17, dependências do cache Maven e as fontes SOAP locais em `target/generated-sources`. O agente de instrumentação permite que o Mockito funcione com o bloqueio de rede da suíte.

```powershell
$jdk = Join-Path $env:LOCALAPPDATA 'Programs/Eclipse Adoptium/jdk-17'
$maven = (Resolve-Path '.\backend\java\mvnw.cmd').Path
$instrumentacao = Join-Path $env:USERPROFILE '.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.10/byte-buddy-agent-1.18.10.jar'
$env:SATELITE_TEST_BYTE_BUDDY_AGENT = $instrumentacao
$env:SATELITE_TEST_BYTE_BUDDY_AGENT_SHA256 = (Get-FileHash -LiteralPath $instrumentacao).Hash
.\scripts\testar_com_cobertura.ps1 -Maven $maven -JavaHome $jdk
.\scripts\testar_com_cobertura.ps1 -Maven $maven -JavaHome $jdk -Papel clientes
$saidaTestes = Get-Content -LiteralPath 'target/unit-tests/latest-coverage.txt'
$saidaClientes = Get-Content -LiteralPath 'target/unit-tests/latest-coverage-clientes.txt'
.\deploy\clientes\empacotar.ps1 -SaidaDosTestes $saidaTestes -SaidaDosTestesClientes $saidaClientes -Maven $maven -JavaHome $jdk
node -e "console.log(require('./deploy/clientes/ecosystem.config.js').apps.map(a => a.namespace + '/' + a.name))"
```

O empacotador cria os dois arquivos na mesma pasta em `deploy/clientes/pacotes/versoes` e mostra os caminhos e hashes SHA-256. Depois da conferência, `deploy/clientes/pacotes/atual.txt` identifica a versão completa usada pelo manifesto. Uma falha durante a preparação mantém a seleção anterior. A última linha inspeciona os dois pacotes. A pasta de pacotes fica fora da saída de compilação do Maven. O cadastro no PM2 é uma etapa operacional separada.

## Operação

Use `node deploy/clientes/operar.cjs status` na raiz para ver os dois serviços. Os comandos `iniciar` e `parar` aceitam `api-integracoes-19090`, `clientes` ou `todos`. O menu `satelite.bat` usa os mesmos nomes. Parar o worker interrompe as agendas de todos os clientes e a reconciliação.

Na migração de 18/11/2026, os três processos antigos de clientes foram desligados entre os ciclos e fora da janela noturna. Os dois cadastros novos foram verificados no PM2 e nos dois arquivos de restauração usados no servidor. As demais 13 definições foram preservadas. A tarefa `PM2-Projetos` usa o cadastro salvo para restaurar os serviços quando o Windows inicia.

Para uma reversão da migração no mesmo servidor, use o backup protegido da conta de operação. A ferramenta `../.runtime/pm2/satelite-tms/persistir-satelite.cjs` oferece `check` e `verify` para conferência. O procedimento de retorno exige encerrar os ciclos, remover os dois cadastros novos, restaurar os quatro anteriores com `restore-live` e atualizar os arquivos de boot com `rollback`. Os registros de auditoria e cursores permanecem no banco.
