# build.ps1 - 息屏歌词模块唯一构建入口（Kotlin 2.4.21 + Compose 1.12 + Miuix 0.9.4，不用 Gradle）
#
# 用法（在仓库根目录执行，可在任意目录用绝对路径调用）：
#   powershell -ExecutionPolicy Bypass -File .\build.ps1                  # 只构建
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install          # 构建 + 安装到设备
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install -Reboot  # 构建 + 安装 + 重启
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean            # 丢弃中间产物后全量构建
#
# 约定：
#   * 版本号唯一来源 = module\app\src\main\AndroidManifest.xml
#     （versionName / versionCode）。META-INF\xposed\module.prop 在打包时
#     自动同步，不用手工改两处。
#   * 产物：dist\qxfly-aod-<versionName>.apk（已 zipalign、已签名）。
#   * 中间产物：module\build\（可随时删除，-Clean 会自动清）。
#   * 签名密钥：module\debug.keystore（请勿删除，换了就无法覆盖安装）。
#   * 首次在新机器上构建前，先按 docs\COMPOSE_SETUP.md 准备 .deps 工具链。
#
# 注意：本文件含中文，必须保存为 UTF-8 with BOM，否则 Windows PowerShell 5.1
# 会按 ANSI 解析并报语法错误。
param(
    [switch]$Install,
    [switch]$Reboot,
    [switch]$Clean,
    [string]$Adb = "D:\GitSoftware\scrcpy-win64-v3.2\adb.exe"
)

$ErrorActionPreference = "Stop"
$swTotal = [System.Diagnostics.Stopwatch]::StartNew()

# ---- 路径 ---------------------------------------------------------------
$WS  = $PSScriptRoot
$FSO = New-Object -ComObject Scripting.FileSystemObject
$S   = $FSO.GetFolder($WS).ShortPath          # 工作区路径含空格，统一用 8.3 短路径
$TMP = "$S\.tmp"
New-Item -ItemType Directory -Force -Path $TMP | Out-Null
$env:TEMP = $TMP
$env:TMP  = $TMP

$BT      = "$S\.deps\android-sdk\bt\android-37.0"
$JDK     = "$S\.deps\jdk21\jdk-21.0.13+9"
$JAVA    = "$JDK\bin\java.exe"
$JAVAC   = "$JDK\bin\javac.exe"
$KC      = "$S\.deps\kotlin24\kotlinc\lib\kotlin-compiler.jar"
$PLUGIN  = "$S\.deps\libs\kotlin-compose-compiler-plugin-2.4.21.jar"
$SERP    = "$S\.deps\libs\kotlin-serialization-compiler-plugin-2.4.21.jar"
$STDLIB  = "$S\.deps\libs\kotlin-stdlib-2.4.21.jar"
$FATJAR  = "$S\.deps\compose-classes-m094.jar"
$ANDROID = "$S\.deps\android-sdk\p36\android-36\android.jar"
$B       = "$S\module\build"
$CLS     = "$B\classes"
$GEN     = "$B\gen"
$GENX    = "$B\gen-extra"
$APP     = "$S\module\app\src\main"
$MANIFEST = "$APP\AndroidManifest.xml"
$KEYSTORE = "$S\module\debug.keystore"
$env:JAVA_HOME = $JDK
$env:AAR_RES_CLEAN = "$S\.deps\m094-clean"

$BASE_CP = "$ANDROID;$S\.deps\libxposed\102\classes.jar;$S\.deps\superlyricapi-4.0\classes.jar;$S\.deps\libs\annotation-jvm-1.11.0.jar;$STDLIB;$FATJAR"

# ---- 预检 ---------------------------------------------------------------
foreach ($p in @($JAVA, $JAVAC, "$BT\aapt2.exe", "$BT\zipalign.exe", "$BT\lib\d8.jar", "$BT\lib\apksigner.jar",
                $KC, $PLUGIN, $SERP, $STDLIB, $FATJAR, $ANDROID, $MANIFEST, $KEYSTORE)) {
    if (-not (Test-Path $p)) { throw "缺少构建依赖：$p（见 docs\COMPOSE_SETUP.md）" }
}
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw "未找到 python，请加入 PATH" }

# ---- 版本号（唯一来源：AndroidManifest.xml）-----------------------------
$manRaw = Get-Content $MANIFEST -Raw
$versionName = ([regex]::Match($manRaw, 'android:versionName="([^"]*)"')).Groups[1].Value
$versionCode = ([regex]::Match($manRaw, 'android:versionCode="([^"]*)"')).Groups[1].Value
if (-not $versionName) { throw "AndroidManifest.xml 里读不到 versionName" }
New-Item -ItemType Directory -Force -Path "$WS\dist" | Out-Null
$OUT_PATH = "$WS\dist\qxfly-aod-$versionName.apk"

