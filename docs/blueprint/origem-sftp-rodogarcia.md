# Origem SFTP Rodogarcia — redução de consumo ESL

## Decisão proposta

O acesso identificado é **SFTP**. FileZilla é somente o cliente gráfico usado para navegar nesse servidor; não deve fazer parte da automação do Satélite.

O melhor caminho é integrar o protocolo SFTP diretamente no Satélite como uma **fonte complementar de documentos**. A conta SFTP definida para a operação possui acesso amplo por decisão operacional; o Satélite deve selecionar explicitamente uma subpasta de cliente por configuração antes de qualquer listagem. A ESL permanece como origem das ocorrências e como fallback controlado quando o arquivo não existir, ainda estiver incompleto ou não puder ser validado.

```text
Ocorrências e cursor de negócio: ESL
XML CT-e e comprovante/POD:     SFTP preferencial, ESL apenas como fallback
```

### Estrutura Vedacit observada em 14/08/2026

A Rodogarcia informou que a integração ESL → SFTP está ativa e deposita comprovantes automaticamente. A área autorizada da Vedacit contém duas subpastas:

```text
/g_rodogarcia/VEDACIT/xml
/g_rodogarcia/VEDACIT/comprovantes
```

Na captura fornecida, os comprovantes seguem o padrão aparente `<referencia>_<chave_cte_44>_<chave_nfe_44>.<extensao>`. A segunda chave tem modelo fiscal `57` (CT-e) e a terceira `55` (NF-e), permitindo correlação direta sem varredura por conteúdo. O padrão ainda deve ser confirmado pela leitura controlada e pela inspeção do XML antes de virar regra produtiva. Foram observadas extensões de imagem e PDF; o leitor aceitará somente formatos já processáveis pelo fluxo Vedacit.

### Inventário somente leitura — 14/08/2026

Uma listagem autenticada, com validação da host key fixada, confirmou a estrutura sem download, envio ou alteração remota:

| Pasta | Arquivos | Tamanho total | Formatos | Correlação no nome |
| --- | ---: | ---: | --- | --- |
| `xml` | 0 | 0 B | — | não aplicável |
| `comprovantes` | 9 | 2.996.538 B | 4 JPEG, 3 JPG, 1 JFIF, 1 PDF | 9 de 9 no padrão esperado |

O SFTP já é fonte verificável de comprovantes para Vedacit. O teste completo de XML CT-e + comprovante permanece bloqueado apenas pela ausência momentânea de XML nessa subpasta; não se deve substituir a busca de XML na ESL antes de observar e validar ao menos uma amostra de XML no SFTP.

### Validação de uma amostra XML + comprovante — 14/08/2026

Após a publicação de um XML, o arquivo único disponível foi baixado temporariamente e removido logo após a validação local. A amostra tem 9.004 B, é XML bem-formado, contém `infCte` com chave de CT-e válida e uma chave de NF-e. O cruzamento pelos dados internos do XML encontrou exatamente um comprovante na pasta `comprovantes` com o mesmo par CT-e/NF-e. Nenhuma chamada à ESL, POST SOAP para Vedacit, alteração do SFTP ou persistência em banco ocorreu.

Isso valida o contrato técnico de leitura e correlação. Ainda não valida o envio à Vedacit: ele deve ocorrer apenas depois de o adaptador SFTP estar implementado, testado localmente e de haver autorização para uma única integração de homologação controlada.

Esta separação evita assumir que uma pasta de arquivos contém toda a linha do tempo de tracking. A troca da fonte de ocorrências só pode ser discutida se o contrato SFTP provar que entrega eventos completos, ordenados e idempotentes.

## O que pode economizar

O ganho provável está nas chamadas ESL por documento:

| Recurso atual | Fonte proposta | Condição para usar |
| --- | --- | --- |
| XML de CT-e | SFTP | XML íntegro e chave CT-e do conteúdo igual à chave esperada |
| Comprovante/POD | SFTP | Arquivo estável, formato aceito e correlação inequívoca com CT-e/NF-e |
| Ocorrências de frete | ESL | Permanece assim até evidência contratual de equivalente no SFTP |
| Página de ocorrências/cursor | ESL | Permanece assim; SFTP não é cursor de eventos por si só |

Não se deve baixar a árvore inteira nem consultar todas as subpastas de clientes. A rotina só poderá listar a subpasta da Rodogarcia autorizada para o Satélite.

## Segurança e limites obrigatórios

