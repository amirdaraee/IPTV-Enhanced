#!/usr/bin/env python3
"""
Local fixture server for Tuner end-to-end testing. Python stdlib only.

  python3 tools/fixture-server/server.py [--port 8900]

Endpoints (the host in generated URLs follows the request's Host header, so the
Android emulator can use http://10.0.2.2:8900 and the tvOS simulator http://localhost:8900):

  /playlist.m3u                 M3U with 40 live channels, x-tvg-url and catch-up attrs, plus 6 VOD entries
  /epg.xml.gz                   gzipped XMLTV covering now-2d .. now+3d
  /player_api.php               fake Xtream Codes API (any username/password except password "bad")
  /xmltv.php                    same XMLTV, uncompressed
  username "big"                Xtream account at large-provider scale (21.7k channels, 103k movies,
                                25k series, 200k programmes) for performance testing
  /live/<u>/<p>/<id>.m3u8|.ts   live channel as sliding-window HLS or continuous MPEG-TS
  /movie/<u>/<p>/<id>.mp4       VOD file (supports Range)
  /series/<u>/<p>/<id>.mp4      episode file
  /timeshift/<u>/<p>/<min>/<start>/<id>.ts   archive (serves the loop once)
  /hls/<n>/live.m3u8            real-time live HLS produced by ffmpeg from media/ch<n>.mp4
"""
import argparse
import gzip
import atexit
import shutil
import signal
import socket
import subprocess
import json
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(ROOT, "media")
LIVE = os.path.join(MEDIA, "live")
SEG_DUR = 2.0

CATEGORIES = [("1", "News"), ("2", "Sports"), ("3", "Movies & Drama"), ("4", "Kids")]
NAMES = {
    "1": ["Daily News", "World Report", "Business Live", "Weather Now", "Tech Today", "Politics Hour", "Local 7", "Global 24", "Headline", "Info Channel"],
    "2": ["Sports One", "Football TV", "Tennis Plus", "Racing HD", "Fairway TV", "Fight Night", "Basket 24", "Extreme", "Medal Run", "Arena"],
    "3": ["Big Screen", "Drama Box", "Classic Films", "Thriller TV", "Romance", "Star Frontier", "Comedy Hub", "Docu World", "Past Times", "Wild Lands"],
    "4": ["Toon Time", "Kids Club", "Junior", "Family TV", "Learn & Play", "Anime Zone", "Puppets", "Story Hour", "Teen Hits", "Little Ones"],
}
CHANNELS = []
for cat, _ in CATEGORIES:
    for i, n in enumerate(NAMES[cat]):
        sid = len(CHANNELS) + 1
        CHANNELS.append({"id": sid, "name": n, "cat": cat, "variant": sid % 4, "archive": cat in ("1", "2"), "epg": f"ch{sid}.test"})
MOVIES = [(500 + i, t, "3") for i, t in enumerate(["The Long Thaw", "Blue Horizon", "Iron Valley", "Paper Lanterns", "Last Signal", "Quiet Harbor"])]
SERIES = {
    "900": ("Harbor Lights", [(1, 1, "Pilot"), (1, 2, "Arrival"), (1, 3, "Storm"), (2, 1, "Return"), (2, 2, "Finale")]),
    "901": ("Kitchen Wars", [(1, 1, "Heat"), (1, 2, "Salt")]),
}
SHOWS = ["Morning Brief", "Live Coverage", "Evening Edition", "Night Desk", "Documentary", "Match Day", "Highlights", "Movie Premiere", "Cartoon Block", "Quiz Night"]


# ------------------------------------------------------------------ large provider (username "big")

BIG_WORDS = ["News", "Sport", "The", "Movie", "France", "Kids", "Music", "World", "Drama", "Live", "Cinema", "Family"]
BIG_EPG_CHANNELS = 2000
_big_cache = {}


def big_title(i):
    return f"{BIG_WORDS[i % len(BIG_WORDS)]} {BIG_WORDS[(i // 7) % len(BIG_WORDS)]} {i}"


