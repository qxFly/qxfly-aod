
# upgrade_m094.py - resolve + download the dependency graph for Miuix 0.9.4
# (split modules) + Compose 1.12.0 + lifecycle 2.11.0, into SEPARATE dirs so the
# working 0.8.8 toolchain stays untouched.
#
# Stages:
#   1. BFS over POMs (highest version wins) -> chosen.json
#   2. download artifacts                -> .deps/m094-clean/
#   3. fix KMP variants (-android/-jvm)  -> same dir
#   4. dedup classes by priority         -> .deps/m094-dedup/
#   5. fat jar (classes + .kotlin_module)-> .deps/compose-classes-m094.jar
#
# kotlin-stdlib is EXCLUDED from the fat jar on purpose: the dex step links the
# standalone stdlib jar; shipping both would give d8 duplicate classes.

import urllib.request, os, re, json, time, zipfile, io, shutil, socket, threading
from concurrent.futures import ThreadPoolExecutor
from collections import defaultdict

# DNS lookups are not covered by urlopen's timeout; a hung resolver wedges a
# worker forever. Cap everything at the socket level.
socket.setdefaulttimeout(20)

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLEAN = os.path.join(WS, ".deps", "m094-clean")
DEDUP = os.path.join(WS, ".deps", "m094-dedup")
FAT   = os.path.join(WS, ".deps", "compose-classes-m094.jar")
os.makedirs(CLEAN, exist_ok=True)

# dl.google.com is unreachable from this network (curl also times out);
# the Aliyun mirrors proxy central + google maven and respond in <1s.
REPOS = [
    "https://maven.aliyun.com/repository/public/",
    "https://maven.aliyun.com/repository/google/",
    "https://repo1.maven.org/maven2/",
    "https://dl.google.com/dl/android/maven2/",
]

M   = "0.9.4"    # miuix
COM = "1.12.0"   # compose (required by miuix 0.9.4)
LCY = "2.11.0"   # lifecycle
NAV = "1.1.2"    # navigationevent
ACT = "1.13.0"   # activity-compose
KTX = "1.19.1"   # core-ktx
SER = "1.11.0"   # kotlinx-serialization
COR = "1.11.0"   # coroutines
KOT = "2.4.21"   # kotlin

ROOTS = [
    ("top.yukonga.miuix.kmp", "miuix-ui-android", M),
    ("top.yukonga.miuix.kmp", "miuix-preference-android", M),
    ("top.yukonga.miuix.kmp", "miuix-nav-android", M),
    ("top.yukonga.miuix.kmp", "miuix-blur-android", M),
    ("top.yukonga.miuix.kmp", "miuix-icons-android", M),
    ("top.yukonga.miuix.kmp", "miuix-squircle-android", M),
    ("androidx.activity", "activity-compose", ACT),
    ("androidx.core", "core-ktx", KTX),
    ("androidx.lifecycle", "lifecycle-runtime-compose-android", LCY),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose-android", LCY),
    ("androidx.navigationevent", "navigationevent-compose-android", NAV),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", SER),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", COR),
    ("org.jetbrains.kotlin", "kotlin-stdlib", KOT),
]

# Explicit pins: coordinates whose resolved version got polluted by bracketed
# Gradle-metadata versions or stale pins from unrelated transitive deps.
PINS = [
    ("androidx.activity", "activity", ACT),
    ("androidx.savedstate", "savedstate", "1.3.1"),
    ("androidx.savedstate", "savedstate-android", "1.3.1"),
    ("androidx.lifecycle", "lifecycle-runtime-ktx-android", LCY),
    ("androidx.lifecycle", "lifecycle-viewmodel-android", LCY),
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx-android", LCY),
    ("androidx.lifecycle", "lifecycle-common-android", LCY),
    ("androidx.lifecycle", "lifecycle-runtime-android", LCY),
    ("androidx.lifecycle", "lifecycle-viewmodel-savedstate", LCY),
    ("androidx.navigationevent", "navigationevent", NAV),
    ("androidx.navigationevent", "navigationevent-android", NAV),
    ("androidx.compose.ui", "ui-android", COM),
    ("androidx.compose.ui", "ui-unit-android", COM),
    ("androidx.compose.ui", "ui-util-android", COM),
    ("androidx.compose.ui", "ui-geometry-android", COM),
    ("androidx.compose.ui", "ui-graphics-android", COM),
    ("androidx.compose.ui", "ui-text-android", COM),
    ("androidx.compose.runtime", "runtime-android", COM),
    ("androidx.compose.runtime", "runtime-saveable-android", COM),
    ("androidx.compose.foundation", "foundation-android", COM),
    ("androidx.compose.foundation", "foundation-layout-android", COM),
    ("androidx.compose.animation", "animation-android", COM),
    ("androidx.annotation", "annotation-jvm", "1.11.0"),
    ("org.jetbrains.kotlinx", "atomicfu", "0.27.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", COR),
]
ROOTS = ROOTS + PINS

