#!/usr/bin/env python3
"""The x86_64 proof: the 35 conformance clips through the Android library on an x86_64 emulator.

  python scripts/emulator_proof.py --avd <an x86_64 AVD> --model <stored 3.0 model folder> --clips <folder of .pcm clips>
      [--key <the folder's public key, hex>] [--conformance <the engine's conformance folder>] [--out <folder for logs>]

The library's x86_64 ABI rests on this run (SdkInfo.ABIS): one bit of difference in one output of one chunk on one
clip fails it. What it does, in order:

1. Serves the model folder (the stored layout, as the CDN has it), the clips and the engine's expected rows on
   127.0.0.1:8791 (scripts/serve_model.py, loopback only).
2. Starts the AVD without a window, read-only (its own images are not written), with the software renderer, on
   port 5584, and waits until it has booted (at most 5 minutes).
3. `adb -s emulator-5584 reverse tcp:8791 tcp:8791`, then `gradlew :tajweedformer:connectedDebugAndroidTest
   -PnativeRelease` for the x86_64 ABI only, with ANDROID_SERIAL=emulator-5584: the test runs on the emulator ONLY,
   whatever phone is attached to the PC. The test is DeviceEndToEndTest: the download with a cut and a resume, the
   verification, the CPU self test, then the 35 clips against the expected rows, all 12 heads of every chunk.
4. Prints the test's own lines (logcat tag TJ_DEVICE), saves them and the Gradle output in --out, then stops the
   emulator and the server: the two processes this script started. The emulator is asked to stop (`adb emu kill`)
   and given a minute to do it; only then is its process tree killed.
5. Says what the emulator left behind. A read-only AVD runs on temporary copies of its disk images (TMP*.qcow2,
   about 400 MB a run) in the emulator's temp folder (<temp>/AndroidEmulator), and an emulator that crashes or is
   killed leaves them there, with a crash dump of about 160 MB. The script lists, with their sizes, the files that
   appeared in that folder during its run. It deletes nothing (another emulator may be running): delete the listed
   files yourself. The first runs left 2 GB behind on a nearly full disk. This step and the minute of waiting were
   added after those runs and have not been run.

Exit code 0 when the test passed.

The emulator itself is not stable on every PC: with its default renderer and no window it exited at once with
0xC0000005 on the Windows PC of the first runs; with the software renderer (what this script passes) it booted and
the test ran to its end, twice. A crash of the emulator is not a result of the test: the script then exits 1.

Tried and rejected: `connectedAndroidTest` without ANDROID_SERIAL (it would also install on an attached phone); a
writable AVD (the proof must not change a developer's emulator image); the emulator's default renderer without a
window (it exited at once with 0xC0000005 on the Windows PC this was first run on).
"""
import argparse
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERIAL = "emulator-5584"
PORT = "8791"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--avd", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--clips", required=True)
    ap.add_argument("--key", default=None)
    ap.add_argument("--conformance", default=os.path.join(ROOT, "engine", "conformance"))
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "emulator-proof"))
    a = ap.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = os.path.join(ROOT, "local.properties")
        if os.path.isfile(local):
            for line in open(local, encoding="utf-8"):
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        sys.exit("the Android SDK is not known: set ANDROID_HOME, or sdk.dir in local.properties")
    exe = ".exe" if os.name == "nt" else ""
    adb_path = os.path.join(sdk, "platform-tools", "adb" + exe)
    emulator_path = os.path.join(sdk, "emulator", "emulator" + exe)
    os.makedirs(a.out, exist_ok=True)

    def adb(*args, timeout=60):
        return subprocess.run([adb_path, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout)

    def kill(p):
        if p and p.poll() is None:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"], capture_output=True)
            else:
                p.kill()

    # what the emulator's temp folder holds before the run: only what appears during it is this run's to remove
    import tempfile
    temp = os.path.join(tempfile.gettempdir(), "AndroidEmulator")

    def listing():
        found = set()
        if os.path.isdir(temp):
            for folder, _, names in os.walk(temp):
                for n in names:
                    found.add(os.path.join(folder, n))
        return found

    before = listing()
    server = emulator = None
    rc = 1
    try:
        server = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts", "serve_model.py"), "--port", PORT,
                                   f"/m={a.model}", f"/audio={a.clips}", f"/conformance={a.conformance}"],
                                  stdout=open(os.path.join(a.out, "server.log"), "w"), stderr=subprocess.STDOUT)
        emulator = subprocess.Popen([emulator_path, "-avd", a.avd, "-port", "5584", "-no-window", "-no-audio", "-no-boot-anim",
                                     "-read-only", "-no-snapshot", "-gpu", "swiftshader_indirect"],
                                    stdout=open(os.path.join(a.out, "emulator.log"), "w"), stderr=subprocess.STDOUT)
        t0 = time.time()
        booted = False
        while time.time() - t0 < 300:
            if emulator.poll() is not None:
                print("the emulator exited with", emulator.returncode, flush=True)
                break
            try:
                if adb("shell", "getprop", "sys.boot_completed", timeout=15).stdout.strip() == "1":
                    booted = True
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(5)
        print(f"booted: {booted} after {time.time() - t0:.0f} s", flush=True)
        if booted:
            print("abi:", adb("shell", "getprop", "ro.product.cpu.abilist").stdout.strip(), flush=True)
            adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
            adb("logcat", "-c")
            gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
            cmd = ([gradlew] if os.name != "nt" else ["cmd", "/c", gradlew]) + [
                "-p", ROOT, "--console=plain", "--no-daemon", ":tajweedformer:connectedDebugAndroidTest", "-PnativeRelease",
                "-Pandroid.injected.build.abi=x86_64", f"-Pandroid.testInstrumentationRunnerArguments.host=http://127.0.0.1:{PORT}"]
            if a.key:
                cmd.append(f"-Pandroid.testInstrumentationRunnerArguments.key={a.key}")
            with open(os.path.join(a.out, "gradle.log"), "w", encoding="utf-8", errors="replace") as f:
                try:
                    rc = subprocess.call(cmd, env=dict(os.environ, ANDROID_SERIAL=SERIAL), stdout=f, stderr=subprocess.STDOUT,
                                         cwd=ROOT, timeout=1500)
                except subprocess.TimeoutExpired:
                    print("the test did not finish in 25 minutes", flush=True)
            log = adb("logcat", "-d", "-s", "TJ_DEVICE:I", timeout=60).stdout
            open(os.path.join(a.out, "device.log"), "w", encoding="utf-8").write(log)
            for line in log.splitlines():
                if "TJ_DEVICE" in line:
                    print(line.split("TJ_DEVICE:", 1)[-1].strip()[:400], flush=True)
    finally:
        try:
            adb("emu", "kill", timeout=20)
        except Exception:
            pass
        # a minute to stop by itself: an emulator that is killed leaves its temporary disk images and a crash dump
        waited = time.time()
        while emulator and emulator.poll() is None and time.time() - waited < 60:
            time.sleep(2)
        kill(emulator)
        kill(server)
        # the temporary disk images and crash reports that appeared during this run
        try:
            time.sleep(2)
            new = sorted(p for p in listing() - before
                         if os.path.basename(p).upper().startswith("TMP") or os.sep + "reports" + os.sep in p or os.sep + "attachments" + os.sep in p)
            for p in new:
                print(f"left in the emulator's temp folder during this run, delete it when no emulator runs: {p} ({os.path.getsize(p) >> 20} MB)", flush=True)
        except Exception as e:
            print("could not look at the emulator's temp folder:", e, flush=True)
    print("gradle exit", rc, flush=True)
    sys.exit(rc)


if __name__ == "__main__":
    main()
