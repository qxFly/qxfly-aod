# setup_deps.ps1 - prepare the build-only dependencies under .deps/ (repo-local).
#
# Usage (from anywhere):
#   powershell -ExecutionPolicy Bypass -File .\tools\setup_deps.ps1
#
# What it does:
#   1. Kotlin 2.4.21 compiler + compose/serialization plugins + stdlib
#   2. Compose 1.12 / Miuix 0.9.4 dependency graph -> .deps/m094-clean,
#      dedup -> .deps/m094-dedup, merged -> .deps/compose-classes-m094.jar
#   3. small prebuilt API jars (libxposed 102, SuperLyric API 4.0, annotation)
#   4. reports whether JDK 21 / Android SDK are missing (and how to point at them)
#
# JDK / Android SDK are NOT downloaded here (they are large and licensed);
# install them yourself and point the build at them with:
#   QXFLY_JDK          e.g. C:\Program Files\Eclipse Adoptium\jdk-21...
#   QXFLY_BUILD_TOOLS  ...\Android\Sdk\build-tools\37.0.0
#   QXFLY_ANDROID_JAR  ...\Android\Sdk\platforms\android-36\android.jar
# (or let them live in .deps/android-sdk and .deps/jdk21 as the default layout)

$ErrorActionPreference = "Stop"
$WS = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $WS ".deps"
New-Item -ItemType Directory -Force -Path $deps | Out-Null

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "未找到 python，请先安装 Python 3 并加入 PATH"
}

Write-Host "[1/3] Kotlin 2.4.21 toolchain"
& python (Join-Path $PSScriptRoot "fetch_toolchain24.py")
if ($LASTEXITCODE -ne 0) { throw "fetch_toolchain24.py failed" }

Write-Host "[2/3] Compose 1.12 / Miuix 0.9.4 dependency graph (may take minutes)"
& python (Join-Path $PSScriptRoot "upgrade_m094.py")
if ($LASTEXITCODE -ne 0) { throw "upgrade_m094.py failed" }

Write-Host "[3/3] prebuilt API jars"
$map = @{
    "libxposed-api-102-classes.jar"   = ".deps\libxposed\102\classes.jar"
    "superlyricapi-4.0-classes.jar"   = ".deps\superlyricapi-4.0\classes.jar"
    "annotation-jvm-1.11.0.jar"       = ".deps\libs\annotation-jvm-1.11.0.jar"
}
foreach ($k in $map.Keys) {
    $src = Join-Path $PSScriptRoot "prebuilt\$k"
    $dst = Join-Path $WS $map[$k]
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    Copy-Item $src $dst -Force
    Write-Host "  $k -> $($map[$k])"
}

# ---- environment check ------------------------------------------------------
$jdk = if ($env:QXFLY_JDK) { $env:QXFLY_JDK } else { "$WS\.deps\jdk21\jdk-21.0.13+9" }
$bt  = if ($env:QXFLY_BUILD_TOOLS) { $env:QXFLY_BUILD_TOOLS } else { "$WS\.deps\android-sdk\bt\android-37.0" }
$aj  = if ($env:QXFLY_ANDROID_JAR) { $env:QXFLY_ANDROID_JAR } else { "$WS\.deps\android-sdk\p36\android-36\android.jar" }

Write-Host ""
Write-Host "toolchain check:"
Write-Host ("  JDK 21        : " + $jdk + $(if (Test-Path "$jdk\bin\javac.exe") { "  [ok]" } else { "  [MISSING -> set QXFLY_JDK]" }))
Write-Host ("  build-tools 37: " + $bt  + $(if (Test-Path "$bt\aapt2.exe") { "  [ok]" } else { "  [MISSING -> set QXFLY_BUILD_TOOLS]" }))
Write-Host ("  android-36 jar: " + $aj  + $(if (Test-Path $aj) { "  [ok]" } else { "  [MISSING -> set QXFLY_ANDROID_JAR]" }))
Write-Host ""
Write-Host "build with: powershell -ExecutionPolicy Bypass -File .\build.ps1"