def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(req, timeout=timeout).read()

def path_for(g, a, v, ext):
    return "%s/%s/%s/%s-%s.%s" % (g.replace(".", "/"), a, v, a, v, ext)

def try_fetch(g, a, v, ext):
    for attempt in range(2):
        for r in REPOS:
            try:
                return fetch(r + path_for(g, a, v, ext))
            except Exception:
                continue
    return None

def ver_key(v):
    parts = re.findall(r"\d+|[A-Za-z]+", v)
    return [(0, int(p)) if p.isdigit() else (1, p) for p in parts]


def norm_ver(v):
    """Normalise Gradle-metadata version strings: '[2.11.0]' -> '2.11.0'.

    JB/KMP POMs publish required versions bracketed; brackets must not leak
    into download paths (404), and 'unspecified' must never win a max() over
    a real version (letters sort above digits)."""
    v = v.strip().strip("[]()! ")
    if "," in v:                      # range like [1.0,2.0) -> lower bound
        v = v.split(",")[0].strip("[]() ")
    if not re.match(r"^[0-9][0-9A-Za-z._+-]*$", v):
        return None
    return v


def parse_pom(txt):
    # Only real <dependencies> matter. <build> (build/plugin deps), <profiles>
    # (conditional deps) and <dependencyManagement> (version pins, not deps)
    # would otherwise explode the frontier 10x (4662 coords instead of ~400).
    for tag in ("dependencyManagement", "profiles", "build", "reporting"):
        txt = re.sub(r"<%s>[\s\S]*?</%s>" % (tag, tag), "", txt)
    out = []
    deps_block = re.search(r"<dependencies>[\s\S]*?</dependencies>", txt)
    if not deps_block:
        return out
    body = deps_block.group(0)
    for m in re.findall(r"<dependency>([\s\S]*?)</dependency>", body, re.S):
        g = re.search(r"<groupId>(.*?)</groupId>", m)
        a = re.search(r"<artifactId>(.*?)</artifactId>", m)
        v = re.search(r"<version>(.*?)</version>", m)
        s = re.search(r"<scope>(.*?)</scope>", m)
        o = re.search(r"<optional>(.*?)</optional>", m)
        if not (g and a):
            continue
        if s and s.group(1).strip() in ("test", "provided", "system"):
            continue
        if o and o.group(1).strip() == "true":
            continue
        cv = v.group(1).strip() if v else None
        if cv:
            cv = norm_ver(cv)
            if not cv:
                continue
        out.append((g.group(1).strip(), a.group(1).strip(), cv))
    return out

POM_CACHE = os.path.join(CLEAN, "poms")
os.makedirs(POM_CACHE, exist_ok=True)

def cached_pom(g, a, v):
    """Fetch a POM with a disk cache so restarts never refetch."""
    key = re.sub(r"[^A-Za-z0-9._-]", "_", "%s__%s__%s" % (g, a, v))
    p = os.path.join(POM_CACHE, key + ".pom")
    if os.path.exists(p) and os.path.getsize(p) > 0:
        return open(p, "rb").read()
    data = try_fetch(g, a, v, "pom")
    if data:
        # unique per-thread tmp: two threads can resolve the same coord
        tmp = "%s.%d.tmp" % (p, threading.get_ident())
        open(tmp, "wb").write(data)
        try:
            os.replace(tmp, p)
        except PermissionError:
            os.remove(tmp)  # another thread won the race; fine
    return data

def foreign(a):
    return any(x in a for x in ("-ios", "-macos", "-js", "-wasm", "-desktop", "-uikit",
                                "-linux", "-mingw", "-tvos", "-watchos"))

