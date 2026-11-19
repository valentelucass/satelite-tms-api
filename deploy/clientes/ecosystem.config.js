const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const repo = path.resolve(__dirname, '../..');
const javaBin = path.join(process.env.LOCALAPPDATA, 'Programs', 'Eclipse Adoptium', 'jdk-17', 'bin');
const java = path.join(javaBin, 'java.exe');
const base = path.join(__dirname, 'pacotes');
const versao = fs.readFileSync(path.join(base, 'atual.txt'), 'utf8').trim();
if (!/^[a-f0-9]{32}$/.test(versao)) {
  throw new Error('Versão de pacotes inválida. Confira o resultado de empacotar.ps1.');
}
const api = path.join(base, 'versoes', versao, 'satelite-api.jar');
const clientes = path.join(base, 'versoes', versao, 'satelite-clientes.jar');

for (const file of [java, path.join(repo, '.env'), api, clientes]) {
  if (!fs.statSync(file, { throwIfNoEntry: false })?.isFile()) {
    throw new Error('Arquivo necessário para iniciar o Satélite ausente: ' + file);
  }
}

const bootClasses = 'BOOT-INF/classes/com/example/satelite/';
for (const file of [api, clientes]) {
  const entries = new Set(execFileSync(path.join(javaBin, 'jar.exe'), ['tf', file], {
    encoding: 'utf8', windowsHide: true
  }).split(/\r?\n/));
  if (!entries.has('BOOT-INF/classes/logback-residente.xml') ||
      entries.has(bootClasses + 'controllers/IntegracaoAuditoriaController.class') !== (file === api) ||
      entries.has(bootClasses + 'ClientesApplication.class') !== (file === clientes) ||
      (file === clientes && !entries.has(bootClasses + 'services/etl/ClientesWorker.class'))) {
    throw new Error('Conteúdo incompatível com o papel do pacote: ' + file);
  }
}

// O Spring lê a configuração de integração do .env no diretório do projeto.
const windowsEnv = Object.fromEntries(
  ['SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA']
    .filter(key => process.env[key])
    .map(key => [key, process.env[key]])
);
const common = {
  namespace: 'satelite-tms',
  cwd: repo,
  script: java,
  interpreter: 'none',
  exec_mode: 'fork',
  instances: 1,
  watch: false,
  windowsHide: true,
  autorestart: true,
  filter_env: [''],
  env: { ...windowsEnv, LANG: 'pt_BR.UTF-8', LC_ALL: 'pt_BR.UTF-8' },
  log_date_format: 'YYYY-MM-DD HH:mm:ss.SSS'
};
const commonArgs = jar => [
  '-Xms64m', '-Xmx384m', '-Dfile.encoding=UTF-8', '-jar', jar,
  '--spring.config.import=file:.env[.properties]',
  '--logging.config=classpath:logback-residente.xml',
  '--debug=false',
  '--spring.jpa.hibernate.ddl-auto=validate',
  '--spring.sql.init.mode=never',
  '--spring.datasource.hikari.minimum-idle=1',
  '--APP_SCHEDULER_ENABLED=false',
  '--APP_CICLO_UNICO=false',
  '--ciclo_unico=false',
  '--APP_NIGHTLY_RETRY_ENABLED=false',
  '--APP_ETL_REPESCAGEM_ENABLED=false',
  '--retroactive.enabled=false',
  '--RETROACTIVE_ENABLED=false',
  '--VEDACIT_RECONCILIACAO_ENABLED=false',
  '--work.sftp-clientes.enabled=false'
];

module.exports = {
  apps: [
    {
      ...common,
      name: 'api-integracoes-19090',
      restart_delay: 10000,
      min_uptime: 120000,
      max_restarts: 2,
      kill_timeout: 30000,
      args: [
        ...commonArgs(api),
        '--satelite.logs.papel=api-integracoes',
        '--server.address=127.0.0.1',
        '--server.port=19090',
        '--spring.datasource.hikari.maximum-pool-size=5',
        '--APP_DASHBOARD_API_ONLY=true',
        '--APP_CLIENTES_WORKER_ENABLED=false',
        '--APP_RECONCILIACAO_ENABLED=false',
        '--APP_LOG_RETENTION_ENABLED=false',
        '--SELIA_INTELIPOST_PLP_ENABLED=true'
      ]
    },
    {
      ...common,
      name: 'SATELITE-TMS-CLIENTES',
      restart_delay: 60000,
      min_uptime: 10000,
      max_restarts: 3,
      kill_timeout: 60000,
      args: [
        '-XX:+ExitOnOutOfMemoryError',
        ...commonArgs(clientes),
        '--satelite.logs.papel=clientes',
        '--spring.main.web-application-type=none',
        '--server.port=0',
        // Até 9 conexões, correspondentes aos três pools anteriores de 3. Os locks SQL usam conexões próprias.
        '--spring.datasource.hikari.maximum-pool-size=9',
        '--APP_CLIENTES_WORKER_ENABLED=true',
        '--APP_DASHBOARD_API_ONLY=false',
        '--APP_RECONCILIACAO_ENABLED=true',
        '--RECONCILIACAO_CLIENTES=VEDACIT,PPG,SELIA,SUPPORTE',
        '--APP_TIME_ZONE=America/Sao_Paulo',
        '--VEDACIT_RECONCILIACAO_START=02:00',
        '--VEDACIT_RECONCILIACAO_END=06:00',
        '--VEDACIT_RECONCILIACAO_RECOVERY_ENABLED=true',
        '--VEDACIT_RECONCILIACAO_PAGE_SIZE=100',
        '--VEDACIT_RECONCILIACAO_PACING_MS=1000',
        '--APP_LOG_RETENTION_ENABLED=true',
        '--APP_PPG_ENABLED=false',
        '--APP_SUPPORTE_ENABLED=false',
        '--APP_VEDACIT_ENABLED=true',
        '--APP_SELIA_ENABLED=true',
        '--SELIA_NFE_WHITELIST_ENABLED=false',
        '--SELIA_INTELIPOST_PLP_ENABLED=false',
        '--SELIA_INCREMENTAL_LOOKBACK_HOURS=0',
        '--SFTP_RODOGARCIA_ENABLED=true',
        '--VEDACIT_SFTP_RECEIPT_ONLY=true',
        '--VEDACIT_SEND_CTE_XML_ENABLED=true',
        '--VEDACIT_SEND_CANHOTO_ENABLED=true',
        '--VEDACIT_SEND_OCCURRENCE_ENABLED=false',
        '--WORK_SFTP_CLIENTES_XML_ENABLED=true',
        '--WORK_SFTP_CLIENTES_DRAIN_ENABLED=true',
        '--WORK_SFTP_CLIENTES_MAX_CONSECUTIVE_ERRORS=3',
        '--WORK_SFTP_CLIENTES_TURN_ITEMS=10',
        '--WORK_SFTP_CLIENTES_TURN_MS=120000',
        '--WORK_SFTP_CLIENTES_INVENTORY_ITEMS_PER_TURN=100',
        '--VEDACIT_XML_SEND_INTERVAL_MS=1000',
        '--VEDACIT_XML_SOURCE_RETRY_ITEMS=10',
        '--VEDACIT_XML_SOURCE_RETRY_COOLDOWN_MS=1800000'
      ]
    }
  ]
};
