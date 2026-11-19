SET NOCOUNT ON;
-- A consulta sob teste recebe exclusivamente o nome temporário desta sessão.
CREATE TABLE #plp_satelite (
    id bigint IDENTITY PRIMARY KEY,
    intelipost_pre_shipment_list bigint NOT NULL, logistics_provider_shipment_list bigint,
    sistema_destino varchar(20) NOT NULL, status varchar(50) NOT NULL, status_dados varchar(50),
    status_canhoto varchar(50), tentativas_dados int NOT NULL, tentativas_canhoto int NOT NULL,
    data_processamento datetime2(3) NOT NULL, arquivado bit NOT NULL,
    chave_nfe varchar(44), order_number varchar(100), volume_number varchar(100),
    CHECK (order_number <> 'FALHAR')
);
DECLARE @sql nvarchar(max) = CONVERT(nvarchar(max), ?);
DECLARE @mapas nvarchar(max) = CONVERT(nvarchar(max), ?);
DECLARE @alterados nvarchar(max) = CONVERT(nvarchar(max), ?);
DECLARE @falha nvarchar(max) = CONVERT(nvarchar(max), ?);
DECLARE @lista bigint = ABS(CONVERT(bigint, CHECKSUM(NEWID()))) + 4611686018427387904;
DECLARE @assinatura nvarchar(256) =
    N'@lista bigint, @mapas nvarchar(max), @recibo bigint OUTPUT, @data datetime2(7) OUTPUT';
DECLARE @recibo bigint, @original bigint, @data datetime2(7), @primeira datetime2(7);
EXEC sys.sp_executesql @sql, @assinatura, @lista, @mapas, @recibo OUTPUT, @data OUTPUT;
SELECT @original=@recibo, @primeira=@data, @recibo=NULL, @data=NULL;
EXEC sys.sp_executesql @sql, @assinatura, @lista, @alterados, @recibo OUTPUT, @data OUTPUT;
IF @recibo IS NULL OR @recibo <> @original OR @data <> CAST(@primeira AS datetime2(3))
    THROW 51100, 'Reenvio devolveu outro recibo ou data.', 1;
IF (SELECT COUNT(*) FROM #plp_satelite WHERE intelipost_pre_shipment_list=@lista) <> 3
    THROW 51100, 'Quantidade de registros incorreta.', 1;
IF (SELECT STRING_AGG(order_number + '/' + volume_number, ',') WITHIN GROUP (ORDER BY id)
    FROM #plp_satelite WHERE sistema_destino='SELIA_PLP_MAP') COLLATE Latin1_General_100_BIN2 <> N'Ação/B,P2/A'
    THROW 51100, 'O reenvio alterou os vinculos da primeira lista.', 1;
IF EXISTS (SELECT 1 FROM #plp_satelite WHERE logistics_provider_shipment_list <> @original
           OR status <> 'ACEITO_PLP' OR status_dados <> 'SUCESSO' OR status_canhoto <> 'NAO_APLICAVEL'
           OR arquivado <> 0 OR tentativas_dados <> 0 OR tentativas_canhoto <> 0)
    THROW 51100, 'Colunas de auditoria divergentes.', 1;

SET @lista += 1;
DECLARE @rejeitado bit = 0;
BEGIN TRY
    EXEC sys.sp_executesql @sql, @assinatura, @lista, @falha, @recibo OUTPUT, @data OUTPUT;
END TRY
BEGIN CATCH
    IF ERROR_NUMBER() <> 547 THROW;
    SET @rejeitado = 1;
END CATCH;
IF @rejeitado = 0 OR @@TRANCOUNT <> 0 OR EXISTS (SELECT 1 FROM #plp_satelite WHERE intelipost_pre_shipment_list=@lista)
    THROW 51100, 'Falha parcial: cabecalho ou vinculos foram gravados.', 1;
EXEC sys.sp_executesql @sql, @assinatura, @lista, @mapas, @recibo OUTPUT, @data OUTPUT;
IF (SELECT COUNT(*) FROM #plp_satelite WHERE intelipost_pre_shipment_list=@lista) <> 3
    THROW 51100, 'Nova tentativa depois da falha incompleta.', 1;
-- A API Java rejeita o cabeçalho legado incompleto e mantém os dados anteriores.
UPDATE #plp_satelite SET logistics_provider_shipment_list=NULL
WHERE intelipost_pre_shipment_list=@lista AND sistema_destino='SELIA_PLP';
EXEC sys.sp_executesql @sql, @assinatura, @lista, @mapas, @recibo OUTPUT, @data OUTPUT;
IF @recibo IS NOT NULL OR (SELECT COUNT(*) FROM #plp_satelite WHERE intelipost_pre_shipment_list=@lista) <> 3
    THROW 51100, 'Cabecalho incompleto recebeu outro identificador.', 1;
DROP TABLE #plp_satelite;
SELECT CAST(1 AS bit) AS verificado FOR JSON PATH, WITHOUT_ARRAY_WRAPPER;
