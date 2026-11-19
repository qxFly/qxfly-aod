#!/usr/bin/env python3
"""Stage the APK: copy aapt2 output, force resources.arsc STORED, add dex + xposed meta.

Android 11+ refuses to install an APK whose targetSdkVersion >= 30 unless
resources.arsc is stored uncompressed and 4-byte aligned. aapt2 link does not
enforce this by itself (it keeps the entry DEFLATED), and zipalign only aligns
entries that are already uncompressed - so the compression decision has to be
made here, explicitly, while writing the staged zip.
"""
import os
import sys
import zipfile


def collect_dex(dex_path: str) -> list:
    """Return [(name, path)] for classes.dex / classes2.dex / ...

    Accepts either a single dex file or the d8 output directory. Compose-sized
    builds exceed the 64K-method limit, so d8 emits several dex files and all of
    them must be packaged or the app fails to start with NoClassDefFoundError.
    """
    if os.path.isdir(dex_path):
        found = sorted(n for n in os.listdir(dex_path) if n.endswith(".dex"))
        return [(n, os.path.join(dex_path, n)) for n in found]
    return [("classes.dex", dex_path)]


def main() -> int:
    base_apk, out_apk, dex_path, xposed_dir = sys.argv[1:5]

    dex_entries = collect_dex(dex_path)
    if not dex_entries:
        print("ERROR: no dex found at " + dex_path, file=sys.stderr)
        return 2

    with zipfile.ZipFile(base_apk) as zin:
        with zipfile.ZipFile(out_apk, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "resources.arsc":
                    # Must be STORED; zipalign fixes the 4-byte boundary later.
                    info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                    info.compress_type = zipfile.ZIP_STORED
                    info.external_attr = item.external_attr
                    zout.writestr(info, data)
                else:
                    zout.writestr(item, data)

            for name, path in dex_entries:
                with open(path, "rb") as f:
                    zout.writestr(name, f.read(), zipfile.ZIP_DEFLATED)

            for root, _dirs, files in os.walk(xposed_dir):
                for name in sorted(files):
                    path = os.path.join(root, name)
                    rel = os.path.relpath(path, xposed_dir).replace(os.sep, "/")
                    with open(path, "rb") as f:
                        zout.writestr("META-INF/xposed/" + rel, f.read(),
                                      zipfile.ZIP_DEFLATED)

    print("staged -> %s (%d dex: %s)" % (
        out_apk, len(dex_entries), ", ".join(n for n, _ in dex_entries)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
