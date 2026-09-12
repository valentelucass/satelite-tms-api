[CmdletBinding()]
param([Parameter(Mandatory)][string]$Candidato, [Parameter(Mandatory)][string]$Frontend, [switch]$Conferir)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$candidato=(Resolve-Path -LiteralPath $Candidato).Path
$compilacao=Split-Path $candidato
$summary=Get-Content (Join-Path $compilacao 'summary.json') -Raw | ConvertFrom-Json
if($summary.ExitCode -ne 0 -or $summary.Failures -ne 0 -or $summary.Errors -ne 0 -or $summary.Passed -lt 385) { throw 'TESTS_NOT_APPROVED' }
$front=(Resolve-Path -LiteralPath $Frontend).Path
$dash=[IO.Path]::GetFullPath((Join-Path $repo '../etl-dash/dashboards'))
$destino=Join-Path $repo 'target/satelite-0.0.1-SNAPSHOT.jar'
$uiDestino=Join-Path $dash 'frontend/dist-prod'
$out=Join-Path $repo 'target/correcao-idempotencia-20260911'
$hashNovo=(Get-FileHash -LiteralPath $candidato -Algorithm SHA256).Hash
$hashAnterior=(Get-FileHash -LiteralPath $destino -Algorithm SHA256).Hash
$zip=[IO.Compression.ZipFile]::OpenRead($candidato)
$conferidos=0
try {
    if(!$zip.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class')) { throw 'JAR_NOT_EXECUTABLE' }
    if(@($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/h2-*.jar' }).Count) { throw 'TEST_DEPENDENCY_IN_JAR' }
    foreach($entry in $zip.Entries) {
        if(!$entry.FullName.StartsWith('BOOT-INF/classes/') -or $entry.FullName.EndsWith('/')) { continue }
        $relative=$entry.FullName.Substring('BOOT-INF/classes/'.Length)
        $stream=$entry.Open(); $sha=[Security.Cryptography.SHA256]::Create()
        try { $hash=[Convert]::ToHexString($sha.ComputeHash($stream)) } finally { $stream.Dispose();$sha.Dispose() }
        if($hash -ne (Get-FileHash -LiteralPath (Join-Path $compilacao ('classes/'+$relative))).Hash) { throw ('UNTESTED_JAR_ENTRY '+$relative) }
        $conferidos++
    }
} finally { $zip.Dispose() }
$visual=Get-Content (Join-Path $dash 'frontend/.tmp/confirmacoes-v2-visual/summary.json') -Raw | ConvertFrom-Json
if(@($visual.checks).Count -ne 8 -or @($visual.errors).Count -ne 0) { throw 'VISUAL_NOT_APPROVED' }
$build=Get-Content (Join-Path $front 'build-info.json') -Raw | ConvertFrom-Json
if($build.buildId -ne '20260911-confirmacoes-v2') { throw 'UNEXPECTED_UI_BUILD' }
if((Get-Content (Join-Path $front 'index.html') -Raw) -notmatch '20260911-confirmacoes-v2') { throw 'UI_INDEX_MISMATCH' }
if(@(Get-ChildItem -LiteralPath $front -Recurse -File -Filter '*.map').Count) { throw 'UI_SOURCE_MAPS' }
$evidence=[ordered]@{candidato=$candidato;sha256=$hashNovo;sha256Anterior=$hashAnterior;arquivosConferidos=$conferidos;testes=$summary.Passed;frontend=$front;buildId=$build.buildId;data=[DateTimeOffset]::Now.ToString('o')}
if($Conferir) { $evidence | ConvertTo-Json; exit 0 }
$ativos=@(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'satelite-0\.0\.1-SNAPSHOT\.jar|satelite-sftp-monitor\.jar' })
if($ativos.Count) { throw ('SATELITE_STILL_RUNNING PIDs='+($ativos.ProcessId -join ',')) }
$ultimoManifesto=Join-Path $out 'instalacao.json'
$hashUltimaInstalacao=if(Test-Path $ultimoManifesto) { (Get-Content $ultimoManifesto -Raw | ConvertFrom-Json).sha256 } else { $null }
if($hashAnterior -ne '4FD9D952C4982106B3CB27A717833F117FC5B095B7336E9D47307E22C7BE4A99' -and $hashAnterior -ne $hashNovo -and $hashAnterior -ne $hashUltimaInstalacao) { throw 'OPERATIONAL_JAR_CHANGED' }
if(!(Test-Path (Join-Path $out 'backup-ui'))) { Copy-Item -LiteralPath $uiDestino -Destination (Join-Path $out 'backup-ui') -Recurse }
if($hashAnterior -ne $hashNovo) {
    $preparado=Join-Path $out 'satelite-novo.jar'
    Copy-Item -LiteralPath $candidato -Destination $preparado
    if((Get-FileHash -LiteralPath $preparado).Hash -ne $hashNovo) { throw 'COPY_HASH_MISMATCH' }
    [IO.File]::Replace($preparado,$destino,(Join-Path $out ('backup-instalacao-'+[guid]::NewGuid().ToString('N')+'.jar')))
}
# Preserva assets antigos para abas já abertas; ativa o HTML apenas depois de copiar os novos.
foreach($file in Get-ChildItem -LiteralPath $front -Recurse -File) {
    $relative=[IO.Path]::GetRelativePath($front,$file.FullName)
    if($relative -in @('index.html','build-info.json')) { continue }
    $target=Join-Path $uiDestino $relative
    New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target -Force
}
foreach($name in @('build-info.json','index.html')) {
    $target=Join-Path $uiDestino $name
    $staged=Join-Path $uiDestino ($name+'.preparado')
    Copy-Item -LiteralPath (Join-Path $front $name) -Destination $staged -Force
    if(Test-Path $target) { [IO.File]::Replace($staged,$target,(Join-Path $out ($name+'.anterior'))) }
    else { [IO.File]::Move($staged,$target) }
}
if((Get-FileHash -LiteralPath $destino).Hash -ne $hashNovo) { throw 'INSTALLED_HASH_MISMATCH' }
foreach($file in Get-ChildItem -LiteralPath $front -Recurse -File) {
    $target=Join-Path $uiDestino ([IO.Path]::GetRelativePath($front,$file.FullName))
    if((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $target).Hash) { throw 'INSTALLED_UI_HASH_MISMATCH' }
}
$evidence['instalado']=$true; $evidence['processosIniciados']=$false
if(Test-Path $ultimoManifesto) { Copy-Item -LiteralPath $ultimoManifesto -Destination (Join-Path $out ('instalacao-anterior-'+[guid]::NewGuid().ToString('N')+'.json')) }
$evidence | ConvertTo-Json | Set-Content (Join-Path $out 'instalacao.json') -Encoding utf8
$evidence | ConvertTo-Json
