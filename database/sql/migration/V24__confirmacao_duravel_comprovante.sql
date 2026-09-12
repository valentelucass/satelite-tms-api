USE SATELITE_TMS_AUDITORIA;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO
-- Auditoria de aceite por documento. Não contém arquivos nem dados de domínio.
IF OBJECT_ID('dbo.tb_confirmacao_comprovante', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_confirmacao_comprovante (
        chave_nfe VARCHAR(44) NOT NULL,
        chave_cte VARCHAR(44) NOT NULL,
        primeira_confirmacao_em DATETIME2 NULL,
        ultima_confirmacao_em DATETIME2 NULL,
        data_confiavel BIT NOT NULL,
        origem VARCHAR(40) NOT NULL,
        log_origem_id BIGINT NOT NULL,
        registrado_em DATETIME2 NOT NULL CONSTRAINT DF_confirmacao_registrado DEFAULT SYSDATETIME(),
        CONSTRAINT PK_confirmacao_comprovante PRIMARY KEY (chave_nfe, chave_cte),
        CONSTRAINT CK_confirmacao_data CHECK (data_confiavel = 0 OR primeira_confirmacao_em IS NOT NULL)
    );
END;
GO
-- Instalada ANTES da carga: nenhuma confirmação concorrente fica fora da auditoria.
CREATE OR ALTER TRIGGER dbo.tr_log_preserva_confirmacao_comprovante
ON dbo.tb_log_integracao
AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    -- Uma atualização atrasada ou de outra etapa não pode apagar aceite nem sua data.
    IF EXISTS (
        SELECT 1 FROM inserted i JOIN deleted d ON d.id = i.id
        WHERE d.sistema_destino = 'VEDACIT' AND d.status_canhoto = 'SUCESSO'
          AND (ISNULL(i.status_canhoto, '') <> 'SUCESSO'
            OR ISNULL(i.sistema_destino, '') <> 'VEDACIT'
            OR ISNULL(i.chave_nfe, '') <> ISNULL(d.chave_nfe, '')
            OR ISNULL(COALESCE(NULLIF(i.canhoto_chave_cte_efetiva, ''), i.chave_cte), '')
                <> ISNULL(COALESCE(NULLIF(d.canhoto_chave_cte_efetiva, ''), d.chave_cte), '')
            OR i.data_processamento_canhoto <> d.data_processamento_canhoto
            OR (i.data_processamento_canhoto IS NULL AND d.data_processamento_canhoto IS NOT NULL)
            OR (i.data_processamento_canhoto IS NOT NULL AND d.data_processamento_canhoto IS NULL))
    ) THROW 51024, 'CONFIRMACAO_COMPROVANTE_IMUTAVEL: alteracao de aceite historico recusada.', 1;

    ;WITH aceites AS (
        SELECT chave_nfe, COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte) AS chave_cte,
            MIN(data_processamento_canhoto) AS primeira, MAX(data_processamento_canhoto) AS ultima,
            MIN(CASE WHEN data_processamento_canhoto IS NULL THEN 0 ELSE 1 END) AS confiavel,
            MIN(id) AS log_id
        FROM inserted WHERE sistema_destino = 'VEDACIT' AND status_canhoto = 'SUCESSO'
          AND LEN(chave_nfe) = 44
          AND LEN(COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)) = 44
        GROUP BY chave_nfe, COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)
    )
    MERGE dbo.tb_confirmacao_comprovante WITH (HOLDLOCK) AS t
    USING aceites s ON t.chave_nfe = s.chave_nfe AND t.chave_cte = s.chave_cte
    WHEN MATCHED THEN UPDATE SET
        primeira_confirmacao_em = CASE WHEN t.data_confiavel = 1 AND s.primeira < t.primeira_confirmacao_em
            THEN s.primeira ELSE t.primeira_confirmacao_em END,
        ultima_confirmacao_em = CASE WHEN t.ultima_confirmacao_em IS NULL OR s.ultima > t.ultima_confirmacao_em
            THEN s.ultima ELSE t.ultima_confirmacao_em END
    WHEN NOT MATCHED THEN INSERT
        (chave_nfe, chave_cte, primeira_confirmacao_em, ultima_confirmacao_em, data_confiavel, origem, log_origem_id)
        VALUES (s.chave_nfe, s.chave_cte, CASE WHEN s.confiavel = 1 THEN s.primeira END,
            s.ultima, s.confiavel, 'AUDITORIA_ATOMICA', s.log_id);
END;
GO
-- Inclui registros arquivados: arquivar uma fila não revoga um aceite.
;WITH aceites AS (
    SELECT chave_nfe, COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte) AS chave_cte,
        MIN(data_processamento_canhoto) AS primeira, MAX(data_processamento_canhoto) AS ultima,
        MIN(CASE WHEN data_processamento_canhoto IS NULL THEN 0 ELSE 1 END) AS confiavel, MIN(id) AS log_id
    FROM dbo.tb_log_integracao WHERE sistema_destino = 'VEDACIT' AND status_canhoto = 'SUCESSO'
      AND LEN(chave_nfe) = 44 AND LEN(COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)) = 44
    GROUP BY chave_nfe, COALESCE(NULLIF(canhoto_chave_cte_efetiva, ''), chave_cte)
)
MERGE dbo.tb_confirmacao_comprovante WITH (HOLDLOCK) AS t
USING aceites s ON t.chave_nfe = s.chave_nfe AND t.chave_cte = s.chave_cte
WHEN MATCHED THEN UPDATE SET
    primeira_confirmacao_em = CASE WHEN t.data_confiavel=0 OR s.confiavel=0 THEN NULL
        WHEN s.primeira<t.primeira_confirmacao_em THEN s.primeira ELSE t.primeira_confirmacao_em END,
    data_confiavel = CASE WHEN t.data_confiavel=0 OR s.confiavel=0 THEN 0 ELSE 1 END,
    ultima_confirmacao_em = CASE WHEN t.ultima_confirmacao_em IS NULL OR s.ultima>t.ultima_confirmacao_em
        THEN s.ultima ELSE t.ultima_confirmacao_em END
WHEN NOT MATCHED THEN INSERT
    (chave_nfe, chave_cte, primeira_confirmacao_em, ultima_confirmacao_em, data_confiavel, origem, log_origem_id)
    VALUES (s.chave_nfe, s.chave_cte, CASE WHEN s.confiavel = 1 THEN s.primeira END,
        s.ultima, s.confiavel, 'HISTORICO_AUDITORIA', s.log_id);
GO
