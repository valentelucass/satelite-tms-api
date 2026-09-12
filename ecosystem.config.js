const path = require("path");
const JAVA_17_EXECUTABLE = path.join(__dirname, ".tools", "temurin17", "jdk-17.0.19+10", "bin", "java.exe");
const SATELITE_JAR = "target\\satelite-0.0.1-SNAPSHOT.jar";

module.exports = {
  apps: [
    {
      name: "Satelite-API-19090", cwd: __dirname, script: JAVA_17_EXECUTABLE, interpreter: "none",
      exec_mode: "fork", instances: 1, autorestart: true, windowsHide: true,
      args: ["-Dfile.encoding=UTF-8", "-jar", SATELITE_JAR, "--debug=false", "--APP_DASHBOARD_API_ONLY=true", "--APP_SCHEDULER_ENABLED=false", "--APP_CICLO_UNICO=false", "--APP_NIGHTLY_RETRY_ENABLED=false", "--APP_ETL_REPESCAGEM_ENABLED=false", "--server.port=19090"],
      env: { LANG: "pt_BR.UTF-8", LC_ALL: "pt_BR.UTF-8" }
    },
    {
      name: "WORK-SFTP-CLIENTES", cwd: __dirname, script: JAVA_17_EXECUTABLE, interpreter: "none",
      exec_mode: "fork", instances: 1, autorestart: true, restart_delay: 30 * 60 * 1000, windowsHide: true,
      out_file: "logs\\work-sftp-clientes-out.log", error_file: "logs\\work-sftp-clientes-error.log", log_date_format: "YYYY-MM-DD HH:mm:ss.SSS",
      args: ["-Dfile.encoding=UTF-8", "-jar", SATELITE_JAR, "--debug=false", "--spring.main.web-application-type=none", "--server.port=0", "--work.sftp-clientes.enabled=true", "--APP_SCHEDULER_ENABLED=false", "--APP_CICLO_UNICO=false", "--APP_NIGHTLY_RETRY_ENABLED=false", "--APP_ETL_REPESCAGEM_ENABLED=false", "--APP_PPG_ENABLED=false", "--APP_SELIA_ENABLED=false", "--APP_SUPPORTE_ENABLED=false", "--APP_VEDACIT_ENABLED=true", "--VEDACIT_SFTP_RECEIPT_ONLY=true"],
      env: { LANG: "pt_BR.UTF-8", LC_ALL: "pt_BR.UTF-8",
        WORK_SFTP_CLIENTES_XML_ENABLED: "true", SFTP_RODOGARCIA_ENABLED: "true",
        VEDACIT_SEND_CTE_XML_ENABLED: "true", WORK_SFTP_CLIENTES_DRAIN_ENABLED: "true",
        WORK_SFTP_CLIENTES_MAX_CONSECUTIVE_ERRORS: "3", VEDACIT_XML_SEND_INTERVAL_MS: "1000",
        WORK_SFTP_CLIENTES_TURN_ITEMS: "10", WORK_SFTP_CLIENTES_TURN_MS: "120000",
        WORK_SFTP_CLIENTES_INVENTORY_ITEMS_PER_TURN: "100",
        VEDACIT_XML_SOURCE_RETRY_ITEMS: "10", VEDACIT_XML_SOURCE_RETRY_COOLDOWN_MS: "1800000"
      }
    },
    {
      // Residente: revisa de madrugada mesmo enquanto o worker normal espera entre ciclos.
      name: "VEDACIT-RECONCILIACAO-NOTURNA", cwd: __dirname, script: JAVA_17_EXECUTABLE, interpreter: "none",
      exec_mode: "fork", instances: 1, autorestart: true, restart_delay: 60000, windowsHide: true,
      out_file: "logs/reconciliacao-vedacit-out.log", error_file: "logs/reconciliacao-vedacit-error.log",
      log_date_format: "YYYY-MM-DD HH:mm:ss.SSS",
      args: ["-Dfile.encoding=UTF-8", "-jar", SATELITE_JAR,
        "--spring.main.web-application-type=none", "--server.port=0",
        "--VEDACIT_RECONCILIACAO_ENABLED=true", "--VEDACIT_RECONCILIACAO_START=02:00", "--VEDACIT_RECONCILIACAO_END=06:00",
        "--APP_TIME_ZONE=America/Sao_Paulo", "--APP_DASHBOARD_API_ONLY=false", "--APP_SCHEDULER_ENABLED=false",
        "--APP_CICLO_UNICO=false", "--ciclo_unico=false", "--APP_NIGHTLY_RETRY_ENABLED=false", "--APP_ETL_REPESCAGEM_ENABLED=false",
        "--retroactive.enabled=false", "--RETROACTIVE_ENABLED=false", "--spring.jpa.hibernate.ddl-auto=validate",
        "--work.sftp-clientes.enabled=false", "--APP_PPG_ENABLED=false", "--APP_SELIA_ENABLED=false",
        "--APP_SUPPORTE_ENABLED=false", "--APP_VEDACIT_ENABLED=true", "--VEDACIT_SFTP_RECEIPT_ONLY=true",
        "--SFTP_RODOGARCIA_ENABLED=true", "--VEDACIT_SEND_CTE_XML_ENABLED=true", "--VEDACIT_SEND_OCCURRENCE_ENABLED=false",
        "--VEDACIT_SEND_CANHOTO_ENABLED=true", "--VEDACIT_RECONCILIACAO_RECOVERY_ENABLED=true",
        "--VEDACIT_RECONCILIACAO_PAGE_SIZE=100", "--VEDACIT_RECONCILIACAO_PACING_MS=1000",
        "--APP_LOG_RETENTION_ENABLED=false", "--spring.task.scheduling.pool.size=1"],
      env: { LANG: "pt_BR.UTF-8", LC_ALL: "pt_BR.UTF-8" }
    }
  ]
};
