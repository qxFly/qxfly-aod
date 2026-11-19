
import os, re, zipfile, shutil

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLEAN = os.environ.get("AAR_RES_CLEAN", os.path.join(WS, ".deps", "compose-libs-clean"))
GEN = os.path.join(WS, "module", "build", "gen")
RTXT = os.path.join(WS, "module", "build", "R.txt")

# ---- 1. REAL ids from the merged resource table ----------------------------
real = {}   # (pkg? unknown) -> we only get type/name/value, package is the app's
# aapt2 --output-text-symbols emits the APP package only, but ids are global:
#   int <type> <name> 0x7f....
# Any library R field must point at the SAME numeric id for the same type/name.
by_type_name = {}
if os.path.exists(RTXT):
    for line in open(RTXT, encoding="utf-8", errors="ignore"):
        m = re.match(r"int\s+(\w+)\s+(\w+)\s+(0x[0-9a-fA-F]+)", line)
        if m:
            by_type_name[(m.group(1), m.group(2))] = m.group(3)
print("real symbols:", len(by_type_name))

# ---- 2. specs from every AAR's R.txt --------------------------------------
EXCLUDE = ("-lint", "-tooling", "ui-test", "-junit4", "-samples", "-benchmark",
           "-compiler", "-testing", "-google-fonts", "viewbinding")
spec = {}
for f in sorted(os.listdir(CLEAN)):
    if not f.endswith(".aar") or any(x in f for x in EXCLUDE):
        continue
    p = os.path.join(CLEAN, f)
    try:
        with zipfile.ZipFile(p) as z:
            names = z.namelist()
            if "R.txt" not in names or "AndroidManifest.xml" not in names:
                continue
            man = z.read("AndroidManifest.xml").decode("utf-8", "ignore")
            pm = re.search(r'package="([a-zA-Z0-9_.]+)"', man)
            if not pm:
                continue
            pkg = pm.group(1)
            for line in z.read("R.txt").decode("utf-8", "ignore").splitlines():
                mm = re.match(r"int\s+(\w+)\s+(\w+)\s+0x[0-9a-fA-F]+", line)
                if mm:
                    spec.setdefault(pkg, {}).setdefault(mm.group(1), {})[mm.group(2)] = None
    except Exception:
        pass

# ---- 3. patch the aapt2 R files, resolving each field to its REAL id -------
def parse_r(path):
    txt = open(path, encoding="utf-8", errors="ignore").read()
    have = {}
    for im in re.finditer(r"public static final class (\w+) \{(.*?)\n  \}", txt, re.S):
        have[im.group(1)] = set(re.findall(r"public static final int (\w+)\s*=", im.group(2)))
    return txt, have

missing_real = 0
patched = created = 0
for pkg, types in sorted(spec.items()):
    path = os.path.join(GEN, *pkg.split("."), "R.java")
    if os.path.exists(path):
        txt, have = parse_r(path)
        add = []
        for t, fields in types.items():
            miss = set(fields) - have.get(t, set())
            for name in sorted(miss):
                rid = by_type_name.get((t, name))
                if rid is None:
                    missing_real += 1
                    continue          # no real resource: omit rather than fake it
                if t in have:
                    pat = re.compile(r"(public static final class %s \{)(.*?)(\n  \})" % re.escape(t), re.S)
                    txt = pat.sub(lambda m: m.group(1) + m.group(2)
                                  + "\n    public static final int %s=%s;" % (name, rid)
                                  + m.group(3), txt, count=1)
                    have[t].add(name)
                else:
                    add.append((t, name, rid))
        if add:
            block = []
            for t, name, rid in add:
                block.append("  public static final class %s {" % t)
                block.append("    public static final int %s=%s;" % (name, rid))
                block.append("  }")
            i = txt.rstrip().rfind("}")
            txt = txt[:i] + "\n".join(block) + "\n}\n"
        open(path, "w", encoding="utf-8").write(txt)
        patched += 1
    else:
        d = os.path.join(GEN, *pkg.split("."))
        os.makedirs(d, exist_ok=True)
        body = ["package %s;" % pkg, "", "public final class R {", "  private R() {}"]
        for t, fields in sorted(types.items()):
            entries = []
            for name in sorted(fields):
                rid = by_type_name.get((t, name))
                if rid is None:
                    missing_real += 1
                    continue
                entries.append("    public static final int %s=%s;" % (name, rid))
            if entries:
                body.append("  public static final class %s {" % t)
                body.extend(entries)
                body.append("  }")
        body.append("}")
        open(os.path.join(d, "R.java"), "w", encoding="utf-8").write("\n".join(body))
        created += 1

print("patched:", patched, "created:", created)
print("fields with no real resource (omitted):", missing_real)
