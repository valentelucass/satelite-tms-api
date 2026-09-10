[CmdletBinding()]
param(
    [string]$Maven = 'mvn.cmd',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Testes = '*'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$saida = Join-Path $repo ('target/unit-tests/coverage-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$envAnterior = @{}
$localAnterior = Get-Location
$jar = Join-Path $repo 'target/satelite-0.0.1-SNAPSHOT.jar'
$hashAntes = if (Test-Path $jar) { (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash } else { $null }
try {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Informe -JavaHome apontando para um JDK 17.' }
    $JavaHome = (Resolve-Path -LiteralPath $JavaHome).ProviderPath
    $java = Join-Path $JavaHome 'bin/java.exe'
    $javac = Join-Path $JavaHome 'bin/javac.exe'
    $versao = (& $java -version 2>&1 | Out-String)
    if ($versao -notmatch 'version "17\.') { throw 'O bloqueio de rede desta suite exige JDK 17.' }
    $mvn = (Get-Command $Maven -ErrorAction Stop).Source
    $agent = Join-Path $env:USERPROFILE '.m2/repository/org/jacoco/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar'
    if (!(Test-Path $agent)) { throw 'JaCoCo 0.8.15 deve estar no cache Maven local antes da execucao offline.' }
    foreach ($key in @((Get-ChildItem Env: | Where-Object { $_.Name -match '^(APP_|DB_|SATELITE_|SFTP_|VEDACIT_|SELIA_|SUPPORTE_|PPG_|RODOGARCIA_|ESL_|WORK_|SPRING_|INTEGRATION_|RETROACTIVE_|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|JDK_JAVA_OPTIONS$|MAVEN_OPTS$|JAVA_HOME$)' }).Name)) {
        $envAnterior[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $null, 'Process')
    }
    if (!$envAnterior.ContainsKey('JAVA_HOME')) { $envAnterior['JAVA_HOME'] = $null }
    $env:JAVA_HOME = $JavaHome
    Set-Location $repo
    New-Item -ItemType Directory -Path "$saida/work", "$saida/safety", "$saida/generated-sources" -Force | Out-Null
    foreach ($nome in @('wsimport', 'wsimport-nfe', 'wsimport-cte')) {
        $origem = Join-Path $repo "target/generated-sources/$nome"
        if (!(Test-Path $origem)) { throw "Fontes SOAP locais ausentes: $nome. Este script nao baixa nem gera contratos remotos." }
        Copy-Item -LiteralPath $origem -Destination "$saida/generated-sources/$nome" -Recurse
    }
    $guard = @'
import java.net.InetAddress;
import java.security.Permission;
@SuppressWarnings("removal")
public final class NoNetworkSecurityManager extends SecurityManager {
    public NoNetworkSecurityManager() { System.err.println("[UNIT_NETWORK_GUARD] active"); }
    public void checkPermission(Permission permission) {}
    public void checkPermission(Permission permission, Object context) {}
    private void deny(String operation) { throw new SecurityException("[UNIT_NETWORK_BLOCKED] " + operation); }
    public void checkConnect(String host, int port) { deny("connect"); }
    public void checkConnect(String host, int port, Object context) { deny("connect"); }
    public void checkListen(int port) { deny("listen"); }
    public void checkAccept(String host, int port) { deny("accept"); }
    public void checkMulticast(InetAddress address) { deny("multicast"); }
}
'@
    [IO.File]::WriteAllText("$saida/safety/NoNetworkSecurityManager.java", $guard)
    & $javac -d "$saida/safety" "$saida/safety/NoNetworkSecurityManager.java"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar bloqueio de rede.' }
    # POM temporario: nao modifica o pom.xml do repositorio nem o JAR operacional.
    [xml]$pom = Get-Content (Join-Path $repo 'pom.xml') -Raw
    $ns = $pom.DocumentElement.NamespaceURI
    function Add-Element($parent, [string]$name, [string]$value) {
        $node = $pom.CreateElement($name, $ns)
        if ($null -ne $value) { $node.InnerText = $value }
        [void]$parent.AppendChild($node)
        return $node
    }
    $build = $pom.project.build
    Add-Element $build 'sourceDirectory' (Join-Path $repo 'src/main/java') | Out-Null
    Add-Element $build 'testSourceDirectory' (Join-Path $repo 'src/test/java') | Out-Null
    $surefire = Add-Element $build.plugins 'plugin' $null
    Add-Element $surefire 'groupId' 'org.apache.maven.plugins' | Out-Null
    Add-Element $surefire 'artifactId' 'maven-surefire-plugin' | Out-Null
    Add-Element $surefire 'version' '3.5.6' | Out-Null
    $conf = Add-Element $surefire 'configuration' $null
    Add-Element $conf 'workingDirectory' "$saida/work" | Out-Null
    Add-Element $conf 'argLine' ('-Xbootclasspath/a:"' + $saida + '/safety" -Djava.security.manager=NoNetworkSecurityManager -Djava.awt.headless=true -javaagent:"' + $agent + '"=destfile=' + $saida + '/jacoco.exec,append=false') | Out-Null
    Add-Element $conf 'forkCount' '1' | Out-Null
    Add-Element $conf 'reuseForks' 'true' | Out-Null
    Add-Element $conf 'forkedProcessTimeoutInSeconds' '240' | Out-Null
    Add-Element $conf 'redirectTestOutputToFile' 'true' | Out-Null
    Add-Element $conf 'reportsDirectory' "$saida/surefire-reports" | Out-Null
    $sys = Add-Element $conf 'systemPropertyVariables' $null
    Add-Element $sys 'spring.config.import' 'optional:classpath:unit-tests-no-import.properties' | Out-Null
    foreach ($name in @('APP_SCHEDULER_ENABLED','APP_NIGHTLY_RETRY_ENABLED','APP_CICLO_UNICO','APP_PPG_ENABLED','APP_SELIA_ENABLED','APP_VEDACIT_ENABLED','APP_SUPPORTE_ENABLED','work.sftp-clientes.enabled','SFTP_RODOGARCIA_ENABLED')) { Add-Element $sys $name 'false' | Out-Null }
    Add-Element $sys 'APP_DASHBOARD_API_ONLY' 'true' | Out-Null
    Add-Element $sys 'junit.jupiter.execution.timeout.default' '30s' | Out-Null
    $jacoco = Add-Element $build.plugins 'plugin' $null
    Add-Element $jacoco 'groupId' 'org.jacoco' | Out-Null
    Add-Element $jacoco 'artifactId' 'jacoco-maven-plugin' | Out-Null
    Add-Element $jacoco 'version' '0.8.15' | Out-Null
    $jc = Add-Element $jacoco 'configuration' $null
    Add-Element $jc 'dataFile' "$saida/jacoco.exec" | Out-Null
    Add-Element $jc 'outputDirectory' "$saida/coverage-own" | Out-Null
    $includes = Add-Element $jc 'includes' $null
    Add-Element $includes 'include' 'com/example/satelite/**' | Out-Null
    $excludes = Add-Element $jc 'excludes' $null
    Add-Element $excludes 'exclude' 'com/example/satelite/vedacit/**' | Out-Null
    $pom.Save("$saida/test-pom.xml")
    # Goals diretos evitam initialize/clean e wsimport remoto ligado ao lifecycle.
    & $mvn -o -B -ntp "-Dsatelite.build.directory=$saida" resources:resources build-helper:add-source@add-wsimport-generated-sources compiler:compile resources:testResources compiler:testCompile *> "$saida/compile.log"
    if ($LASTEXITCODE -ne 0) { throw "Compilacao falhou; consulte $saida/compile.log" }
    & $mvn -o -B -ntp -f "$saida/test-pom.xml" "-Dsatelite.build.directory=$saida" "-Dtest=$Testes" surefire:test *> "$saida/tests.log"
    $testExit = $LASTEXITCODE
    & $mvn -o -B -ntp -f "$saida/test-pom.xml" "-Dsatelite.build.directory=$saida" org.jacoco:jacoco-maven-plugin:0.8.15:report *> "$saida/coverage.log"
    if ($LASTEXITCODE -ne 0) { throw "Relatorio falhou; consulte $saida/coverage.log" }
    $guardAtivo = Select-String -LiteralPath "$saida/tests.log" -SimpleMatch '[UNIT_NETWORK_GUARD] active' -Quiet
    if (!$guardAtivo) { throw 'Execucao nao comprovou o bloqueio de rede na JVM de teste.' }
    $total=0; $falhas=0; $erros=0; $ignorados=0
    Get-ChildItem "$saida/surefire-reports/TEST-*.xml" | ForEach-Object {
        [xml]$r=Get-Content $_.FullName -Raw
        $total += [int]$r.testsuite.tests; $falhas += [int]$r.testsuite.failures; $erros += [int]$r.testsuite.errors; $ignorados += [int]$r.testsuite.skipped
    }
    [xml]$coverage=Get-Content "$saida/coverage-own/jacoco.xml" -Raw
    $counters=@($coverage.SelectNodes('/report/counter') | ForEach-Object {
        $covered=[int]$_.GetAttribute('covered'); $missed=[int]$_.GetAttribute('missed')
        [pscustomobject]@{Type=$_.GetAttribute('type');Covered=$covered;Missed=$missed;Percent=if($covered+$missed){[math]::Round(100*$covered/($covered+$missed),2)}else{100}}
    })
    $summary=[pscustomobject]@{Tests=$total;Passed=$total-$falhas-$erros-$ignorados;Failures=$falhas;Errors=$erros;Skipped=$ignorados;ExitCode=$testExit;Coverage=$counters;Output=$saida;SelectedTests=$Testes}
    $summary | ConvertTo-Json -Depth 5 | Set-Content "$saida/summary.json"
    Set-Content (Join-Path $repo 'target/unit-tests/latest-coverage.txt') $saida
    $summary | ConvertTo-Json -Depth 5
    if ($testExit -ne 0 -or $falhas -gt 0 -or $erros -gt 0) { throw "Suite reprovada: consulte $saida/tests.log" }
} finally {
    foreach ($key in $envAnterior.Keys) { [Environment]::SetEnvironmentVariable($key, $envAnterior[$key], 'Process') }
    Set-Location $localAnterior
    if ($hashAntes -and (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $hashAntes) { throw 'O hash do JAR operacional mudou durante a validacao.' }
}
