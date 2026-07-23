:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'intelipost_pre_shipment_list') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD intelipost_pre_shipment_list BIGINT NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'logistics_provider_shipment_list') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD logistics_provider_shipment_list BIGINT NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'order_number') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD order_number VARCHAR(100) NULL;
GO

IF COL_LENGTH('dbo.tb_log_integracao', 'volume_number') IS NULL
    ALTER TABLE dbo.tb_log_integracao ADD volume_number VARCHAR(100) NULL;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_selia_plp_lista'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_selia_plp_lista
    ON dbo.tb_log_integracao (sistema_destino, intelipost_pre_shipment_list, data_processamento ASC, id ASC)
    INCLUDE (logistics_provider_shipment_list, status);
END;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE name = N'IX_tb_log_integracao_selia_plp_nfe'
      AND object_id = OBJECT_ID(N'dbo.tb_log_integracao')
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_tb_log_integracao_selia_plp_nfe
    ON dbo.tb_log_integracao (sistema_destino, chave_nfe, status, data_processamento DESC, id DESC)
    INCLUDE (intelipost_pre_shipment_list, order_number, volume_number);
END;
GO
