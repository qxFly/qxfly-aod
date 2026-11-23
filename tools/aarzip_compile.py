
# aarzip_compile.py - aapt2-compile each AAR res dir into a zip (drives aapt2
# directly via subprocess; the PowerShell foreach loop kept dying mid-run).
import os, glob, subprocess, sys

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BT = os.environ.get("QXFLY_BUILD_TOOLS") or os.path.join(WS, ".deps", "android-sdk", "bt", "android-37.0")
BT = os.path.join(BT, "aapt2.exe")
RES = os.path.join(WS, "module", "build", "aar-res2")
OUT = os.path.join(WS, "module", "build", "aarzip2")
os.makedirs(OUT, exist_ok=True)

ok = fail = 0
dirs = sorted(d for d in os.listdir(RES) if os.path.isdir(os.path.join(RES, d, "res")))
for d in dirs:
    z = os.path.join(OUT, d + ".zip")
    r = subprocess.run([BT, "compile", "--dir", os.path.join(RES, d, "res"), "-o", z],
                       capture_output=True)
    if r.returncode == 0 and os.path.exists(z) and os.path.getsize(z) > 100:
        ok += 1
    else:
        fail += 1
        print("FAIL", d, r.returncode, r.stderr.decode("utf-8", "replace")[:300], flush=True)

print("aar res zips ok=%d fail=%d" % (ok, fail))
sys.exit(0 if fail == 0 else 1)