# ---- stage 1+2: resolve & download ------------------------------------------
chosen_path = os.path.join(CLEAN, "chosen.json")
if os.path.exists(chosen_path) and os.path.exists(FAT):
    print("fat jar already built, skip resolve/download")
else:
    constraints = defaultdict(set)
    visited = set()
    frontier = list(ROOTS)
    pending_unknown = []
    t0 = time.time()
    rnd = 0
    while frontier:
        rnd += 1
        batch = [c for c in frontier if c not in visited]
        frontier = []
        if not batch:
            break
        for c in batch:
            visited.add(c)
            constraints[(c[0], c[1])].add(c[2])
        with ThreadPoolExecutor(max_workers=48) as ex:
            import threading
            lock = threading.Lock()
            counter = [0]

            def fp(coord):
                g, a, v = coord
                if foreign(a):
                    with lock:
                        counter[0] += 1
                    return coord, None
                pom = cached_pom(g, a, v)
                with lock:
                    counter[0] += 1
                    if counter[0] % 50 == 0:
                        print("    poms %d/%d (%.0fs)" % (
                            counter[0], len(batch), time.time() - t0), flush=True)
                return coord, pom
            results = list(ex.map(fp, batch))
        for coord, pom in results:
            if not pom:
                continue
            for cg, ca, cv in parse_pom(pom.decode("utf-8", "ignore")):
                if foreign(ca):
                    continue
                if cv:
                    constraints[(cg, ca)].add(cv)
                    if (cg, ca, cv) not in visited:
                        frontier.append((cg, ca, cv))
                else:
                    pending_unknown.append((cg, ca))
        print("  round %d: batch=%d visited=%d modules=%d (%.0fs)" % (
            rnd, len(batch), len(visited), len(constraints), time.time() - t0), flush=True)

    chosen = {}
    for (g, a), vs in constraints.items():
        real = [v for v in vs if v and not v.startswith("$")]
        if real:
            chosen[(g, a)] = sorted(real, key=ver_key)[-1]
    print("\nwalked %d coords, %d modules, %.0fs" % (len(visited), len(chosen), time.time() - t0))
    json.dump([{"g": g, "a": a, "v": v} for (g, a), v in sorted(chosen.items())],
              open(chosen_path, "w"), indent=1)

    def dl(item):
        (g, a), v = item
        for ext in ("aar", "jar"):
            data = try_fetch(g, a, v, ext)
            if data:
                fn = "%s__%s__%s.%s" % (g, a, v, ext)
                p = os.path.join(CLEAN, fn)
                if not os.path.exists(p) or os.path.getsize(p) != len(data):
                    open(p, "wb").write(data)
                return fn, len(data)
        return None, 0

    t1 = time.time()
    ok, bad = 0, []
    with ThreadPoolExecutor(max_workers=16) as ex:
        for i, (fn, size) in enumerate(ex.map(dl, list(chosen.items()))):
            if fn:
                ok += 1
            else:
                g, a = list(chosen.items())[i][0]
                bad.append("%s:%s:%s" % (g, a, list(chosen.values())[i]))
            if (i + 1) % 40 == 0:
                print("  downloaded %d/%d" % (i + 1, len(chosen)), flush=True)
    print("DOWNLOAD DONE %.0fs, ok=%d missing=%d" % (time.time() - t1, ok, len(bad)))
    for b in bad[:12]:
        print("   MISSING", b)

    # ---- stage 3: KMP variant fix -------------------------------------------
    def has_classes(path):
        try:
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
                if path.endswith(".aar"):
                    return "classes.jar" in names
                return any(n.endswith(".class") for n in names)
        except Exception:
            return False

    broken = []
    for f in sorted(os.listdir(CLEAN)):
        if not f.endswith((".aar", ".jar")):
            continue
        p = os.path.join(CLEAN, f)
        try:
            g, a, v = f.rsplit(".", 1)[0].split("__")[:3]
        except ValueError:
            continue
        if not has_classes(p):
            broken.append((g, a, v))
    print("\nartifacts without classes: %d" % len(broken))

    def fix(item):
        g, a, v = item
        if a.endswith("-android"):
            # android artifact without classes: try the -jvm sibling
            cands = [a + "-jvm"]
        else:
            cands = [a + "-android", a + "-jvm"]
        for cand in cands:
            for e in ("aar", "jar"):
                data = try_fetch(g, cand, v, e)
                if data:
                    fn = "%s__%s__%s.%s" % (g, cand, v, e)
                    open(os.path.join(CLEAN, fn), "wb").write(data)
                    if has_classes(os.path.join(CLEAN, fn)):
                        return cand
        return None

    with ThreadPoolExecutor(max_workers=16) as ex:
        for orig, got in zip(broken, ex.map(fix, broken)):
            print("   %s:%s -> %s" % (orig[1], orig[2], got or "STILL EMPTY"))

