# ArborJ Windows Signing Script
# Signs MSI installers and/or app-image launchers using Azure Trusted Signing.
#
# Prerequisites:
#   1. Azure CLI logged in: az login
#   2. Windows SDK installed (for signtool.exe)
#   3. Azure.CodeSigning.Dlib installed via NuGet:
#      nuget install Microsoft.Trusted.Signing.Client -OutputDirectory C:\signing
#   4. Copy signing-metadata.example.json to signing-metadata.json (gitignored)
#      and fill in your Trusted Signing account/profile names
#
# Usage:
#   # Sign whatever exists in target\installer (MSI and/or portable app-image):
#   .\scripts\sign-windows.ps1
#
#   # Sign a specific MSI:
#   .\scripts\sign-windows.ps1 -MsiPath "target\installer\ArborJ-1.1.2.msi"
#
#   # Sign a portable app-image folder (signs ArborJ.exe inside, then zips):
#   .\scripts\sign-windows.ps1 -AppImageDir "target\installer\ArborJ"
#
#   # Sign just an exe:
#   .\scripts\sign-windows.ps1 -ExePath "target\installer\ArborJ\ArborJ.exe"
#
#   # Skip the portable zip step:
#   .\scripts\sign-windows.ps1 -AppImageDir "target\installer\ArborJ" -NoZip
#

param(
    [string]$MsiPath = "",
    [string]$AppImageDir = "",
    [string]$ExePath = "",
    [string]$MetadataPath = "scripts\signing-metadata.json",
    [string]$DlibPath = "C:\signing\Microsoft.Trusted.Signing.Client.1.0.95\bin\x64\Azure.CodeSigning.Dlib.dll",
    [string]$SignToolPath = "",
    [switch]$NoZip
)

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

if (-not (Test-Path $MetadataPath)) { Write-Error "Metadata not found: $MetadataPath"; exit 1 }
if (-not (Test-Path $DlibPath)) {
    Write-Host "Dlib not found. Installing Microsoft.Trusted.Signing.Client..."
    nuget install Microsoft.Trusted.Signing.Client -OutputDirectory C:\signing
    if (-not (Test-Path $DlibPath)) {
        Write-Error "Dlib still not found at: $DlibPath"
        exit 1
    }
}

Write-Host "=== ArborJ Windows Signing ==="
Write-Host "Metadata:    $MetadataPath"
Write-Host "Dlib:        $DlibPath"
Write-Host "SignTool:    $SignToolPath"
if ($MsiPath)      { Write-Host "MSI:         $MsiPath" }
if ($AppImageDir)  { Write-Host "AppImage:    $AppImageDir" }
if ($ExePath)      { Write-Host "Exe:         $ExePath" }
Write-Host ""

function Invoke-Sign($targetPath, $label) {
    # signtool writes the signature back into the file; clear read-only bit if jpackage set it.
    $item = Get-Item $targetPath
    if ($item.IsReadOnly) { $item.IsReadOnly = $false }

    Write-Host "Signing $label : $targetPath"
    & $SignToolPath sign `
        /v `
        /fd SHA256 `
        /tr "http://timestamp.acs.microsoft.com" `
        /td SHA256 `
        /dlib $DlibPath `
        /dmdf $MetadataPath `
        $targetPath
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Signing failed for $targetPath (exit $LASTEXITCODE)"
        exit $LASTEXITCODE
    }
    Write-Host ""
    Write-Host "Verifying $label signature..."
    & $SignToolPath verify /pa $targetPath
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

Write-Host "=== Signing complete ==="
