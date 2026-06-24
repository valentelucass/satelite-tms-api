:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.tb_controle_cursor', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.tb_controle_cursor (
        id INT IDENTITY(1,1) PRIMARY KEY,
        sistema_destino VARCHAR(50) NOT NULL UNIQUE,
        cursor_next_id BIGINT NULL,
        data_atualizacao DATETIME DEFAULT GETDATE()
    );
END;
GO
