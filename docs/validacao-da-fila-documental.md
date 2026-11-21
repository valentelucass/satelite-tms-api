# Como validar uma mudança na fila de comprovantes

A fila seleciona quais notas o worker pode processar e em que ordem. Uma consulta mais rápida precisa preservar os mesmos documentos elegíveis, o saldo, os dados devolvidos ao Java e a entrega única. A revisão `d5c7ca2` e a migração V30 são o exemplo medido em 20/11/2026. [Histórico da implantação](../states.md) · [Índice da fila](../database/sql/migration/V30__indice_fila_documental_vedacit.sql)

## Comparar as duas consultas na cópia

1. Restaurar uma cópia do Satélite e usar um receptor de teste. Guardar a versão anterior do worker. Escolher um horário de elegibilidade fixo para comparar as duas versões com a mesma fila.
2. Incluir fila vazia e cheia, chaves repetidas, formatos legados e empates no horário. O caminho com `OPENJSON` recebe em um parâmetro de 32 a 500 chaves canônicas de NF-e. Cada chave tem exatamente 44 dígitos ASCII. As demais entradas passam pela regra já existente. Conferir as condições de seleção também nos limites de quantidade. [Consulta usada pelo repositório](../backend/java/src/main/java/com/example/satelite/repositories/ConsultaInventarioNfeImpl.java)
3. Comparar saldo, sequência de IDs e os 42 campos, inclusive nulos. O desempate é por `data_processamento` e `id`. Repetir com dois leitores e testar a retomada depois do aceite do receptor. Contar arquivos recebidos e confirmações persistidas no destino de teste. [Cobertura da seleção](../backend/java/src/test/java/com/example/satelite/repositories/ConsultaInventarioNfeTest.java) · [Cobertura do repositório](../backend/java/src/test/java/com/example/satelite/repositories/LogIntegracaoRepositoryTest.java)
4. Após aquecer as duas versões, guardar as medianas de tempo e leituras lógicas com a mesma entrada. No plano, anotar a memória concedida e a parcela efetivamente usada. Rodar as suítes com o executor documentado no projeto. [Testes locais](../README.md)

| Cópia restaurada, 500 chaves | Antes | Depois |
| --- | ---: | ---: |
| Contar 133 notas, leituras lógicas | 2.360 | 8 |
| Tempo mediano da contagem, µs | 207.575 | 12.899 |
| Buscar o comprovante elegível, leituras lógicas | 2.360 | 1.100 |
| Tempo mediano da busca, µs | 233.084 | 8.713 |

As duas consultas receberam reserva de 1.024 KiB nas duas versões. A parte usada passou de 88 para 0 KiB na contagem e de 96 para 16 KiB na busca. Houve 540 execuções SQL, 36 comparações pela aplicação e dez comparações concorrentes. Dois processos e uma retomada geraram um único arquivo de 687 bytes e um único aceite local.

## Interpretar o ciclo em produção

Consultar `dbo.tb_work_sftp_cliente_execucao` pelo horário de início e fim. Registrar conexão, selecionados, enviados, pendentes, erros de XML, erros de comprovante e `resultado_execucao`. Para entender uma pendência, cruzar `execucao_id` com `dbo.tb_integracao_etapa_evento` e agrupar etapa, ação e motivo. Os identificadores de documento podem ficar no ambiente operacional.

Em 20/11, o último ciclo antes da troca e o primeiro depois já mostravam 11 erros de XML e 133 comprovantes pendentes. No último ciclo acompanhado, 131 dependiam de dados da data de entrega e dois aguardavam a origem. Nos 11 ciclos encerrados após a implantação houve zero envios reais. O aceite único pertence ao ensaio com o receptor local. Em uma próxima ocorrência elegível, a confirmação do destino é a prova de envio real.
