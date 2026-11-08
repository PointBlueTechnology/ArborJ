# ArborJ Windows Signing Script (REST variant)
# Signs MSI installers and/or app-image launchers with Azure Trusted Signing
# WITHOUT the Azure.CodeSigning.Dlib. signtool emits the Authenticode digest
# (/dg), the digest is signed through the Trusted Signing REST API with the
# cached Azure CLI login, and signtool ingests the signature (/di) and
# timestamps it.
#
# Use this when sign-windows.ps1 fails with "SignTool Error: Access is denied"
# even though `az account get-access-token --resource https://codesigning.azure.net`
# works. That started on the build VM after the Windows 11 25H2 update (the
# dlib fails before it even reads its metadata); 2.0.11 was signed this way.
#
# Prerequisites:
#   1. Azure CLI logged in (az login --use-device-code --tenant <tenant>) as an
#      identity holding the Certificate Profile Signer role on the account
#   2. Windows SDK installed (for signtool.exe)
#   3. scripts\signing-metadata.json (gitignored) with Endpoint,
#      CodeSigningAccountName and CertificateProfileName — same file
#      sign-windows.ps1 uses
#
# Usage mirrors sign-windows.ps1:
#   .\scripts\sign-windows-rest.ps1                                  # whatever exists in target\installer
#   .\scripts\sign-windows-rest.ps1 -MsiPath target\installer\ArborJ-1.1.2.msi
#   .\scripts\sign-windows-rest.ps1 -AppImageDir target\installer\ArborJ   # signs ArborJ.exe, then zips
#   .\scripts\sign-windows-rest.ps1 -ExePath target\installer\ArborJ\ArborJ.exe
#   .\scripts\sign-windows-rest.ps1 -AppImageDir target\installer\ArborJ -NoZip
#

param(
    [string]$MsiPath = "",
    [string]$AppImageDir = "",
    [string]$ExePath = "",
    [string]$MetadataPath = "scripts\signing-metadata.json",
    [string]$SignToolPath = "",
    [string]$ApiVersion = "2022-06-15-preview",
    [switch]$NoZip
)

$ErrorActionPreference = 'Stop'

# Auto-discover targets in target\installer if no explicit paths given
if (-not $MsiPath -and -not $AppImageDir -and -not $ExePath) {
    $defaultMsi = Get-ChildItem -Path "target\installer\ArborJ-*.msi" -ErrorAction SilentlyContinue |
                  Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($defaultMsi) { $MsiPath = $defaultMsi.FullName }

    $defaultImg = "target\installer\ArborJ"
    if (Test-Path (Join-Path $defaultImg "ArborJ.exe")) { $AppImageDir = $defaultImg }

    if (-not $MsiPath -and -not $AppImageDir) {
        Write-Error "Nothing to sign. No MSI in target\installer and no app-image at target\installer\ArborJ."
        exit 1
    }
}

# Find signtool.exe if not specified
if (-not $SignToolPath) {
    $sdkPaths = @(
        "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe",
        "${env:ProgramFiles}\Windows Kits\10\bin\*\x64\signtool.exe"
    )
    foreach ($pattern in $sdkPaths) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Sort-Object -Descending | Select-Object -First 1
        if ($found) {
            $SignToolPath = $found.FullName
            break
        }
    }
    if (-not $SignToolPath) {
        Write-Error "signtool.exe not found. Install the Windows SDK."
        exit 1
    }
}

# Azure CLI is not on PATH over SSH
if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    $env:Path = "${env:ProgramFiles}\Microsoft SDKs\Azure\CLI2\wbin;$env:Path"
}

if (-not (Test-Path $MetadataPath)) { Write-Error "Metadata not found: $MetadataPath"; exit 1 }
$meta = Get-Content $MetadataPath -Raw | ConvertFrom-Json
$profileUrl = "$($meta.Endpoint.TrimEnd('/'))/codesigningaccounts/$($meta.CodeSigningAccountName)/certificateprofiles/$($meta.CertificateProfileName)"

Write-Host "=== ArborJ Windows Signing (REST) ==="
Write-Host "Metadata:    $MetadataPath"
Write-Host "Profile:     $profileUrl"
Write-Host "SignTool:    $SignToolPath"
if ($MsiPath)      { Write-Host "MSI:         $MsiPath" }
if ($AppImageDir)  { Write-Host "AppImage:    $AppImageDir" }
if ($ExePath)      { Write-Host "Exe:         $ExePath" }
Write-Host ""

$token = az account get-access-token --resource https://codesigning.azure.net --query accessToken -o tsv
if ($LASTEXITCODE -ne 0 -or -not $token) {
    Write-Error "No Azure CLI token. Run: az login --use-device-code --tenant <tenant>"
    exit 1
}
$headers = @{ Authorization = "Bearer $token" }

