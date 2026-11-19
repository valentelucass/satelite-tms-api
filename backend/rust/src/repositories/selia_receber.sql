SET NOCOUNT ON;
SET XACT_ABORT ON;
DECLARE @cabecalho bigint, @lock_result int;
DECLARE @recurso nvarchar(255) = CONCAT(N'Satelite:SELIA_PLP:', @lista);

BEGIN TRY
    BEGIN TRANSACTION;
    EXEC @lock_result = sys.sp_getapplock
        @Resource = @recurso, @LockMode = 'Exclusive',
        @LockOwner = 'Transaction', @LockTimeout = 12000;
    IF @lock_result < 0 THROW 51000, 'Timeout na recepcao SELIA.', 1;

    SELECT TOP (1)
        @cabecalho = id, @recibo = logistics_provider_shipment_list, @data = data_processamento
    FROM dbo.tb_log_integracao WITH (UPDLOCK, HOLDLOCK)
    WHERE sistema_destino = 'SELIA_PLP' AND intelipost_pre_shipment_list = @lista
    ORDER BY data_processamento, id;

    IF @cabecalho IS NULL
    BEGIN
        -- O Java consulta SYSDATETIME() e grava o DATETIME2 na auditoria.
        SET @data = SYSDATETIME();
        DECLARE @inserido TABLE (id bigint NOT NULL);
        INSERT INTO dbo.tb_log_integracao
            (intelipost_pre_shipment_list, sistema_destino, status, status_dados,
             status_canhoto, tentativas_dados, tentativas_canhoto, data_processamento, arquivado)
        OUTPUT inserted.id INTO @inserido
        VALUES (@lista, 'SELIA_PLP', 'ACEITO_PLP', 'SUCESSO', 'NAO_APLICAVEL', 0, 0, @data, 0);
        SELECT @recibo = id FROM @inserido;
        UPDATE dbo.tb_log_integracao SET logistics_provider_shipment_list = @recibo WHERE id = @recibo;

        INSERT INTO dbo.tb_log_integracao
            (intelipost_pre_shipment_list, logistics_provider_shipment_list, chave_nfe,
             order_number, volume_number, sistema_destino, status, status_dados,
             status_canhoto, tentativas_dados, tentativas_canhoto, data_processamento, arquivado)
        SELECT @lista, @recibo, m.chave, m.pedido, m.volume, 'SELIA_PLP_MAP',
               'ACEITO_PLP', 'SUCESSO', 'NAO_APLICAVEL', 0, 0, @data, 0
        FROM OPENJSON(@mapas) j CROSS APPLY OPENJSON(j.value) WITH
            (chave varchar(44) '$.invoice_key', pedido nvarchar(100) '$.order_number',
             volume nvarchar(100) '$.volume_number') m
        ORDER BY CONVERT(int, j.[key]);
    END;
    -- O Java devolve o recibo original mesmo quando o pedido do reenvio mudou.
    SET @data = COALESCE(@data, SYSDATETIME());
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
