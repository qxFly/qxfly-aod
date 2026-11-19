# setup_compose.ps1 - one-shot setup of the Kotlin + Compose toolchain
# Usage: powershell -ExecutionPolicy Bypass -File work\setup_compose.ps1
#
# Everything lands inside the workspace (.deps/), nothing is installed system-wide.
param(
    [string]$KotlinVersion = "2.3.20",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$WS = Split-Path -Parent $PSScriptRoot
$S  = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($WS).ShortPath
$env:TEMP = "$WS\.tmp"; $env:TMP = "$WS\.tmp"

$deps = "$S\.deps"
$dl   = "$deps\downloads"
$kt   = "$deps\kotlin"
$cc   = "$deps\compose-compiler"
$libs = "$deps\compose-libs"
foreach ($d in @($dl, $cc, $libs)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

# ---- 1. Kotlin compiler -----------------------------------------------------
$kZip = "$dl\kotlin-compiler-$KotlinVersion.zip"
if ($Force -or -not (Test-Path "$kt\kotlinc\bin\kotlinc.bat")) {
    if (-not (Test-Path $kZip)) {
        Write-Host "[1/3] downloading Kotlin $KotlinVersion (~80 MB)"
        & python "$WS\work\dl_kotlin.py"
    }
    Write-Host "[1/3] extracting Kotlin"
    & python "$WS\work\unzip_kotlin.py"
} else {
    Write-Host "[1/3] Kotlin compiler present"
}

# ---- 2. Compose compiler plugin + stdlib -----------------------------------
if ($Force -or -not (Test-Path "$cc\kotlin-compose-compiler-plugin-embeddable-$KotlinVersion.jar")) {
    Write-Host "[2/3] downloading Compose compiler plugin"
    & python "$WS\work\dl_compose_plugin.py"
} else {
    Write-Host "[2/3] Compose compiler plugin present"
}

# ---- 3. Compose + MiuiX artifact tree --------------------------------------
if ($Force -or -not (Test-Path "$libs\manifest.json")) {
    Write-Host "[3/3] resolving Compose/MiuiX dependencies (this takes a few minutes)"
    & python "$WS\work\resolve_compose.py"
} else {
    $n = (Get-ChildItem $libs -File -Include *.aar,*.jar).Count
    Write-Host "[3/3] compose libs present ($n artifacts)"
}

Write-Host ""
Write-Host "toolchain ready:"
Write-Host "  kotlinc : $kt\kotlinc\bin\kotlinc.bat"
Write-Host "  plugin  : $cc\kotlin-compose-compiler-plugin-embeddable-$KotlinVersion.jar"
Write-Host "  libs    : $libs"
Write-Host ""
Write-Host "build with: powershell -ExecutionPolicy Bypass -File .\build.ps1  (repo root)"