def big(action):
    """Catalogs at the scale of a large commercial provider, generated once and cached."""
    if action in _big_cache:
        return _big_cache[action]
    if action == "get_live_categories":
        out = [{"category_id": str(i), "category_name": f"LIVE {big_title(i)}"} for i in range(1, 301)]
    elif action == "get_vod_categories":
        out = [{"category_id": str(i), "category_name": f"VOD {big_title(i)}"} for i in range(1, 151)]
    elif action == "get_series_categories":
        out = [{"category_id": str(i), "category_name": f"SERIES {big_title(i)}"} for i in range(1, 81)]
    elif action == "get_live_streams":
        out = [{"num": i + 1, "name": f"{big_title(i)} HD", "stream_type": "live", "stream_id": i + 1, "stream_icon": "",
                "epg_channel_id": f"big{i % BIG_EPG_CHANNELS}.test" if i % 10 < 9 else None, "added": "1700000000",
                "category_id": str(i % 300 + 1), "tv_archive": 1 if i % 3 == 0 else 0, "tv_archive_duration": 2 if i % 3 == 0 else 0}
               for i in range(21_700)]
    elif action == "get_vod_streams":
        out = [{"num": i + 1, "name": f"{big_title(i)} (20{i % 25:02d})", "stream_type": "movie", "stream_id": 100_000 + i, "stream_icon": "",
                "rating": f"{i % 10}.5", "added": str(1700000000 + i), "category_id": str(i % 150 + 1), "container_extension": "mp4"}
               for i in range(103_000)]
    elif action == "get_series":
        out = [{"num": i + 1, "name": big_title(i), "series_id": 900_000 + i, "cover": "", "plot": f"A plot about {big_title(i)}.",
                "genre": "Drama", "releaseDate": "2020-01-01", "rating": "7", "category_id": str(i % 80 + 1), "last_modified": "1700000000"}
               for i in range(25_000)]
    elif action == "xmltv":
        now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
        start, fmt = now - timedelta(hours=36), "%Y%m%d%H%M%S +0000"
        parts = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="tuner-fixture-big">']
        parts += [f'<channel id="big{c}.test"><display-name>{big_title(c)} HD</display-name></channel>' for c in range(BIG_EPG_CHANNELS)]
        for c in range(BIG_EPG_CHANNELS):
            t = start
            for p in range(100):
                e = t + timedelta(minutes=45)
                parts.append(f'<programme start="{t.strftime(fmt)}" stop="{e.strftime(fmt)}" channel="big{c}.test"><title>{big_title(p * 31 + c)}</title>'
                             f'<desc>Episode {p} of {big_title(c)}: a longer description with some text.</desc><category>Cat</category></programme>')
                t = e
        parts.append("</tv>")
        _big_cache[action] = "\n".join(parts).encode()
        return _big_cache[action]
    else:
        out = []
    _big_cache[action] = json.dumps(out).encode()
    return _big_cache[action]


def segments(variant):
    d = os.path.join(MEDIA, f"ch{variant}")
    return sorted(f for f in os.listdir(d) if f.endswith(".ts")) if os.path.isdir(d) else []


