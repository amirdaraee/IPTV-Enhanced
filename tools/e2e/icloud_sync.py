#!/usr/bin/env python3
"""End-to-end iCloud sync test: two iOS simulators, signed in to the same iCloud account.

    python3 tools/fixture-server/server.py &
    python3 tools/e2e/icloud_sync.py <simulator-a> <simulator-b> <path/to/Tuner.app>

Build the app for the simulator with DEVELOPMENT_TEAM set, or it has no iCloud permission.
Safe on a real account: it only touches a playlist named "E2E Test" and deletes it at the end.
It doesn't test resets, wipes or PINs, which would reach the account's real devices; the core
tests (CloudSyncTest) cover those.
"""
import json
import subprocess
import sys
import time

BUNDLE = "com.amirdaraee.iptv"
SERVER = "http://localhost:8900"
SYNC_TIMEOUT = 300


def simctl(*args, check=True):
    return subprocess.run(["xcrun", "simctl", *args], capture_output=True, text=True, check=check).stdout


class Device:
    def __init__(self, name, udid):
        self.name, self.udid = name, udid

    def launch(self, *args):
        simctl("launch", "--terminate-running-process", self.udid, BUNDLE, "-syncTest", *args)
        self.launched = time.time()

    def state(self):
        path = simctl("get_app_container", self.udid, BUNDLE, "data").strip()
        try:
            with open(f"{path}/Documents/e2e-state.json") as f:
                state = json.load(f)
        except (OSError, ValueError):
            return None
        # A file left by the previous launch says nothing about this one.
        return state if state["at"] > self.launched else None


def wait(what, device, others, condition, timeout=SYNC_TIMEOUT):
    start = time.time()
    next_sync = 0
    state = None
    while time.time() - start < timeout:
        state = device.state()
        if state and condition(state):
            print(f"  ok   {what} ({time.time() - start:.0f} s)", flush=True)
            return
        if time.time() > next_sync:
            for d in others:
                simctl("icloud_sync", d.udid, check=False)
            next_sync = time.time() + 15
        time.sleep(2)
    raise AssertionError(f"{what}: not true after {timeout} s, {device.name} state: {state}")


def stays(what, device, condition, seconds, others):
    end = time.time() + seconds
    while time.time() < end:
        for d in others:
            simctl("icloud_sync", d.udid, check=False)
        state = device.state()
        assert not state or condition(state), f"{what}: {device.name} state: {state}"
        time.sleep(5)
    print(f"  ok   {what} (held for {seconds} s)", flush=True)


def favorites(s):
    return s.get("favorites", [])


def main():
    a, b = Device("A", sys.argv[1]), Device("B", sys.argv[2])
    both = [a, b]
    for d in both:
        simctl("install", d.udid, sys.argv[3])
    failed = None
    real = None
    try:
        print("playlist and password reach a new device", flush=True)
        a.launch("-syncTestPlaylist", SERVER)
        wait("A added the test playlist and synced it", a, both, lambda s: s.get("synced"), 120)
        real = a.state()["otherPlaylists"]
        b.launch()
        wait("B received the playlist", b, both, lambda s: s["hasE2E"])
        wait("B received its password", b, both, lambda s: s.get("passwordMatches"))
        wait("B synced its channels from the playlist", b, both, lambda s: s.get("synced"), 120)

        print("favorites both ways", flush=True)
        a.launch("-syncTestFavorite", "e2e-one")
        wait("favorite added on A reaches B", b, both, lambda s: "e2e-one" in favorites(s))
        b.launch("-syncTestUnfavorite", "e2e-one")
        wait("favorite removed on B leaves A", a, both, lambda s: "e2e-one" not in favorites(s) and s["hasE2E"])

        print("watch progress", flush=True)
        a.launch("-syncTestProgress", "600000")
        wait("position saved on A reaches B", b, both, lambda s: s.get("progressMs") == 600000)

        print("off switch", flush=True)
        a.launch("-syncTestOff")
        wait("A is off", a, [], lambda s: not s["cloudOn"], 30)
        a.launch("-syncTestFavorite", "e2e-from-off")
        b.launch("-syncTestFavorite", "e2e-from-b")
        wait("B's favorite is saved", b, both, lambda s: "e2e-from-b" in favorites(s), 30)
        stays("nothing crosses while A is off", b, lambda s: "e2e-from-off" not in favorites(s), 60, both)
        s = a.state()
        assert s and "e2e-from-b" not in favorites(s), f"A received data while off: {s}"
        a.launch("-syncTestOn")
        wait("switching on brings B's favorite to A", a, both, lambda s: "e2e-from-b" in favorites(s))
        wait("and A's favorite to B", b, both, lambda s: "e2e-from-off" in favorites(s))
    except AssertionError as e:
        failed = e
        print(f"  FAIL {e}", flush=True)

    print("deleting the test playlist", flush=True)
    # Cleanup runs after a failure too, so nothing is left on the account's real devices.
    a.launch("-syncTestOn", "-syncTestRemove")
    try:
        wait("gone from A", a, both, lambda s: not s["hasE2E"], 60)
        b.launch()
        wait("deletion reaches B", b, both, lambda s: not s["hasE2E"])
        stays("and doesn't come back", a, lambda s: not s["hasE2E"], 45, both)
        if real is not None:
            s = a.state()
            assert s["otherPlaylists"] == real, f"real playlists changed: {real} -> {s['otherPlaylists']}"
            print(f"  ok   other playlists untouched ({real})", flush=True)
    except AssertionError as e:
        failed = failed or e
        print(f"  FAIL cleanup: {e}", flush=True)

    print("PASS" if failed is None else "FAILED")
    return 0 if failed is None else 1


if __name__ == "__main__":
    sys.exit(main())
