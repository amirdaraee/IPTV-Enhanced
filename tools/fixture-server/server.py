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
  /live/<u>/<p>/<id>.m3u8|.ts   live channel as sliding-window HLS or continuous MPEG-TS
  /movie/<u>/<p>/<id>.mp4       VOD file (supports Range)
  /series/<u>/<p>/<id>.mp4      episode file
  /timeshift/<u>/<p>/<min>/<start>/<id>.ts   archive (serves the loop once)
  /hls/<n>/live.m3u8            live sliding window over media/ch<n>
"""
import argparse
import gzip
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
                return self.send(xmltv_bytes(), "application/xml")
            if path == "/player_api.php":
                return self.xtream(q)
            m = re.match(r"^/hls/(\d+)/live\.m3u8$", path)
            if m:
                return self.live_playlist(int(m.group(1)))
            m = re.match(r"^/hls/(\d+)/(seg\d+\.ts)$", path)
            if m:
                return self.file(os.path.join(MEDIA, f"ch{m.group(1)}", m.group(2)), "video/mp2t")
            m = re.match(r"^/live/[^/]+/[^/]+/(\d+)\.(m3u8|ts)$", path)
            if m:
                ch = next((c for c in CHANNELS if c["id"] == int(m.group(1))), None)
                if not ch:
                    return self.send("no such stream", "text/plain", 404)
                if m.group(2) == "m3u8":
                    return self.live_playlist(ch["variant"])
                return self.live_ts(ch["variant"])
            if re.match(r"^/(movie|series)/[^/]+/[^/]+/\d+\.\w+$", path):
                return self.file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
            m = re.match(r"^/timeshift/[^/]+/[^/]+/\d+/[^/]+/(\d+)\.ts$", path)
            if m:
                ch = next((c for c in CHANNELS if c["id"] == int(m.group(1))), CHANNELS[0])
                return self.archive_ts(ch["variant"])
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
        segs = segments(variant)
        if not segs:
            return self.send("media missing: run generate_media.sh", "text/plain", 503)
        seq = int(time.time() / SEG_DUR)
        window = 6
        out = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{int(SEG_DUR)}", f"#EXT-X-MEDIA-SEQUENCE:{seq}"]
        for i in range(seq, seq + window):
            if i > seq and (i % len(segs)) == 0:
                out.append("#EXT-X-DISCONTINUITY")
            out.append(f"#EXTINF:{SEG_DUR:.3f},")
            out.append(f"{self.base}/hls/{variant}/{segs[i % len(segs)]}")
        return self.send("\n".join(out) + "\n", "application/vnd.apple.mpegurl", extra={"Cache-Control": "no-cache"})

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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8900)
    args = ap.parse_args()
    if not segments(0):
        print("Media not generated; run tools/fixture-server/generate_media.sh first", file=sys.stderr)
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    srv.daemon_threads = True
    print(f"Tuner fixture server on http://localhost:{args.port}  (Android emulator: http://10.0.2.2:{args.port})")
    srv.serve_forever()


if __name__ == "__main__":
    main()
