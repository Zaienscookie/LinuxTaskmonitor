#!/usr/bin/env python3
"""
LinuxTaskmonitor - Dependency & Build helper.

Downloads JAR dependencies (JSch, Gson) and builds the project.
Run: python setup.py
"""
import os, subprocess, sys, urllib.request

REQUIRED_JDK = 17
LIB_DIR = "lib"
SRC_DIR = "src/main/java/com/linuxmonitor"
DEPS = [
    ("jsch-0.1.55.jar",
     "https://repo1.maven.org/maven2/com/jcraft/jsch/0.1.55/jsch-0.1.55.jar"),
    ("gson-2.10.1.jar",
     "https://repo1.maven.org/maven2/com/google/code/gson/gson-2.10.1/gson-2.10.1.jar"),
]


def check_java():
    try:
        out = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True)
        for line in out.splitlines():
            if "version" in line:
                v = line.strip()
                print(f"  Java: {v}")
                parts = v.split()
                for p in parts:
                    if p[0].isdigit():
                        ver = int(p.split(".")[0])
                        if ver >= REQUIRED_JDK:
                            return True
                        print(f"  [!] Need JDK {REQUIRED_JDK}+, found {ver}")
                        return False
        return False
    except FileNotFoundError:
        print("  [!] Java not found. Install JDK 17+.")
        return False


def download_deps():
    os.makedirs(LIB_DIR, exist_ok=True)
    for name, url in DEPS:
        path = os.path.join(LIB_DIR, name)
        if os.path.exists(path):
            print(f"  [OK] {name}")
            continue
        print(f"  [DL] {name}...")
        try:
            urllib.request.urlretrieve(url, path)
            print(f"  [OK] {name}")
        except Exception as e:
            print(f"  [FAIL] {name}: {e}")
            return False
    return True


def build():
    cp = ";".join(os.path.join(LIB_DIR, d[0]) for d in DEPS)
    out = "target/classes"
    os.makedirs(out, exist_ok=True)

    src_files = []
    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith(".java"):
                src_files.append(os.path.join(root, f))

    if not src_files:
        print("  [!] No source files found")
        return False

    cmd = ["javac", "-cp", cp, "-d", out, "-encoding", "UTF-8"] + src_files
    print(f"  [BUILD] Compiling {len(src_files)} sources...")
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  [FAIL] {r.stderr}")
        return False
    print(f"  [OK] Compiled to {out}")
    return True


def main():
    print("=" * 50)
    print("  LinuxTaskmonitor Setup")
    print("=" * 50)
    print()
    ok = True

    print("[1/3] Checking Java...")
    ok = check_java() and ok

    print()
    print("[2/3] Downloading dependencies...")
    ok = download_deps() and ok

    print()
    print("[3/3] Building...")
    ok = build() and ok

    print()
    if ok:
        print("  All done! Run: run.bat")
    else:
        print("  Some steps failed. Check messages above.")
        sys.exit(1)


if __name__ == "__main__":
    main()