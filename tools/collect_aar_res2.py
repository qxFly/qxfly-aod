
import os, zipfile, shutil, re

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLEAN = os.path.join(WS, ".deps", "m094-clean")
OUT = os.path.join(WS, "module", "build", "aar-res2")

if os.path.isdir(OUT):
    shutil.rmtree(OUT)
os.makedirs(OUT)

EXCLUDE = ("-lint", "-tooling", "ui-test", "-junit4", "-samples", "-benchmark")


def parse_name(f):
    parts = f.rsplit(".", 1)[0].split("__")
    return parts[0], parts[1], parts[2] if len(parts) >= 3 else ("", "", "")


def ver_key(v):
    parts = re.findall(r"\d+|[A-Za-z]+", v)
    return [(0, int(p)) if p.isdigit() else (1, p) for p in parts]


# Keep only the highest version per (group, base artifact): stale bare KMP
# artifacts (e.g. androidx.compose.ui:ui:1.2.1 pulled by some legacy pin)
# duplicate the -android AAR's resources and break aapt2 link.
best = {}
for f in sorted(os.listdir(CLEAN)):
    if not f.endswith(".aar"):
        continue
    if any(x in f for x in EXCLUDE):
        continue
    g, a, v = parse_name(f)
    base = a[:-len("-android")] if a.endswith("-android") else a
    key = (g, base)
    k = (ver_key(v), 1 if a.endswith("-android") else 0)
    if key not in best or k > best[key][0]:
        best[key] = (k, f)

total = 0
dirs = 0
for key, (k, f) in sorted(best.items()):
    p = os.path.join(CLEAN, f)
    try:
        with zipfile.ZipFile(p) as z:
            names = z.namelist()
            res = [n for n in names if n.startswith("res/") and not n.endswith("/")]
            if not res:
                continue
            base_dir = f.rsplit(".", 1)[0].replace(".", "_")
            dest = os.path.join(OUT, base_dir)
            os.makedirs(dest, exist_ok=True)
            for n in res:
                target = os.path.join(dest, n)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as fh:
                    fh.write(z.read(n))
            total += len(res)
            dirs += 1
    except Exception as e:
        print("skip", f, e)

print("AAR res dirs: %d, files: %d" % (dirs, total))
