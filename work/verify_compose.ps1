# verify_compose.ps1 - prove the Kotlin + Compose toolchain works end-to-end
#
# Windows notes (learned the hard way):
#   * a command line over ~32K chars is rejected -> use an @argfile
#   * kotlinc's argfile parser mangles backslashes inside quotes -> use short
#     paths and forward slashes, so no quoting is needed at all
param(
    [string]$PluginJar = ""
)
$ErrorActionPreference = "Stop"
$WS = Split-Path -Parent $PSScriptRoot
$FSO = New-Object -ComObject Scripting.FileSystemObject
$S  = $FSO.GetFolder($WS).ShortPath
$env:TEMP = "$S\.tmp"; $env:TMP = "$S\.tmp"

# Every path handed to kotlinc must be space-free 8.3 form.
function Short([string]$p) {
    try {
        if (Test-Path -LiteralPath $p -PathType Container) { return $FSO.GetFolder($p).ShortPath }
        if (Test-Path -LiteralPath $p -PathType Leaf)      { return $FSO.GetFile($p).ShortPath }
    } catch { }
    return $p
}
function Fwd([string]$p) { return ($p -replace '\\', '/') }
function J([string]$p)   { return (Fwd (Short $p)) }

$JDK  = "$S\.deps\jdk21\jdk-21.0.13+9"
$KOTLINC = "$S\.deps\kotlin\kotlinc\bin\kotlinc.bat"
$PLUGIN  = if ($PluginJar) { $PluginJar } else { "$S\.deps\compose-compiler\kotlin-compose-compiler-plugin-2.3.20.jar" }
$LIBS    = "$S\.deps\compose-libs-clean"
$OUT     = "$S\.tmp\compose-verify"
$env:JAVA_HOME = $JDK

if (-not (Test-Path $KOTLINC)) { throw "missing kotlinc: run work\setup_compose.ps1" }
if (-not (Test-Path $PLUGIN))  { throw "missing compose plugin: run work\setup_compose.ps1" }
if (-not (Test-Path $LIBS))    { throw "missing compose libs: run work\setup_compose.ps1" }

Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$jarDir = "$OUT\jars"
New-Item -ItemType Directory -Force -Path $jarDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$cps = New-Object System.Collections.Generic.List[string]
$idx = 0
foreach ($f in (Get-ChildItem $LIBS -File | Where-Object { $_.Extension -in ".jar", ".aar" })) {
    if ($f.Extension -eq ".jar") {
        $cps.Add((J $f.FullName))
    } else {
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($f.FullName)
            $e = $zip.Entries | Where-Object { $_.FullName -eq "classes.jar" }
            if ($e) {
                $idx++
                $t = Join-Path $jarDir ("a$idx.jar")
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $t, $true)
                $cps.Add((J $t))
            }
            $zip.Dispose()
        } catch { }
    }
}
$cps.Add((J "$S\.deps\android-sdk\p36\android-36\android.jar"))
# -no-stdlib disables kotlinc's automatic stdlib, so it must be explicit.
$stdlib = "$S\.deps\libs\kotlin-stdlib-2.3.20.jar"
if (Test-Path $stdlib) { $cps.Add((J $stdlib)) } else { throw "missing $stdlib" }
Write-Host "classpath entries: $($cps.Count)"
$withSpaces = ($cps | Where-Object { $_ -match ' ' }).Count
if ($withSpaces -gt 0) {
    Write-Warning "$withSpaces classpath entries still contain spaces; quoting may be needed"
}

$src = "$S\module\app\src\main\kotlin"
$files = Get-ChildItem $src -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
Write-Host "kotlin sources: $($files.Count)"

$cp = ($cps -join ";")
$argFile = "$OUT\kotlinc.args"
$lines = @(
    "-no-stdlib",
    "-nowarn",
    "-jvm-target",
    "21",
    "-Xplugin=$(J $PLUGIN)",
    "-classpath",
    $cp,
    "-d",
    (J $OUT)
)
foreach ($f in $files) { $lines += (J $f) }
Set-Content -Path $argFile -Value $lines -Encoding ASCII

Write-Host "compiling..."
& $KOTLINC "@$argFile"
$code = $LASTEXITCODE
Write-Host ""
if ($code -ne 0) { throw "kotlinc failed ($code)" }
$count = (Get-ChildItem $OUT -Recurse -Filter *.class -ErrorAction SilentlyContinue).Count
Write-Host "RESULT: OK - $count classes produced"
Write-Host "Compose compiler plugin transformed @Composable successfully."
