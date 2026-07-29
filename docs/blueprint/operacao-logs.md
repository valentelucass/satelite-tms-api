# Operação de logs do Satélite

## Política adotada

- Pasta do projeto: `logs`.
- Máximo de 20 arquivos de log.
- Máximo de 30 dias por arquivo.
- Máximo agregado de 500 MB.
- Arquivos mais antigos são removidos primeiro; o script atua apenas em `.log`, `.out` e `.err` dentro da pasta `logs` do projeto.

Os limites podem ser ajustados no `.env` sem alterar código:

```properties
LOG_RETENTION_MAX_FILES=20
LOG_RETENTION_MAX_AGE_DAYS=30
LOG_RETENTION_MAX_TOTAL_MB=500
APP_LOG_RETENTION_ENABLED=true
APP_LOG_RETENTION_INTERVAL_MS=900000
```

## Reciclagem automatica pela API

O proprio processo Spring Boot online executa a verificacao a cada 15 minutos, mesmo quando o scheduler de ETL estiver desabilitado. Ele atua somente em `logs`, nunca na auditoria SQL e preserva os arquivos mais recentes.

`APP_LOG_RETENTION_ENABLED=false` desliga apenas esta reciclagem. `APP_LOG_RETENTION_INTERVAL_MS` altera a frequencia da verificacao.

## Limpeza manual excepcional

`scripts\rotacionar_logs.bat` continua disponivel para uma limpeza manual excepcional. Ele chama `rotacionar_logs.ps1`, valida que o alvo permanece dentro do projeto e remove somente arquivos que excedam idade, quantidade ou tamanho total.

Para conferir antes de apagar, execute manualmente:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rotacionar_logs.ps1 -WhatIf
```

## Segurança operacional

- Nunca use limpeza recursiva fora de `logs`.
- Não limpe `tb_log_integracao`: ela é a auditoria oficial da integração e não é um log de arquivo descartável.
- Não remova arquivos ativos manualmente; a propria API preserva os arquivos mais recentes e faz a limpeza periodica.
