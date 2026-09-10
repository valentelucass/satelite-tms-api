const path = require("path");
const JAVA_17_EXECUTABLE = path.join(__dirname, ".tools", "temurin17", "jdk-17.0.19+10", "bin", "java.exe");

module.exports = {
  apps: [
    {
      name: "Satelite-API-19090", cwd: __dirname, script: JAVA_17_EXECUTABLE, interpreter: "none",
      exec_mode: "fork", instances: 1, autorestart: true, windowsHide: true,
      args: ["-Dfile.encoding=UTF-8", "-jar", "target\\satelite-0.0.1-SNAPSHOT.jar", "--debug=false", "--APP_DASHBOARD_API_ONLY=true", "--APP_SCHEDULER_ENABLED=false", "--APP_CICLO_UNICO=false", "--APP_NIGHTLY_RETRY_ENABLED=false", "--APP_ETL_REPESCAGEM_ENABLED=false", "--server.port=19090"],
      env: { LANG: "pt_BR.UTF-8", LC_ALL: "pt_BR.UTF-8" }
    },
    {
      name: "WORK-SFTP-CLIENTES", cwd: __dirname, script: JAVA_17_EXECUTABLE, interpreter: "none",
      exec_mode: "fork", instances: 1, autorestart: true, restart_delay: 30 * 60 * 1000, windowsHide: true,
      out_file: "logs\\work-sftp-clientes-out.log", error_file: "logs\\work-sftp-clientes-error.log", log_date_format: "YYYY-MM-DD HH:mm:ss.SSS",
      args: ["-Dfile.encoding=UTF-8", "-jar", "target\\satelite-0.0.1-SNAPSHOT.jar", "--debug=false", "--spring.main.web-application-type=none", "--server.port=0", "--work.sftp-clientes.enabled=true", "--APP_SCHEDULER_ENABLED=false", "--APP_CICLO_UNICO=false", "--APP_NIGHTLY_RETRY_ENABLED=false", "--APP_ETL_REPESCAGEM_ENABLED=false", "--APP_PPG_ENABLED=false", "--APP_SELIA_ENABLED=false", "--APP_SUPPORTE_ENABLED=false", "--APP_VEDACIT_ENABLED=true", "--VEDACIT_SFTP_RECEIPT_ONLY=true"],
      env: { LANG: "pt_BR.UTF-8", LC_ALL: "pt_BR.UTF-8",
        WORK_SFTP_CLIENTES_XML_ENABLED: "true", SFTP_RODOGARCIA_ENABLED: "true",
        VEDACIT_SEND_CTE_XML_ENABLED: "true", WORK_SFTP_CLIENTES_DRAIN_ENABLED: "true",
        WORK_SFTP_CLIENTES_MAX_CONSECUTIVE_ERRORS: "3", VEDACIT_XML_SEND_INTERVAL_MS: "1000"
      }
    }
  ]
};
