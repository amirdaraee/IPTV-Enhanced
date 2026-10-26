#!/usr/bin/env python3
"""Writes docs/demo/: a small playlist and guide that store reviewers (and anyone) can use to try the app.

Every stream is content that may be shared: the Blender Foundation's open films (CC BY) and Apple's
sample HLS streams. The guide covers a year from START in 6-hour blocks, so it keeps matching "now".
"""
import datetime as dt
import os
from xml.sax.saxutils import escape

BASE = "https://raw.githubusercontent.com/amirdaraee/IPTV-Enhanced/main/docs/demo"
START = dt.datetime(2026, 10, 26, tzinfo=dt.timezone.utc)
DAYS = 366
BLOCK_HOURS = 6

CHANNELS = [
    ("bbb.demo", "Open Movies", "Open Films",
     "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
     ["Big Buck Bunny", "Big Buck Bunny (repeat)"],
     "Big Buck Bunny (2008), (c) Blender Foundation, www.bigbuckbunny.org, licensed under CC BY 3.0."),
    ("tos.demo", "Open Sci-Fi", "Open Films",
     "https://test-streams.mux.dev/tos_ismc/main.m3u8",
     ["Tears of Steel", "Tears of Steel (repeat)"],
     "Tears of Steel (2012), (c) Blender Foundation, mango.blender.org, licensed under CC BY 3.0."),
    ("bipbop.demo", "Test Pattern", "Test Streams",
     "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
     ["Test Pattern", "Test Pattern (multi-bitrate)"],
     "Apple's sample HLS stream with several bitrates."),
    ("bipbop-adv.demo", "Test Pattern HD", "Test Streams",
     "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
     ["Test Pattern HD", "Test Pattern HD (audio tracks)"],
     "Apple's advanced sample HLS stream, with several audio and subtitle tracks."),
]


def stamp(t: dt.datetime) -> str:
    return t.strftime("%Y%m%d%H%M%S +0000")


def main() -> None:
    out = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "demo")
    with open(os.path.join(out, "playlist.m3u"), "w") as f:
        f.write(f'#EXTM3U url-tvg="{BASE}/guide.xml"\n')
        for cid, name, group, url, _, _ in CHANNELS:
            f.write(f'#EXTINF:-1 tvg-id="{cid}" group-title="{group}",{name}\n{url}\n')
    with open(os.path.join(out, "guide.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="IPTV Enhanced demo">\n')
        for cid, name, _, _, _, _ in CHANNELS:
            f.write(f'  <channel id="{cid}"><display-name>{escape(name)}</display-name></channel>\n')
        for cid, _, _, _, titles, desc in CHANNELS:
            for i in range(DAYS * 24 // BLOCK_HOURS):
                a = START + dt.timedelta(hours=i * BLOCK_HOURS)
                b = a + dt.timedelta(hours=BLOCK_HOURS)
                f.write(f'  <programme start="{stamp(a)}" stop="{stamp(b)}" channel="{cid}">'
                        f'<title>{escape(titles[i % len(titles)])}</title><desc>{escape(desc)}</desc></programme>\n')
        f.write("</tv>\n")


if __name__ == "__main__":
    main()