def xmltv_bytes():
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    start = now - timedelta(days=2)
    fmt = "%Y%m%d%H%M%S +0000"
    out = ['<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE tv SYSTEM "xmltv.dtd">\n<tv generator-info-name="tuner-fixture">']
    for c in CHANNELS:
        out.append(f'<channel id="{c["epg"]}"><display-name>{c["name"]}</display-name></channel>')
    for c in CHANNELS:
        t = start
        k = c["id"]
        while t < now + timedelta(days=3):
            dur = timedelta(minutes=30 if (k + t.hour) % 3 else 60)
            title = SHOWS[(k + t.hour + t.minute // 30) % len(SHOWS)]
            out.append(
                f'<programme start="{t.strftime(fmt)}" stop="{(t + dur).strftime(fmt)}" channel="{c["epg"]}">'
                f'<title lang="en">{title}</title><desc lang="en">{title} on {c["name"]} &amp; friends. '
                f'An episode about {["space", "food", "history", "sport", "music"][k % 5]}.</desc>'
                f'<category lang="en">{["News", "Sports", "Movie", "Kids"][int(c["cat"]) - 1]}</category></programme>'
            )
            t += dur
            k += 1
    out.append("</tv>")
    return "\n".join(out).encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path[:160]))

    @property
    def base(self):
        return f"http://{self.headers.get('Host', 'localhost:8900')}"

    def send(self, body, ctype="application/octet-stream", status=200, extra=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        path, q = url.path, {k: v[0] for k, v in parse_qs(url.query).items()}
        try:
            if path == "/playlist.m3u":
                return self.send(self.m3u(), "audio/x-mpegurl")
            if path == "/epg.xml.gz":
                return self.send(gzip.compress(xmltv_bytes()), "application/gzip")
            if path == "/xmltv.php":
                return self.send(big("xmltv") if q.get("username") == "big" else xmltv_bytes(), "application/xml")
            if path == "/player_api.php":
                return self.xtream(q)
            m = re.match(r"^/hls/(\d+)/live\.m3u8$", path)
            if m:
                return self.live_playlist(int(m.group(1)))
            m = re.match(r"^/hls/(\d+)/(live\d+\.ts)$", path)
            if m:
                return self.file(os.path.join(LIVE, f"ch{m.group(1)}", m.group(2)), "video/mp2t")
            m = re.match(r"^/live/[^/]+/[^/]+/(\d+)\.(m3u8|ts)$", path)
            if m:
                ch = next((c for c in CHANNELS if c["id"] == int(m.group(1))), None)
                if not ch:
                    # Large-provider channels share the four test streams.
                    ch = {"variant": int(m.group(1)) % 4}
                if m.group(2) == "m3u8":
                    return self.live_playlist(ch["variant"])
                return self.live_ts(ch["variant"])
            if re.match(r"^/(movie|series)/[^/]+/[^/]+/\d+\.\w+$", path):
                return self.file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
            m = re.match(r"^/timeshift/[^/]+/[^/]+/\d+/[^/]+/(\d+)\.(ts|m3u8)$", path)
            if m:
                ch = next((c for c in CHANNELS if c["id"] == int(m.group(1))), CHANNELS[0])
                if m.group(2) == "m3u8":
                    return self.archive_hls(ch["variant"])
                return self.archive_ts(ch["variant"])
            m = re.match(r"^/vod/(\d+)/(seg\d+\.ts)$", path)
            if m:
                return self.file(os.path.join(MEDIA, f"ch{m.group(1)}", m.group(2)), "video/mp2t")
            if path == "/":
                return self.send(__doc__, "text/plain")
            return self.send("not found", "text/plain", 404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ---------------------------------------------------------------- playlists

    def m3u(self):
        lines = [f'#EXTM3U x-tvg-url="{self.base}/epg.xml.gz"']
        cats = dict(CATEGORIES)
        for c in CHANNELS:
            attrs = f'tvg-id="{c["epg"]}" tvg-name="{c["name"]}" tvg-chno="{100 + c["id"]}" group-title="{cats[c["cat"]]}"'
            if c["archive"]:
                attrs += ' catchup="append" catchup-days="2" catchup-source="?utc={utc}&lutc={lutc}"'
            lines.append(f"#EXTINF:-1 {attrs},{c['name']}")
            lines.append(f"{self.base}/hls/{c['variant']}/live.m3u8")
        for mid, title, _ in MOVIES:
            lines.append(f'#EXTINF:-1 tvg-logo="" group-title="Movies",{title}')
            lines.append(f"{self.base}/movie/m3u/m3u/{mid}.mp4")
        return "\n".join(lines) + "\n"

    def live_playlist(self, variant):
        fp = os.path.join(LIVE, f"ch{variant}", "live.m3u8")
        if not os.path.exists(fp):
            return self.send("live stream not running (needs ffmpeg and generate_media.sh)", "text/plain", 503)
        with open(fp, encoding="utf-8") as f:
            lines = f.read().splitlines()
        # Segment URIs are relative to /hls/<n>/; make them absolute so the playlist also
        # works when served from Xtream-style paths like /live/<user>/<pass>/<id>.m3u8.
        body = "\n".join(l if l.startswith("#") or not l else f"{self.base}/hls/{variant}/{l}" for l in lines) + "\n"
        return self.send(body, "application/vnd.apple.mpegurl", extra={"Cache-Control": "no-cache"})

    def live_ts(self, variant):
        segs = segments(variant)
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        i = int(time.time() / SEG_DUR)
        started = time.time()
        sent = 0
        while True:
            with open(os.path.join(MEDIA, f"ch{variant}", segs[i % len(segs)]), "rb") as f:
                self.wfile.write(f.read())
            self.wfile.flush()
            i += 1
            sent += 1
            # keep ~2 segments ahead of real time
            ahead = sent * SEG_DUR - (time.time() - started)
            if ahead > 2 * SEG_DUR:
                time.sleep(ahead - 2 * SEG_DUR)

    def archive_hls(self, variant):
        out = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{int(SEG_DUR)}", "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-MEDIA-SEQUENCE:0"]
        for seg in segments(variant):
            out += [f"#EXTINF:{SEG_DUR:.3f},", f"{self.base}/vod/{variant}/{seg}"]
        out.append("#EXT-X-ENDLIST")
        return self.send("\n".join(out) + "\n", "application/vnd.apple.mpegurl")

    def archive_ts(self, variant):
        data = b"".join(open(os.path.join(MEDIA, f"ch{variant}", s), "rb").read() for s in segments(variant))
        return self.send(data, "video/mp2t")

    def file(self, fp, ctype):
        if not os.path.exists(fp):
            return self.send("media missing: run generate_media.sh", "text/plain", 503)
        size = os.path.getsize(fp)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
        length = end - start + 1
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(fp, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    # ---------------------------------------------------------------- Xtream

    def xtream(self, q):
        j = lambda o: self.send(json.dumps(o), "application/json")
        if q.get("password") == "bad":
            return j({"user_info": {"auth": 0}})
        action = q.get("action")
        if q.get("username") == "big" and action not in (None, "get_vod_info", "get_series_info", "get_short_epg"):
            return self.send(big(action), "application/json")
        if action is None:
            return j({
                "user_info": {"username": q.get("username"), "auth": 1, "status": "Active", "exp_date": str(int(time.time()) + 86400 * 90),
                              "is_trial": "0", "active_cons": "0", "max_connections": "2", "allowed_output_formats": ["m3u8", "ts"]},
                "server_info": {"url": "localhost", "port": "8900", "server_protocol": "http", "timezone": "UTC",
                                "timestamp_now": int(time.time()), "time_now": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")},
            })
        if action == "get_live_categories":
            return j([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in CATEGORIES])
        if action == "get_live_streams":
            return j([{"num": c["id"], "name": c["name"], "stream_type": "live", "stream_id": c["id"], "stream_icon": "",
                       "epg_channel_id": c["epg"], "added": "1700000000", "category_id": c["cat"], "custom_sid": "",
                       "tv_archive": 1 if c["archive"] else 0, "direct_source": "", "tv_archive_duration": 2 if c["archive"] else 0}
                      for c in CHANNELS if q.get("category_id") in (None, c["cat"])])
        if action == "get_vod_categories":
            return j([{"category_id": "3", "category_name": "Feature Films", "parent_id": 0}])
        if action == "get_vod_streams":
            return j([{"num": i + 1, "name": t, "stream_type": "movie", "stream_id": mid, "stream_icon": "", "rating": "7.1",
                       "added": str(1700000000 + i * 3600), "category_id": cat, "container_extension": "mp4"} for i, (mid, t, cat) in enumerate(MOVIES)])
        if action == "get_vod_info":
            mid = int(q.get("vod_id", 0))
            title = next((t for m, t, _ in MOVIES if m == mid), "Unknown")
            return j({"info": {"plot": f"{title}: a generated test film with a sine-wave soundtrack.", "cast": "A. Actor, B. Actress",
                               "director": "C. Director", "genre": "Drama", "duration_secs": 300, "duration": "00:05:00",
                               "backdrop_path": [], "releasedate": "2024-01-01", "rating": "7.1"},
                      "movie_data": {"stream_id": mid, "name": title, "container_extension": "mp4"}})
        if action == "get_series_categories":
            return j([{"category_id": "20", "category_name": "Drama Series", "parent_id": 0}])
        if action == "get_series":
            return j([{"num": i + 1, "name": n, "series_id": int(sid), "cover": "", "plot": f"{n} — a test series.", "genre": "Drama",
                       "releaseDate": "2023-05-01", "rating": "8", "category_id": "20", "last_modified": "1700000000"} for i, (sid, (n, _)) in enumerate(SERIES.items())])
        if action == "get_series_info":
            sid = q.get("series_id", "")
            name, eps = SERIES.get(sid, ("?", []))
            seasons = {}
            for s, e, t in eps:
                seasons.setdefault(str(s), []).append({"id": f"{sid}{s}{e:02d}", "episode_num": e, "title": f"{t}", "container_extension": "mp4",
                                                      "season": s, "info": {"duration_secs": 300, "plot": f"Season {s}, episode {e} of {name}."}})
            return j({"seasons": [{"season_number": int(s)} for s in seasons], "info": {"name": name, "plot": f"{name} — a test series."}, "episodes": seasons})
        if action == "get_short_epg":
            return j({"epg_listings": []})
        return j([])


LIVE_SOURCES = [
    ("testsrc2=size=640x360:rate=25", 440),
    ("smptehdbars=size=640x360:rate=25", 554),
    ("mandelbrot=size=640x360:rate=25", 659),
    ("life=size=640x360:rate=25:mold=10:ratio=0.1:death_color=#202060:life_color=#40c0ff", 880),
    ("testsrc=size=640x360:rate=25", None),  # /hls/4: video only, for environments without audio output
]


def start_live_encoders(no_audio=False):
    """One real-time ffmpeg encoder per test channel writing a rolling live HLS window.
    Encoding live (rather than looping a file) gives continuous, monotonic timestamps,
    which AVPlayer requires."""
    if not shutil.which("ffmpeg"):
        print("ffmpeg not found: /hls live streams disabled", file=sys.stderr)
        return
    shutil.rmtree(LIVE, ignore_errors=True)
    procs = []
    for n, (video, freq) in enumerate(LIVE_SOURCES):
        out = os.path.join(LIVE, f"ch{n}")
        os.makedirs(out, exist_ok=True)
        # The realtime/arealtime filters pace the generators to wall-clock speed.
        inputs = ["-f", "lavfi", "-i", f"{video},realtime"]
        audio = ["-an"]
        if freq and not no_audio:
            inputs += ["-f", "lavfi", "-i", f"sine=frequency={freq}:sample_rate=48000,arealtime"]
            audio = ["-c:a", "aac", "-b:a", "64k", "-ac", "2"]
        procs.append(subprocess.Popen([
            "ffmpeg", "-hide_banner", "-loglevel", "error", *inputs,
            "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-pix_fmt", "yuv420p",
            "-g", "50", "-keyint_min", "50", "-sc_threshold", "0", "-b:v", "800k", *audio,
            "-f", "hls", "-hls_time", "2", "-hls_list_size", "8",
            "-hls_flags", "delete_segments+omit_endlist+program_date_time",
            "-hls_segment_filename", os.path.join(out, "live%05d.ts"), os.path.join(out, "live.m3u8"),
        ]))
    def stop(*_):
        for p in procs:
            p.terminate()
    atexit.register(stop)
    # atexit doesn't run on SIGTERM; make sure encoders die with the server.
    signal.signal(signal.SIGTERM, lambda *a: (stop(), sys.exit(0)))


class DualStackServer(ThreadingHTTPServer):
    """Listens on IPv6 and IPv4 so `localhost` works whichever address a client tries first."""
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8900)
    ap.add_argument("--no-audio", action="store_true",
                    help="encode live channels without audio (for headless simulators with no audio device)")
    args = ap.parse_args()
    if not segments(0):
        print("Media not generated; run tools/fixture-server/generate_media.sh first", file=sys.stderr)
    start_live_encoders(args.no_audio)
    srv = DualStackServer(("::", args.port), Handler)
    srv.daemon_threads = True
    print(f"Tuner fixture server on http://localhost:{args.port}  (Android emulator: http://10.0.2.2:{args.port})")
    srv.serve_forever()


if __name__ == "__main__":
    main()