# ---- stage 4: dedup ---------------------------------------------------------
EXCLUDE = ("-lint", "-tooling", "ui-test", "-junit4", "-google-fonts", "viewbinding",
           "themeadapter", "-samples", "-benchmark", "kotlin-stdlib", "kotlin-dom",
           "lifecycle-common-java8")  # 2.3.0 classes (DefaultLifecycleObserver extends
           # FullLifecycleObserver) poison the 2.11.0 set at runtime
SKIP_GRP = ("org.jetbrains.kotlin",)  # stdlib comes standalone

def classes_of(path):
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if path.endswith(".aar"):
                if "classes.jar" not in names:
                    return set(), None
                inner = z.read("classes.jar")
                with zipfile.ZipFile(io.BytesIO(inner)) as zj:
                    return {n for n in zj.namelist() if n.endswith(".class")}, inner
            return {n for n in names if n.endswith(".class")}, None
    except Exception:
        return set(), None

def module_of(filename):
    parts = filename.rsplit(".", 1)[0].split("__")
    return (parts[0], parts[1], parts[2]) if len(parts) >= 3 else (filename, "", "")

def priority(filename):
    g, a, v = module_of(filename)
    score = 0
    if a.endswith("-android"): score += 100
    if "androidx.compose" in g or "androidx.compose" in a: score += 50
    if "org.jetbrains.compose" in g: score += 40
    if "top.yukonga.miuix" in g: score += 60
    if any(x in a for x in ("-bom", "-parent", ".androidx")): score -= 50
    if "material3" in a: score += 20
    if a in ("ui", "runtime", "foundation", "material", "material3"): score += 30
    return score

if os.path.isdir(DEDUP):
    shutil.rmtree(DEDUP)
os.makedirs(DEDUP)

files = sorted(f for f in os.listdir(CLEAN) if f.endswith((".aar", ".jar")))
files = [f for f in files if not any(x in f for x in EXCLUDE)
         and not any(f.startswith(sg + "__") for sg in SKIP_GRP)]


def ver_key2(v):
    parts = re.findall(r"\d+|[A-Za-z]+", v)
    return [(0, int(p)) if p.isdigit() else (1, p) for p in parts]


# Version filter: same (group, base artifact) keeps only the highest version
# (-android preferred). Stale bare KMP jars (compose 1.9.2 etc.) would other-
# wise leak old classes into the fat jar next to the 1.12.0 ones.
_by_base = {}
for f in files:
    g, a, v = module_of(f)
    base = a[:-len("-android")] if a.endswith("-android") else a
    k = (ver_key2(v), 1 if a.endswith("-android") else 0)
    key = (g, base)
    if key not in _by_base or k > _by_base[key][0]:
        _by_base[key] = (k, f)
files = [f for f in files if _by_base.get(
    (module_of(f)[0],
     module_of(f)[1][:-len("-android")] if module_of(f)[1].endswith("-android") else module_of(f)[1]),
    (None, None))[1] == f]
print("\ndedup candidates:", len(files))

info = {}
for f in files:
    cls, inner = classes_of(os.path.join(CLEAN, f))
    if cls:
        info[f] = (cls, inner)
print("with classes:", len(info))

order = sorted(info.keys(), key=lambda f: (-priority(f), f))
owner = {}
for f in order:
    for c in info[f][0]:
        owner.setdefault(c, f)

def pkg_of(classname):
    return classname.rsplit("/", 1)[0]

pkg_index = {}
for f in order:
    g, a, v = module_of(f)
    mod = a.replace("-android", "")
    for c in info[f][0]:
        p = pkg_of(c)
        base = mod.replace("-", ".").replace(".", "")
        if p.replace("/", ".").replace(".", "").endswith(base):
            pkg_index.setdefault(p, f)

