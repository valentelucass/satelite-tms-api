# Database - Satelite TMS

Scripts SQL Server para criar a base de auditoria do ETL.

## Como subir

No PowerShell ou CMD, configure as variaveis e rode:

```bat
set DB_SERVER=localhost
set DB_NAME=SATELITE_TMS_AUDITORIA
set DB_USER=sa
set DB_PASSWORD=SUA_SENHA
database\subir_database.bat
```

O script le automaticamente o `.env` da raiz do projeto. Para criar a database
ele usa Windows Authentication por padrao. Se precisar usar um login SQL
administrativo, configure `DB_ADMIN_USER` e `DB_ADMIN_PASSWORD`.

`DB_USER` e `DB_PASSWORD` ficam reservados para o usuario da aplicacao. Quando
`DB_USER` estiver preenchido, o script cria o usuario dentro da database e
concede permissoes de leitura/escrita na tabela de auditoria.

O `sqlcmd` com drivers recentes criptografa a conexao por padrao. Para SQL
Server local com certificado autoassinado, o script usa
`DB_TRUST_SERVER_CERTIFICATE=true` por padrao e passa `-C` ao `sqlcmd`. Em
producao com certificado confiavel, defina `DB_TRUST_SERVER_CERTIFICATE=false`.

O Spring le as configuracoes pelo arquivo `.env` na raiz do projeto. Use
`config/env.example.properties` como base para montar esse arquivo sem colocar
segredos dentro do `application.properties`.

## Estrutura

- `sql/schema`: criacao idempotente da database, tabela e indices.
- `sql/maintenance`: scripts operacionais, sem exclusao automatica.
- `config/env.example.properties`: modelo das variaveis esperadas pelo Spring.

O banco deve guardar apenas auditoria, status, cursor e mensagens de erro. Nao grave imagens de canhoto nem dados operacionais completos.
