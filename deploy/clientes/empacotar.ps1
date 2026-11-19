[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SaidaDosTestes,
    [string]$SaidaDosTestesClientes,
    [string]$Maven,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$raizTestes = (Resolve-Path (Join-Path $repo 'target/unit-tests')).Path
if (!$SaidaDosTestesClientes) {
    $SaidaDosTestesClientes = Get-Content -LiteralPath (Join-Path $raizTestes 'latest-coverage-clientes.txt')
}
function Get-BuildTestado([string]$caminho, [string]$papel) {
    $saida = (Resolve-Path -LiteralPath $caminho).Path
    if ([IO.Path]::GetDirectoryName($saida) -ne $raizTestes) {
        throw 'Informe diretórios produzidos por scripts/testar_com_cobertura.ps1 neste projeto.'
    }
    $resumo = Get-Content -LiteralPath (Join-Path $saida 'summary.json') -Raw | ConvertFrom-Json
    if ($resumo.Output -ne $saida -or $resumo.Passed -lt 1 -or $resumo.ExitCode -ne 0 -or $resumo.Errors -or $resumo.Failures) {
        throw "Compilação sem testes aprovados: $saida"
    }
    if ($resumo.Papel -and $resumo.Papel -ne $papel) { throw "O relatório $saida pertence a outro papel." }
    return $saida
}
$testesApi = Get-BuildTestado $SaidaDosTestes 'api'
$testesClientes = Get-BuildTestado $SaidaDosTestesClientes 'clientes'
$moduloJava = Join-Path $repo 'backend/java'
if (!$Maven) { $Maven = Join-Path $moduloJava 'mvnw.cmd' }
if (!$JavaHome) { $JavaHome = Join-Path $env:LOCALAPPDATA 'Programs/Eclipse Adoptium/jdk-17' }
$javaAnterior = $env:JAVA_HOME
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$destino = Join-Path $PSScriptRoot 'pacotes'
$idVersao = [Guid]::NewGuid().ToString('N')
$versao = Join-Path $destino "versoes/$idVersao"
$preparo = Join-Path $destino ".preparo-$idVersao"
$buildApi = Join-Path $preparo 'api'
$buildClientes = Join-Path $preparo 'clientes'
$ponteiro = Join-Path $destino 'atual.txt'
$ponteiroTemporario = Join-Path $destino "$idVersao.tmp"
$publicada = $false
Push-Location $moduloJava
try {
    # Empacota o bytecode das duas suítes. Nenhuma recompilação ou consulta ao WSDL.
    New-Item -ItemType Directory -Path $buildApi, $buildClientes -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $testesApi 'classes') -Destination $buildApi -Recurse
    & $Maven -o -B -ntp "-Dsatelite.build.directory=$buildApi" 'jar:jar' 'spring-boot:repackage'
    if ($LASTEXITCODE -ne 0) { throw 'Maven falhou no empacotamento da API.' }
    Copy-Item -LiteralPath (Join-Path $testesClientes 'classes') -Destination $buildClientes -Recurse
    & $Maven -o -B -ntp '-Pclientes-worker' "-Dsatelite.build.directory=$buildClientes" 'jar:jar' 'spring-boot:repackage'
    if ($LASTEXITCODE -ne 0) { throw 'Maven falhou no empacotamento do worker de clientes.' }

    New-Item -ItemType Directory -Path $versao -Force | Out-Null
    $api = Join-Path $versao 'satelite-api.jar'
    $clientes = Join-Path $versao 'satelite-clientes.jar'
    Copy-Item -LiteralPath (Join-Path $buildApi 'satelite-0.0.1-SNAPSHOT.jar') -Destination $api
    Copy-Item -LiteralPath (Join-Path $buildClientes 'satelite-0.0.1-SNAPSHOT.jar') -Destination $clientes
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($pacote in @($api, $clientes)) {
        $arquivo = [IO.Compression.ZipFile]::OpenRead($pacote)
        try {
            $prefixo = 'BOOT-INF/classes/com/example/satelite/'
            $temControllerApi = $null -ne $arquivo.GetEntry("${prefixo}controllers/IntegracaoAuditoriaController.class")
            $temMainClientes = $null -ne $arquivo.GetEntry("${prefixo}ClientesApplication.class")
            if ($temControllerApi -ne ($pacote -eq $api) -or $temMainClientes -ne ($pacote -eq $clientes)) {
                throw "Classes incompatíveis com o papel do pacote $pacote."
            }
            if ($pacote -eq $clientes -and ($arquivo.Entries.FullName -like "${prefixo}controllers/*.class")) {
                throw 'O worker inclui controladores HTTP da API.'
            }
            foreach ($item in @('services/etl/ClientesWorker.class','config/ClientesWorkerConfig.class','services/etl/CicloSftpClientes.class')) {
                if ($pacote -eq $clientes -and !$arquivo.GetEntry($prefixo + $item)) { throw "Falta no worker: $item" }
            }
            if (!$arquivo.GetEntry('BOOT-INF/classes/logback-residente.xml')) { throw "Configuração de logs ausente em $pacote" }
        } finally { $arquivo.Dispose() }
    }
    # A seleção troca uma única vez, depois de ambos os arquivos terem sido conferidos.
    [IO.File]::WriteAllText($ponteiroTemporario, $idVersao)
    if ([IO.File]::Exists($ponteiro)) {
        [IO.File]::Replace($ponteiroTemporario, $ponteiro, [NullString]::Value)
    } else {
        try { [IO.File]::Move($ponteiroTemporario, $ponteiro) }
        catch [IO.IOException] {
            if (![IO.File]::Exists($ponteiro)) { throw }
            [IO.File]::Replace($ponteiroTemporario, $ponteiro, [NullString]::Value)
        }
    }
    $publicada = $true
    Get-FileHash -LiteralPath $api, $clientes -Algorithm SHA256
} finally {
    if ([IO.File]::Exists($ponteiroTemporario)) { [IO.File]::Delete($ponteiroTemporario) }
    if (!$publicada -and [IO.Directory]::Exists($versao)) {
        foreach ($nome in @('satelite-api.jar', 'satelite-clientes.jar')) { [IO.File]::Delete((Join-Path $versao $nome)) }
        [IO.Directory]::Delete($versao)
    }
    if ([IO.Directory]::Exists($preparo)) {
        $tempReal = (Resolve-Path -LiteralPath $preparo).Path
        if ([IO.Path]::GetDirectoryName($tempReal) -eq $destino -and
            [IO.Path]::GetFileName($tempReal) -eq ".preparo-$idVersao" -and
            !((Get-Item -LiteralPath $tempReal).Attributes -band [IO.FileAttributes]::ReparsePoint) -and
            !(Get-ChildItem -LiteralPath $tempReal -Recurse -Force -Attributes ReparsePoint | Select-Object -First 1)) {
            Remove-Item -LiteralPath $tempReal -Recurse -Force
        }
    }
    Pop-Location
    $env:JAVA_HOME = $javaAnterior
}
