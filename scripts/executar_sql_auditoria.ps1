[CmdletBinding()]
param([Parameter(Mandatory)][string[]]$Arquivos, [switch]$Rollback, [switch]$Integrated, [string]$Saida,
    [int]$ExpectedSqlError=0, [switch]$Silencioso)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$config = @{}
foreach ($line in [IO.File]::ReadLines((Join-Path $repo '.env'))) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $config[$Matches[1]]=$Matches[2].Trim().Trim('"').Trim("'") }
}
$url=$config['SATELITE_DB_URL']; if (!$url) { $url=$config['DB_URL'] }
if ($url -notmatch '(?i)^jdbc:sqlserver://([^;]+);.*databaseName=SATELITE_TMS_AUDITORIA(?:;|$)') { throw 'DATABASE_OUT_OF_SCOPE' }
$builder=[Data.SqlClient.SqlConnectionStringBuilder]::new()
$builder['Data Source']=$Matches[1] -replace ':(\d+)$', ',$1'
$builder['Initial Catalog']='SATELITE_TMS_AUDITORIA'
$builder['User ID']=if($config['SATELITE_DB_USER']){$config['SATELITE_DB_USER']}else{$config['DB_USER']}
$builder['Password']=if($config['SATELITE_DB_PASSWORD']){$config['SATELITE_DB_PASSWORD']}else{$config['DB_PASSWORD']}
if ($Integrated) { $builder.Remove('User ID') | Out-Null; $builder.Remove('Password') | Out-Null; $builder['Integrated Security']=$true }
$builder['Encrypt']=$true; $builder['TrustServerCertificate']=$true; $builder['Connect Timeout']=10
$builder['Application Name']='CorrecaoAuditadaComprovantes'
$conn=[Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
$resultados=[Collections.Generic.List[object]]::new()
try {
    $conn.Open()
    if ($conn.Database -ine 'SATELITE_TMS_AUDITORIA') { throw 'DATABASE_OUT_OF_SCOPE' }
    $tx=$conn.BeginTransaction()
    try {
        foreach($arquivo in $Arquivos) {
            $path=(Resolve-Path -LiteralPath $arquivo).Path
            $sql=[IO.File]::ReadAllText($path)
            if ($sql -notmatch '(?is)^\s*USE\s+SATELITE_TMS_AUDITORIA;\s*SET ANSI_NULLS ON;\s*SET QUOTED_IDENTIFIER ON;') { throw 'SQL_HEADER_REQUIRED' }
            foreach($batch in ($sql -split '(?im)^\s*GO\s*\r?$')) {
                if (!$batch.Trim()) { continue }
                $cmd=$conn.CreateCommand(); $cmd.Transaction=$tx; $cmd.CommandTimeout=120
                $cmd.CommandText="SET LOCK_TIMEOUT 10000;`n"+$batch
                # CREATE TRIGGER exige ser o primeiro comando do lote.
                if ($batch -match '(?is)CREATE OR ALTER TRIGGER') { $cmd.CommandText=$batch }
                $reader=$cmd.ExecuteReader()
                try {
                    do {
                        while($reader.Read()) {
                            $row=[ordered]@{}
                            for($c=0;$c -lt $reader.FieldCount;$c++) { $row[$reader.GetName($c)]=if($reader.IsDBNull($c)){$null}else{$reader.GetValue($c)} }
                            $resultados.Add([pscustomobject]$row)
                        }
                    } while($reader.NextResult())
                } finally { $reader.Dispose(); $cmd.Dispose() }
            }
        }
        if ($Rollback) { $tx.Rollback() } else { $tx.Commit() }
    } catch { try { $tx.Rollback() } catch {} ; throw }
    finally { $tx.Dispose() }
    if ($Saida) { $resultados.ToArray() | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Saida -Encoding utf8 }
    Write-Output ('SQL_OK rollback='+$Rollback.IsPresent+' resultados='+$resultados.Count)
    if ($ExpectedSqlError) { throw 'SQL_EXPECTED_ERROR_NOT_RAISED' }
    if (!$Silencioso) { $resultados.ToArray() | Format-Table -AutoSize | Out-String -Width 180 | Write-Output }
} catch [Data.SqlClient.SqlException] {
    if ($Rollback -and $ExpectedSqlError -and $_.Exception.Number -eq $ExpectedSqlError) { Write-Output ('SQL_EXPECTED_ERROR_OK code='+$ExpectedSqlError+' rollback=True') }
    else { throw ('SQL_FAILED code='+$_.Exception.Number+' message='+$_.Exception.Message) }
}
finally { $conn.Dispose() }
