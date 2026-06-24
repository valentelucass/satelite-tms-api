:ON ERROR EXIT

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF DB_ID(N'$(DatabaseName)') IS NULL
BEGIN
    DECLARE @createDatabaseSql NVARCHAR(MAX) = N'CREATE DATABASE ' + QUOTENAME(N'$(DatabaseName)');
    EXEC sys.sp_executesql @createDatabaseSql;
END;
GO