Write-Host "== AOD Lyric build: versionName=$versionName versionCode=$versionCode"
Write-Host "   workspace : $WS"
Write-Host "   output    : $OUT_PATH"

# ---- 0. 清理 ------------------------------------------------------------
if ($Clean) {
    Write-Host "[0/9] clean module\build"
    Remove-Item $B -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "[0/9] prepare intermediates"
Remove-Item $GEN, $GENX -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "$B\aar-res2", "$B\aarzip2" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $B | Out-Null

# ---- 1. 应用资源 ---------------------------------------------------------
Write-Host "[1/9] aapt2 compile (app res)"
& "$BT\aapt2.exe" compile --dir "$APP\res" -o "$B\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile (app) 失败" }

# ---- 2. AAR 资源 --------------------------------------------------------
Write-Host "[2/9] aapt2 compile (AAR res)"
& python "$S\work\collect_aar_res2.py"
if ($LASTEXITCODE -ne 0) { throw "collect_aar_res2.py 失败" }
& python "$S\work\aarzip_compile.py"
if ($LASTEXITCODE -ne 0) { throw "aarzip_compile.py 失败" }

# ---- 3. link ------------------------------------------------------------
Write-Host "[3/9] aapt2 link"
$extraArgs = @()
foreach ($pkg in @(
        "androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.material3",
        "androidx.customview.poolingcontainer", "androidx.activity", "androidx.core",
        "androidx.lifecycle.runtime", "androidx.lifecycle.viewmodel", "androidx.savedstate",
        "androidx.navigationevent", "androidx.startup", "android.support.graphics.drawable",
        "androidx.appcompat", "androidx.fragment", "androidx.recyclerview", "androidx.cardview",
        "androidx.constraintlayout.widget", "androidx.coordinatorlayout", "androidx.viewpager2",
        "androidx.window", "androidx.emoji2.widget", "androidx.emoji2.emojipicker",
        "androidx.core.viewtree", "com.google.android.material",
        "top.yukonga.miuix.kmp", "top.yukonga.miuix.kmp.basic", "top.yukonga.miuix.kmp.theme",
        "top.yukonga.miuix.kmp.preference", "top.yukonga.miuix.kmp.icon", "top.yukonga.miuix.kmp.blur",
        "top.yukonga.miuix.kmp.nav", "top.yukonga.miuix.kmp.squircle", "com.materialkolor")) {
    $extraArgs += "--extra-packages"
    $extraArgs += $pkg
}
$aarZips = @()
if (Test-Path "$B\aarzip2") {
    $aarZips = @(Get-ChildItem "$B\aarzip2" -Filter *.zip | ForEach-Object { $_.FullName })
}
& "$BT\aapt2.exe" link -o "$B\base.apk" `
    -I $ANDROID `
    --manifest $MANIFEST `
    --min-sdk-version 26 --target-sdk-version 34 `
    --java $GEN --auto-add-overlay `
    --output-text-symbols "$B\R.txt" `
    @extraArgs `
    "$B\res.zip" @aarZips
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }
$symbols = (Get-Content "$B\R.txt" | Measure-Object -Line).Lines
Write-Host "      merged symbols: $symbols"
if ($symbols -lt 100) { throw "资源合并不完整：只有 $symbols 个符号" }

# ---- 4. 补齐 R ----------------------------------------------------------
Write-Host "[4/9] complete R classes"
& python "$S\work\gen_extra_r.py"
if ($LASTEXITCODE -ne 0) { throw "gen_extra_r.py 失败" }

# ---- 5. javac（业务 Java + 生成的 R）------------------------------------
Write-Host "[5/9] javac (java sources + R)"
Remove-Item $CLS -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $CLS | Out-Null
$javaAll  = @(Get-ChildItem "$APP\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javaBase = @($javaAll | Where-Object { $_ -notlike "*MainActivity.java" })
$rSources = @()
if (Test-Path $GEN) { $rSources = @(Get-ChildItem $GEN -Recurse -Filter R.java | ForEach-Object { $_.FullName }) }
$baseSources = $javaBase + $rSources
if ($baseSources.Count -gt 0) {
    & $JAVAC -encoding UTF-8 --release 21 -nowarn -cp $BASE_CP -d $CLS $baseSources
    if ($LASTEXITCODE -ne 0) { throw "javac (base) 失败" }
}

# ---- 6. kotlinc（Compose UI）-------------------------------------------
$ktDir = "$APP\kotlin"
if (Test-Path $ktDir) {
    Write-Host "[6/9] kotlinc (Compose + serialization plugins)"
    $ktSrc = @(Get-ChildItem $ktDir -Recurse -Filter *.kt | ForEach-Object { $_.FullName })
    if ($ktSrc.Count -gt 0) {
        & $JAVA -Xmx3g -cp $KC org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
            -no-stdlib -nowarn -jvm-target 21 `
            "-Xplugin=$PLUGIN" `
            "-Xplugin=$SERP" `
            -classpath "$FATJAR;$BASE_CP;$CLS" `
            -d $CLS $ktSrc
        if ($LASTEXITCODE -ne 0) { throw "kotlinc 失败" }
    }
}

# ---- 7. javac（MainActivity：依赖 Kotlin 侧产物）-----------------------
Write-Host "[7/9] javac (MainActivity)"
$main = @($javaAll | Where-Object { $_ -like "*MainActivity.java" })
if ($main.Count -gt 0) {
    & $JAVAC -encoding UTF-8 --release 21 -nowarn -cp "$BASE_CP;$CLS" -d $CLS $main
    if ($LASTEXITCODE -ne 0) { throw "javac (MainActivity) 失败" }
}

# ---- 8. dex + stage ----------------------------------------------------
Write-Host "[8/9] d8 + stage"
$dexIn = @(Get-ChildItem $CLS -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$dexIn += "$S\.deps\superlyricapi-4.0\classes.jar"
$dexIn += "$S\.deps\libs\hiddenapibypass-6.2-classes.jar"
$dexIn += $STDLIB
$dexIn += $FATJAR
Remove-Item "$B\out" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$B\out" | Out-Null
$d8Lines = @("--release", "--lib", $ANDROID, "--min-api", "26", "--output", "$B\out") + $dexIn
Set-Content -Path "$B\d8.args" -Value $d8Lines -Encoding ASCII
& $JAVA -Xmx4g -cp "$BT\lib\d8.jar" com.android.tools.r8.D8 "@$B\d8.args"
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

# 打包用的 xposed 元数据：从源码复制一份，并按 manifest 同步版本号
$XP_SRC = "$APP\resources\META-INF\xposed"
$XP_OUT = "$B\xposed"
Remove-Item $XP_OUT -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item $XP_SRC $XP_OUT -Recurse
$prop = Get-Content "$XP_OUT\module.prop" -Raw
$prop = [regex]::Replace($prop, '(?m)^version=.*$', "version=$versionName")
$prop = [regex]::Replace($prop, '(?m)^versionCode=.*$', "versionCode=$versionCode")
Set-Content -Path "$XP_OUT\module.prop" -Value $prop -Encoding ASCII

& python "$S\module\stage_apk.py" "$B\base.apk" "$B\staged.apk" "$B\out" "$XP_OUT"
if ($LASTEXITCODE -ne 0) { throw "stage_apk.py 失败" }

# ---- 9. 对齐 + 签名 ----------------------------------------------------
Write-Host "[9/9] zipalign + apksigner"
& "$BT\zipalign.exe" -f 4 "$B\staged.apk" "$B\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }
if (Test-Path $OUT_PATH) { Remove-Item $OUT_PATH -Force }
& $JAVA -jar "$BT\lib\apksigner.jar" sign `
    --ks $KEYSTORE --ks-pass pass:aodlyric --ks-key-alias aodlyric `
    --key-pass pass:aodlyric `
    --v4-signing-enabled false `
    --out $OUT_PATH "$B\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }
if (-not (Test-Path $OUT_PATH)) { throw "未生成 $OUT_PATH" }

$sizeMB = [math]::Round((Get-Item $OUT_PATH).Length / 1MB, 2)
$sha = (Get-FileHash $OUT_PATH -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host "构建完成：$OUT_PATH  ($sizeMB MB)"
Write-Host "sha256  : $sha"
Write-Host ("耗时    : {0:N1}s" -f $swTotal.Elapsed.TotalSeconds)

# ---- 可选：安装 / 重启 -------------------------------------------------
if ($Install) {
    if (-not (Test-Path $Adb)) { throw "未找到 adb：$Adb" }
    Write-Host "[install] adb install -r"
    & $Adb install -r $OUT_PATH
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败（设备是否连接？）" }
    if ($Reboot) {
        Write-Host "[reboot] 重启设备以加载新 Hook"
        & $Adb reboot
    } else {
        Write-Host "[install] 完成；若本次改了 Hook 代码，请加 -Reboot 或手动 adb reboot"
    }
} elseif ($Reboot) {
    Write-Host "[reboot] 未指定 -Install，忽略 -Reboot"
}
