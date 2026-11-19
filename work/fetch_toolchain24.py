
# fetch_toolchain24.py - Kotlin 2.4.21 toolchain pieces for the Miuix 0.9.4 path:
#   * kotlin-compiler zip          (GitHub releases, same layout as 2.3.20)
#   * kotlin-compose-compiler-plugin jar (Maven Central)
#   * kotlinx-serialization-compiler-plugin jar (Maven Central)
#   * kotlin-stdlib jar            (Maven Central)
# Everything lands in .deps/ without disturbing the 2.3.20 set.

import urllib.request, os, time, zipfile, shutil

WS = r"D:\Code\My Project\xipinggeci"
DEST = os.path.join(WS, ".deps", "downloads")
os.makedirs(DEST, exist_ok=True)

K = "2.4.21"

def dl(url, filename):
    path = os.path.join(DEST, filename)
    if os.path.exists(path) and os.path.getsize(path) > 1024:
        print("cached  %s (%.1f MB)" % (filename, os.path.getsize(path) / 1048576))
        return path
    t0 = time.time()
    print("fetching %s ..." % filename, flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=180) as r, open(path, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        got = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            if total and got % (10 << 20) < (1 << 20):
                print("   %d/%d MB" % (got // 1048576, total // 1048576), flush=True)
    print("  done %.1f MB in %.0fs" % (os.path.getsize(path) / 1048576, time.time() - t0), flush=True)
    return path

p1 = dl("https://github.com/JetBrains/kotlin/releases/download/v%s/kotlin-compiler-%s.zip" % (K, K),
        "kotlin-compiler-%s.zip" % K)
p2 = dl("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/%s/kotlin-compose-compiler-plugin-%s.jar" % (K, K),
        "kotlin-compose-compiler-plugin-%s.jar" % K)
p3 = dl("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/%s/kotlin-serialization-compiler-plugin-%s.jar" % (K, K),
        "kotlin-serialization-compiler-plugin-%s.jar" % K)
p4 = dl("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/%s/kotlin-stdlib-%s.jar" % (K, K),
        "kotlin-stdlib-%s.jar" % K)

# unzip the compiler into .deps/kotlin24/kotlinc (same layout as the 2.3.20 one)
KC = os.path.join(WS, ".deps", "kotlin24", "kotlinc")
if not os.path.exists(os.path.join(KC, "lib", "kotlin-compiler.jar")):
    if os.path.isdir(KC):
        shutil.rmtree(KC)
    os.makedirs(KC)
    with zipfile.ZipFile(p1) as z:
        z.extractall(KC)
    print("kotlin compiler unpacked ->", KC)

for p in (p2, p3, p4):
    base = os.path.basename(p)
    tgt = os.path.join(WS, ".deps", "libs", base)
    if not os.path.exists(tgt):
        shutil.copyfile(p, tgt)
    print("libs/", base, "OK", os.path.getsize(tgt))
