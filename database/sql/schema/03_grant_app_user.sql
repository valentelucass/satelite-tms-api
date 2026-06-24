:ON ERROR EXIT

USE [$(DatabaseName)];
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

DECLARE @appUser SYSNAME = NULLIF(N'$(AppUser)', N'');

IF @appUser IS NULL
BEGIN
    PRINT N'AppUser nao informado. Grants ignorados.';
END
ELSE IF SUSER_ID(@appUser) IS NULL
BEGIN
    PRINT N'Login de aplicacao nao existe no SQL Server. Grants ignorados: ' + @appUser;
END
ELSE
BEGIN
    DECLARE @sql NVARCHAR(MAX);

    IF USER_ID(@appUser) IS NULL
    BEGIN
        SET @sql = N'CREATE USER ' + QUOTENAME(@appUser) + N' FOR LOGIN ' + QUOTENAME(@appUser) + N';';
        EXEC sys.sp_executesql @sql;
    END;

    IF IS_ROLEMEMBER(N'db_datareader', @appUser) <> 1
    BEGIN
        SET @sql = N'ALTER ROLE db_datareader ADD MEMBER ' + QUOTENAME(@appUser) + N';';
        EXEC sys.sp_executesql @sql;
    END;

    IF IS_ROLEMEMBER(N'db_datawriter', @appUser) <> 1
    BEGIN
        SET @sql = N'ALTER ROLE db_datawriter ADD MEMBER ' + QUOTENAME(@appUser) + N';';
        EXEC sys.sp_executesql @sql;
    END;

    SET @sql = N'GRANT SELECT, INSERT, UPDATE ON dbo.tb_log_integracao TO ' + QUOTENAME(@appUser) + N';';
    EXEC sys.sp_executesql @sql;

    IF OBJECT_ID(N'dbo.tb_controle_cursor', N'U') IS NOT NULL
    BEGIN
        SET @sql = N'GRANT SELECT, INSERT, UPDATE ON dbo.tb_controle_cursor TO ' + QUOTENAME(@appUser) + N';';
        EXEC sys.sp_executesql @sql;
    END;

    PRINT N'Permissoes aplicadas para o usuario de aplicacao: ' + @appUser;
END;
GO