# Signing certificate: the profile's current short-lived leaf plus its CA chain,
# returned as PKCS7. signtool gets the leaf via /f and its issuer via /ac
# (signtool rejects a PKCS7 there, so the issuer is exported on its own).
$work = Join-Path $env:TEMP "arborj-sign"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
$chainPath = Join-Path $work "chain.p7b"
$leafPath = Join-Path $work "leaf.cer"
$issuerPath = Join-Path $work "issuer.cer"
Invoke-WebRequest -Uri "$profileUrl/sign/certchain?api-version=$ApiVersion" -Headers $headers -OutFile $chainPath -UseBasicParsing
$chain = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2Collection
$chain.Import($chainPath)
$leaf = $chain | Where-Object {
    $bc = $_.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.19' }
    -not ($bc -and $bc.CertificateAuthority)
} | Select-Object -First 1
if (-not $leaf) { Write-Error "No end-entity certificate in the chain returned by $profileUrl"; exit 1 }
$issuer = $chain | Where-Object { $_.Subject -eq $leaf.Issuer } | Select-Object -First 1
if (-not $issuer) { Write-Error "Issuer '$($leaf.Issuer)' missing from the chain returned by $profileUrl"; exit 1 }
[IO.File]::WriteAllBytes($leafPath, $leaf.Export('Cert'))
[IO.File]::WriteAllBytes($issuerPath, $issuer.Export('Cert'))
Write-Host "Certificate: $($leaf.Subject.Split(',')[0]) (valid $($leaf.NotBefore.ToString('u')) to $($leaf.NotAfter.ToString('u')))"
Write-Host ""

function Invoke-Sign($targetPath, $label) {
    # signtool writes the signature back into the file; clear read-only bit if jpackage set it.
    $item = Get-Item $targetPath
    if ($item.IsReadOnly) { $item.IsReadOnly = $false }
    $name = $item.Name

    $dg = Join-Path $work "dg"
    if (Test-Path $dg) { Remove-Item $dg -Recurse -Force }
    New-Item -ItemType Directory -Path $dg | Out-Null

    Write-Host "Signing $label : $targetPath"
    # 1. Emit the to-be-signed digest ($dg\<name>.dig) and unsigned PKCS7 ($dg\<name>.p7u)
    & $SignToolPath sign /dg $dg /fd SHA256 /f $leafPath /ac $issuerPath $targetPath | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "signtool /dg failed for $targetPath (exit $LASTEXITCODE)"; exit $LASTEXITCODE }
    $digest = (Get-Content (Join-Path $dg "$name.dig") -Raw).Trim()

    # 2. Sign the digest with Trusted Signing (async operation)
    $body = @{ signatureAlgorithm = "RS256"; digest = $digest } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Method Post -Uri "$profileUrl/sign?api-version=$ApiVersion" -Headers $headers -ContentType "application/json" -Body $body
    $opUrl = "$profileUrl/sign/$($resp.operationId)?api-version=$ApiVersion"
    for ($i = 0; $i -lt 60 -and $resp.status -ne 'Succeeded'; $i++) {
        Start-Sleep -Seconds 2
        $resp = Invoke-RestMethod -Method Get -Uri $opUrl -Headers $headers
        if ($resp.status -eq 'Failed') { Write-Error "Trusted Signing failed for $targetPath : $($resp | ConvertTo-Json -Compress)"; exit 1 }
    }
    if ($resp.status -ne 'Succeeded') { Write-Error "Trusted Signing timed out for $targetPath (last status $($resp.status))"; exit 1 }
    Set-Content -Path (Join-Path $dg "$name.dig.signed") -Value $resp.signature -NoNewline -Encoding ascii

    # 3. Ingest the signature, then timestamp
    & $SignToolPath sign /di $dg $targetPath | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "signtool /di failed for $targetPath (exit $LASTEXITCODE)"; exit $LASTEXITCODE }
    & $SignToolPath timestamp /tr "http://timestamp.acs.microsoft.com" /td SHA256 $targetPath | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "Timestamping failed for $targetPath (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

    Write-Host ""
    Write-Host "Verifying $label signature..."
    & $SignToolPath verify /pa $targetPath
    if ($LASTEXITCODE -ne 0) { Write-Error "Verification failed for $targetPath (exit $LASTEXITCODE)"; exit $LASTEXITCODE }
    Write-Host ""
}

# Sign app-image launcher first (so a subsequent MSI rebuild from it would carry the signed exe)
if ($AppImageDir) {
    if (-not (Test-Path $AppImageDir)) { Write-Error "AppImage dir not found: $AppImageDir"; exit 1 }
    $imgExe = Join-Path $AppImageDir "ArborJ.exe"
    if (-not (Test-Path $imgExe)) { Write-Error "ArborJ.exe not found in: $AppImageDir"; exit 1 }
    Invoke-Sign $imgExe "app-image exe"

    if (-not $NoZip) {
        # Derive version from app.cfg if present, otherwise from folder mtime
        $version = ""
        $cfg = Get-ChildItem -Path (Join-Path $AppImageDir "app\*.cfg") -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cfg) {
            $match = Select-String -Path $cfg.FullName -Pattern '-Djpackage\.app-version=(.+)$' | Select-Object -First 1
            if ($match) { $version = $match.Matches[0].Groups[1].Value.Trim() }
        }
        $suffix = if ($version) { "-$version" } else { "" }
        $zipPath = Join-Path (Split-Path $AppImageDir -Parent) ("ArborJ$suffix-portable-windows.zip")
        if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
        Write-Host "Creating portable zip: $zipPath"
        Compress-Archive -Path $AppImageDir -DestinationPath $zipPath -CompressionLevel Optimal
        Write-Host "Portable zip: $zipPath"
        Write-Host ""
    }
}

if ($ExePath) {
    if (-not (Test-Path $ExePath)) { Write-Error "Exe not found: $ExePath"; exit 1 }
    Invoke-Sign $ExePath "exe"
}

if ($MsiPath) {
    if (-not (Test-Path $MsiPath)) { Write-Error "MSI not found: $MsiPath"; exit 1 }
    Invoke-Sign $MsiPath "MSI"
}

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "=== Signing complete ==="