- A conta compartilhada autorizada pode ser usada pelo serviço, mas o seletor `SFTP_RODOGARCIA_CLIENT_PATH` é obrigatório e deve apontar exatamente para uma subpasta de cliente dentro de `SFTP_RODOGARCIA_BASE_PATH`. O processo não pode listar a raiz nem inferir ou alternar clientes.
- Guardar host, porta, usuário, chave privada ou segredo apenas no `.env`; nunca em código, logs, `states.md`, documentação ou telemetria.
- Fixar e validar a impressão digital da chave do servidor SFTP (host key pinning). Não aceitar uma chave de host nova automaticamente.
- Não permitir upload, exclusão, renomeação, arquivamento ou movimentação de arquivos remotos pelo Satélite.
- Limitar extensões aceitas, tamanho máximo, quantidade por ciclo e profundidade de diretórios. Rejeitar links simbólicos e caminhos fora da pasta autorizada.
- Considerar arquivo elegível somente quando tamanho for maior que zero e metadados estiverem estáveis por uma janela configurável; isso evita ler upload parcial.
- Registrar na auditoria apenas fonte, tipo de recurso, caminho relativo sanitizado/identificador hash, tamanho, data e resultado. Não persistir XML, imagem, credencial ou caminho absoluto de outro cliente.

## Desenho para implementação

Criar um adaptador SFTP isolado da regra de negócio, por exemplo `services.origem.sftp`, com biblioteca Java mantida e conexão SFTP nativa. Não automatizar FileZilla, navegador, tela remota ou script que dependa do perfil local do operador.

O adaptador deverá expor operações pequenas:

1. listar metadados da pasta autorizada;
2. localizar um candidato por chave documental e tipo de arquivo;
3. baixar somente o candidato validado;
4. devolver `encontrado`, `ausente`, `incompleto` ou `inválido`, sem decidir integração de destino.

O consumidor de XML/POD deve seguir esta ordem:

```text
1. consultar cache/cursor técnico local;
2. procurar no SFTP permitido;
3. validar nome, tamanho, estabilidade e conteúdo;
4. se válido, usar SFTP e registrar source=SFTP;
5. se ausente/incompleto, aplicar cooldown e só então usar fallback ESL, se habilitado;
6. nunca reenviar ao destino apenas porque a fonte mudou.
```

A idempotência atual por CT-e/NF-e continua soberana. O marcador técnico do SFTP deve usar ao menos caminho relativo, tamanho, última modificação e hash de conteúdo após download; mudança no arquivo precisa ser rastreável sem apagar o histórico.

## Piloto seguro antes do código produtivo

1. Definir explicitamente `SFTP_RODOGARCIA_CLIENT_PATH` para o cliente da execução e inventariar somente as subpastas `xml` e `comprovantes`: nomes, extensões, tamanho, data de modificação e quantidade de arquivos. Sem download em massa, sem POST e sem alteração remota.
2. Selecionar uma amostra pequena de documentos já conhecidos na auditoria: XML integrado, XML ausente na ESL e comprovante disponível.
3. Comparar manualmente chave, tipo, conteúdo e atraso de publicação entre SFTP e ESL.
4. Definir o padrão de nome/caminho e a retenção mínima. Se não houver correlação confiável, não automatizar busca por varredura.
5. Implementar o adaptador inicialmente com `SFTP_RODOGARCIA_ENABLED=false` e modo inventário/read-only. Só depois habilitar a preferência SFTP em lote pequeno.

Critérios de aceite:

- nenhum arquivo de outro cliente é listado pelo processo;
- nenhum arquivo remoto é modificado;
- cada XML/POD usado corresponde à chave documental esperada;
- fallback ESL ocorre somente quando SFTP não tem documento elegível;
- telemetria consegue distinguir `SFTP`, `ESL` e fallback;
- a amostra não cria envio duplicado nem altera cursor de ocorrência.

## Comunicação recomendada à Rodogarcia/ESL

> Temos acesso SFTP à área de arquivos da Rodogarcia e vamos selecionar explicitamente a subpasta de cada cliente para usar XML de CT-e e comprovantes como fonte de leitura, reduzindo consultas repetidas à API ESL. Poderiam confirmar o contrato técnico: fingerprint da chave SSH do servidor, estrutura e subpasta correta, padrão de nome dos XMLs/PODs, formato e correlação por CT-e/NF-e, momento de publicação, garantia de conclusão do upload, retenção, correção/reprocessamento de arquivos e limites de conexão? Não faremos upload, exclusão ou alteração de arquivos. As ocorrências continuarão sendo consultadas na ESL até validação formal de que exista equivalente completo no SFTP.

Essa solicitação é melhor do que pedir uma “API do FileZilla”: ela pede o contrato do sistema que realmente importa, o servidor SFTP e seus arquivos.

## Referências

- [FileZilla Pro CLI](https://filezillapro.com/cli/) é um produto separado para automação de transferências; não é necessário nem recomendado para o processo Java.
- [WinSCP — Automating FileZilla](https://winscp.net/eng/docs/guide_filezilla_automation) registra que o cliente FileZilla não oferece automação; o Satélite deve falar SFTP diretamente.