for c in list(owner):
    p = pkg_of(c)
    better = pkg_index.get(p)
    # Only hand a class to the package-owner artifact if that artifact
    # actually defines it; otherwise the class would be dropped entirely
    # (e.g. androidx/compose/ui/graphics classes defined in ui-android but
    # the package claimed by ui-graphics-android).
    if better and better != owner[c] and better in info and c in info[better][0]:
        owner[c] = better

print("unique classes:", len(owner))

written = []
for f in order:
    mine = [c for c in info[f][0] if owner.get(c) == f]
    if not mine:
        continue
    name = f.replace(".aar", ".jar") if f.endswith(".aar") else f
    outjar = os.path.join(DEDUP, name)
    inner = info[f][1]
    src = zipfile.ZipFile(io.BytesIO(inner)) if inner else zipfile.ZipFile(os.path.join(CLEAN, f))
    with zipfile.ZipFile(outjar, "w", zipfile.ZIP_DEFLATED) as zout:
        for c in mine:
            zout.writestr(c, src.read(c))
    src.close()
    written.append(name)

print("emitted %d jars, %.1f MB" % (
    len(written), sum(os.path.getsize(os.path.join(DEDUP, w)) for w in written) / 1048576))
json.dump(written, open(os.path.join(DEDUP, "manifest.json"), "w"), indent=1)

# ---- stage 5: fat jar (classes + kotlin_module) -----------------------------
def module_files(path):
    out = {}
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if path.endswith(".aar"):
                if "classes.jar" not in names:
                    return out
                with zipfile.ZipFile(io.BytesIO(z.read("classes.jar"))) as zj:
                    for n in zj.namelist():
                        if n.endswith(".kotlin_module"):
                            out[n] = zj.read(n)
            else:
                for n in names:
                    if n.endswith(".kotlin_module"):
                        out[n] = z.read(n)
    except Exception:
        pass
    return out

seen = set()
stats = {"class": 0, "module": 0}
with zipfile.ZipFile(FAT, "w", zipfile.ZIP_DEFLATED) as zout:
    for j in sorted(os.listdir(DEDUP)):
        if not j.endswith(".jar"):
            continue
        with zipfile.ZipFile(os.path.join(DEDUP, j)) as z:
            for n in z.namelist():
                if not n.endswith(".class") or n in seen:
                    continue
                seen.add(n)
                zout.writestr(n, z.read(n))
                stats["class"] += 1
    for f in sorted(os.listdir(CLEAN)):
        if not f.endswith((".aar", ".jar")):
            continue
        if any(x in f for x in EXCLUDE) or any(f.startswith(sg + "__") for sg in SKIP_GRP):
            continue
        for n, data in module_files(os.path.join(CLEAN, f)).items():
            if n in seen:
                continue
            seen.add(n)
            zout.writestr(n, data)
            stats["module"] += 1

print("\nFAT JAR: %s" % FAT)
print("classes: %d, kotlin_module: %d, size: %.1f MB" % (
    stats["class"], stats["module"], os.path.getsize(FAT) / 1048576))

# ---- essentials -------------------------------------------------------------
need = [
    "top/yukonga/miuix/kmp/theme/MiuixTheme",
    "top/yukonga/miuix/kmp/nav/core/NavDisplay",
    "top/yukonga/miuix/kmp/blur/TextureBlurKt",
    "top/yukonga/miuix/kmp/basic/TextKt",
    "androidx/compose/ui/Modifier.class",
    "androidx/compose/runtime/Composer.class",
    "androidx/compose/foundation/layout/ColumnKt.class",
    "androidx/activity/compose/ContentKt",
    "androidx/lifecycle/compose/FlowCollectAsStateKt",
    "kotlinx/serialization/json/Json",
]
have = set()
for w in written:
    with zipfile.ZipFile(os.path.join(DEDUP, w)) as z:
        have |= set(z.namelist())
print("\n--- essentials ---")
for n in need:
    hit = any(x.startswith(n) for x in have)
    if not hit:   # case-insensitive filename match within the package dir
        pkg, name = n.rsplit("/", 1)
        hit = any(x.startswith(pkg + "/") and x.rsplit("/", 1)[1].lower().startswith(name.lower())
                  for x in have)
    print("  %-60s %s" % (n, "OK" if hit else "MISS"))
