:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.tb_controle_cursor', N'U') IS NOT NULL
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sys.columns c
        JOIN sys.types t ON c.user_type_id = t.user_type_id
        WHERE c.object_id = OBJECT_ID(N'dbo.tb_controle_cursor')
          AND c.name = N'cursor_next_id'
          AND t.name IN (N'varchar', N'nvarchar', N'char', N'nchar')
    )
    BEGIN
        IF EXISTS (
            SELECT 1
            FROM dbo.tb_controle_cursor
            WHERE cursor_next_id IS NOT NULL
              AND TRY_CONVERT(BIGINT, cursor_next_id) IS NULL
        )
        BEGIN
            THROW 51000, 'tb_controle_cursor.cursor_next_id possui valores nao numericos; corrija antes de converter para BIGINT.', 1;
        END;

        ALTER TABLE dbo.tb_controle_cursor ALTER COLUMN cursor_next_id BIGINT NULL;
    END;
END;
GO
