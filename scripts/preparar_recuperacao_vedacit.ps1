[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlanilhaXml,
    [Parameter(Mandatory)][string]$PlanilhaComprovantes,
    [Parameter(Mandatory)][string]$AuditoriaJson,
    [Parameter(Mandatory)][string]$DiretorioSaida
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Ler-XmlEntrada($zip, [string]$nome) {
    $entrada = $zip.GetEntry($nome)
    if (!$entrada) { return $null }
    $config = [Xml.XmlReaderSettings]::new()
    $config.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $config.XmlResolver = $null
    $fluxo = $entrada.Open()
    $leitor = [Xml.XmlReader]::Create($fluxo, $config)
    try { $doc = [Xml.XmlDocument]::new(); $doc.XmlResolver = $null; $doc.Load($leitor); return ,$doc }
    finally { $leitor.Dispose(); $fluxo.Dispose() }
}
function Ler-Linhas([string]$arquivo) {
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $arquivo).ProviderPath)
    try {
        $textos = Ler-XmlEntrada $zip 'xl/sharedStrings.xml'
        $compartilhados = @($textos.sst.si | ForEach-Object { $_.InnerText })
        $doc = Ler-XmlEntrada $zip 'xl/worksheets/sheet1.xml'
        if (!$doc) { throw 'Primeira aba ausente.' }
        $linhas = @{}
        foreach ($linha in $doc.worksheet.sheetData.row) {
            if ([int]$linha.r -eq 1) { continue }
            $valores = @{}
            foreach ($celula in $linha.c) {
                $coluna = [regex]::Replace([string]$celula.r, '\d', '')
                $valor = if ($celula.t -eq 's') { $compartilhados[[int]$celula.v] }
                    elseif ($celula.t -eq 'inlineStr') { $celula.is.InnerText } else { [string]$celula.v }
                $valores[$coluna] = ([string]$valor).Trim()
            }
            $linhas[[int]$linha.r] = $valores
        }
        return $linhas
    } finally { $zip.Dispose() }
}
$auditado = Get-Content -LiteralPath $AuditoriaJson -Raw | ConvertFrom-Json
if ($auditado.status -ne 'PASS') { throw 'Auditoria não concluída.' }
$xml = Ler-Linhas $PlanilhaXml
$pod = Ler-Linhas $PlanilhaComprovantes
if ($xml.Count -ne $auditado.spreadsheet_rows -or $pod.Count -ne $auditado.receipt_spreadsheet_rows) {
    throw 'Planilhas diferentes das auditadas.'
}
$recuperar = [Collections.Generic.List[string]]::new()
$divergentes = [Collections.Generic.List[string]]::new()
$ausentes = [Collections.Generic.List[string]]::new()
foreach ($detalhe in $auditado.spreadsheet_row_details) {
    $linha = $xml[[int]$detalhe.linha_excel]
    if ($linha.C -notmatch '^\d{44}$' -or $linha.D -notmatch '^\d{44}$' -or
        !$linha.D.EndsWith($detalhe.cte_sufixo) -or $linha.A -ne $detalhe.numero_nf) { throw 'Correlação de linha XML divergente.' }
    $par = $linha.C + ';' + $linha.D
    if ($detalhe.classificacao -eq 'DIVERGENCIA_SUCESSO_LOCAL') { $divergentes.Add($par) }
    elseif ($detalhe.classificacao -in @('XML_CORRELACIONADO_SEM_SUCESSO_LOCAL', 'SEM_XML_CTE_RECONHECIDO_NO_SFTP')) { $recuperar.Add($par) }
    else { throw 'Classificação XML não prevista.' }
}
foreach ($detalhe in $auditado.receipt_spreadsheet_row_details) {
    $linha = $pod[[int]$detalhe.linha_excel]
    if ($linha.C -notmatch '^\d{44}$' -or !$linha.C.EndsWith($detalhe.nfe_sufixo) -or
        $linha.A -ne $detalhe.numero_nf) { throw 'Correlação de linha comprovante divergente.' }
    if ($detalhe.classificacao -eq 'SEM_COMPROVANTE_SFTP_PARA_NFE') { $ausentes.Add($linha.C) }
}
$saida = [IO.Path]::GetFullPath($DiretorioSaida)
[void][IO.Directory]::CreateDirectory($saida)
$arquivos = @{
    'xml_sem_sucesso_pares.txt' = @($recuperar | Sort-Object -Unique)
    'xml_divergencias_conciliar.txt' = @($divergentes | Sort-Object -Unique)
    'comprovantes_sem_sftp_nfes.txt' = @($ausentes | Sort-Object -Unique)
}
foreach ($nome in $arquivos.Keys) {
    $caminho = Join-Path $saida $nome
    if (Test-Path -LiteralPath $caminho) { throw "Saída já existe: $nome" }
    [IO.File]::WriteAllLines($caminho, [string[]]$arquivos[$nome], [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{ Arquivo = $nome; Linhas = $arquivos[$nome].Count; SHA256 = (Get-FileHash -LiteralPath $caminho).Hash }
}
