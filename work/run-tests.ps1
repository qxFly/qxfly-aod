# run-tests.ps1 - plain-JVM unit tests for the pure-logic parts of the module.
#
# Usage (any directory):
#   powershell -ExecutionPolicy Bypass -File .\work\run-tests.ps1
#
# There is no JUnit in this project: the toolchain is hand-rolled (aapt2 /
# kotlinc / d8), so the tests are a single main() compiled against the same
# classes the APK is built from and executed with the bundled JDK.
#
# Only classes that touch NO Android runtime are covered here (AnchorIds,
# LrcBuilder, LyricDisplay). Anything with a Context/Bundle/View dependency
# still needs an on-device check.
#
# NOTE: keep this file ASCII-only, or save it as UTF-8 with BOM.

$ErrorActionPreference = "Stop"

$WS  = Split-Path -Parent $PSScriptRoot
$FSO = New-Object -ComObject Scripting.FileSystemObject
$S   = $FSO.GetFolder($WS).ShortPath

$JDK     = "$S\.deps\jdk21\jdk-21.0.13+9"
$JAVA    = "$JDK\bin\java.exe"
$JAVAC   = "$JDK\bin\javac.exe"
$STDLIB  = "$S\.deps\libs\kotlin-stdlib-2.4.21.jar"
$FATJAR  = "$S\.deps\compose-classes-m094.jar"
$ANDROID = "$S\.deps\android-sdk\p36\android-36\android.jar"
$B       = "$S\module\build"
$OUT     = "$B\test-classes"
$APP     = "$S\module\app\src\main"
$TEST    = "$S\module\test\java"

foreach ($p in @($JAVA, $JAVAC, $STDLIB, $ANDROID)) {
    if (-not (Test-Path $p)) { throw "missing build dependency: $p (see docs\COMPOSE_SETUP.md)" }
}
if (-not (Test-Path $TEST)) { throw "missing test sources: $TEST" }

$BASE_CP = "$ANDROID;$S\.deps\libxposed\102\classes.jar;$S\.deps\superlyricapi-4.0\classes.jar;$S\.deps\libs\annotation-jvm-1.11.0.jar;$STDLIB;$FATJAR"

Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

# Compile ONLY the test sources. -sourcepath lets javac pull in the app classes
# under test (AnchorIds / LrcBuilder / LyricDisplay) implicitly, so no Android
# resources (R) are needed - those classes have no Context/Bundle dependency.
$testSources = @(Get-ChildItem $TEST -Recurse -Filter *.java | ForEach-Object { $_.FullName })

Write-Host "[1/2] javac (tests + sources under test)"
& $JAVAC -encoding UTF-8 --release 21 -nowarn -sourcepath "$APP\java" `
    -cp $BASE_CP -d $OUT $testSources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# The generated R class is not produced here, so exclude nothing else; the
# tested classes do not reference R.
Write-Host "[2/2] run AllTests"
& $JAVA -cp "$OUT;$S\.deps\superlyricapi-4.0\classes.jar;$STDLIB;$ANDROID" cn.qxfly.aod.AllTests
if ($LASTEXITCODE -ne 0) { throw "tests failed" }
