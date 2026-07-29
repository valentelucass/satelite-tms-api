param(
    [string]$LogsDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'logs'),
    [int]$MaxFiles = 20,
    [int]$MaxAgeDays = 30,
    [int]$MaxTotalMb = 500,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

if ($MaxFiles -lt 1 -or $MaxAgeDays -lt 1 -or $MaxTotalMb -lt 1) {
    throw 'Os limites de retencao devem ser maiores que zero.'
}

$resolvedLogsDirectory = [System.IO.Path]::GetFullPath($LogsDirectory)
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
$projectRootPrefix = $projectRoot + [System.IO.Path]::DirectorySeparatorChar
if ($resolvedLogsDirectory -ne $projectRoot -and -not $resolvedLogsDirectory.StartsWith($projectRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'A limpeza só pode atuar dentro do projeto Satélite.'
}

if (-not (Test-Path -LiteralPath $resolvedLogsDirectory -PathType Container)) {
    Write-Output "Nenhuma pasta de logs encontrada: $resolvedLogsDirectory"
    exit 0
}

$cutoff = (Get-Date).AddDays(-$MaxAgeDays)
$files = Get-ChildItem -LiteralPath $resolvedLogsDirectory -File -Recurse |
    Where-Object { $_.Extension -in '.log', '.out', '.err' } |
    Sort-Object LastWriteTime, FullName

$selectedForRemoval = @{}
foreach ($file in ($files | Where-Object { $_.LastWriteTime -lt $cutoff })) {
    $selectedForRemoval[$file.FullName] = $true
}

$retained = $files | Where-Object { -not $selectedForRemoval.ContainsKey($_.FullName) } |
    Sort-Object -Property LastWriteTime, FullName -Descending

if ($retained.Count -gt $MaxFiles) {
    $retained | Select-Object -Skip $MaxFiles | ForEach-Object { $selectedForRemoval[$_.FullName] = $true }
}

$retained = $files | Where-Object { -not $selectedForRemoval.ContainsKey($_.FullName) } |
    Sort-Object -Property LastWriteTime, FullName -Descending
$maxTotalBytes = [int64]$MaxTotalMb * 1MB
$currentBytes = [int64](($retained | Measure-Object -Property Length -Sum).Sum)
if ($currentBytes -gt $maxTotalBytes) {
    $retained | Sort-Object -Property LastWriteTime, FullName | ForEach-Object {
        if ($currentBytes -le $maxTotalBytes) {
            return
        }
        $selectedForRemoval[$_.FullName] = $true
        $currentBytes -= $_.Length
    }
}

$uniqueRemoval = $files | Where-Object { $selectedForRemoval.ContainsKey($_.FullName) } |
    Sort-Object -Property FullName
if ($uniqueRemoval.Count -eq 0) {
    Write-Output "Retencao OK: $($files.Count) arquivo(s), $([math]::Round((($files | Measure-Object Length -Sum).Sum / 1MB), 2)) MB."
}

foreach ($file in $uniqueRemoval) {
    if ($WhatIf) {
        Write-Output "[SIMULACAO] Remover: $($file.FullName)"
        continue
    }

    try {
        Remove-Item -LiteralPath $file.FullName -Force
        Write-Output "Removido: $($file.FullName)"
    } catch {
        Write-Warning "Nao foi possivel remover $($file.FullName): $($_.Exception.Message)"
    }
}

$emptyDirectories = Get-ChildItem -LiteralPath $resolvedLogsDirectory -Directory -Recurse |
    Sort-Object -Property @{ Expression = { $_.FullName.Length }; Descending = $true }
foreach ($directory in $emptyDirectories) {
    if ((Get-ChildItem -LiteralPath $directory.FullName -Force | Measure-Object).Count -ne 0) {
        continue
    }
    if ($WhatIf) {
        Write-Output "[SIMULACAO] Remover diretorio vazio: $($directory.FullName)"
        continue
    }
    try {
        Remove-Item -LiteralPath $directory.FullName -Force
        Write-Output "Diretorio vazio removido: $($directory.FullName)"
    } catch {
        Write-Warning "Nao foi possivel remover diretorio vazio $($directory.FullName): $($_.Exception.Message)"
    }
}
