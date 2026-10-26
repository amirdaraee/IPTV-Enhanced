#!/usr/bin/env python3
"""
Draws the showcase artwork (logos, posters, backdrops, episode stills) and the looping videos
the showcase channels play, into media/showcase/. Everything is generated here, so screenshots
contain no one else's artwork. Needs Pillow, numpy and ffmpeg; macOS fonts.

  python3 tools/fixture-server/make_showcase_art.py
"""
import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import showcase as sc

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media", "showcase")
FONTS = "/System/Library/Fonts"

# sky top, sky horizon, sun, nearest ridge, farthest ridge, stars, roughness
PALETTES = {
    "dawn":    ("#1B2A4A", "#F6A96B", "#FFE6B8", "#1A1626", "#9C7A96", False, 0.55),
    "meadow":  ("#2F78C9", "#CDEBFF", "#FFFFFF", "#1C4426", "#8DBF8A", False, 0.35),
    "dusk":    ("#24173A", "#FF7E5F", "#FFD9A3", "#110818", "#7A4570", False, 0.6),
    "glacier": ("#0E3954", "#B6E6F3", "#F4FCFF", "#12344A", "#DCEFF7", False, 0.5),
    "candy":   ("#FF8FC8", "#FFEBB0", "#FFFFFF", "#4A2F86", "#C3AEFF", False, 0.3),
    "night":   ("#03040C", "#26346A", "#F4F1E0", "#05070F", "#34407A", True, 0.55),
}
GENRE_PALETTE = ["dusk", "dawn", "glacier", "night", "meadow", "candy"]


def font(name, size, index=0):
    path = os.path.join(FONTS, name)
    if not os.path.exists(path):
        path = os.path.join(FONTS, "Supplemental", name)
    return ImageFont.truetype(path, size, index=index)


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def ridge(width, rng, roughness, base, amplitude):
    """A mountain ridge by midpoint displacement: heights (fractions of the image height) per column."""
    n = 1
    pts = np.array([rng.uniform(-1, 1), rng.uniform(-1, 1)])
    scale = 1.0
    while n < width:
        mids = (pts[:-1] + pts[1:]) / 2 + rng.uniform(-scale, scale, len(pts) - 1)
        out = np.empty(len(pts) * 2 - 1)
        out[0::2], out[1::2] = pts, mids
        pts, n, scale = out, len(out), scale * roughness
    xs = np.linspace(0, len(pts) - 1, width)
    line = np.interp(xs, np.arange(len(pts)), pts)
    return base - amplitude * (line - line.min()) / max(np.ptp(line), 1e-6)


def landscape(w, h, pal, seed):
    top, horizon, sun, near, far, stars, rough = PALETTES[pal]
    rng = np.random.default_rng(seed)
    y = np.linspace(0, 1, h)[:, None, None]
    sky = rgb(top) * (1 - y ** 0.8) + rgb(horizon) * y ** 0.8
    img = np.broadcast_to(sky, (h, w, 3)).copy()
    if stars:
        for _ in range(w * h // 2500):
            sx, sy = rng.integers(0, w), rng.integers(0, int(h * 0.6))
            img[sy, sx] = 255 * rng.uniform(0.5, 1)
    # Sun (or moon) with a soft glow.
    cx, cy, r = w * rng.uniform(0.25, 0.75), h * rng.uniform(0.28, 0.42), h * 0.07
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    glow = np.clip(1 - d / (r * 6), 0, 1)[..., None] ** 2 * 0.55
    disc = np.clip((r - d) / 2, 0, 1)[..., None]
    img = img * (1 - glow) + rgb(sun) * glow
    img = img * (1 - disc) + rgb(sun) * disc
    # Ridges from far (pale, high) to near (dark, low); each one hides the sky behind it.
    layers = 5
    for i in range(layers):
        t = i / (layers - 1)
        colour = rgb(far) * (1 - t) + rgb(near) * t
        heights = ridge(w, rng, rough, base=0.62 + 0.09 * i, amplitude=0.26 - 0.03 * i) * h
        mask = (yy >= heights[None, :])[..., None]
        shade = 1 - 0.18 * np.clip((yy - heights[None, :]) / (h * 0.4), 0, 1)[..., None]
        img = np.where(mask, colour * shade, img)
    noise = rng.normal(0, 2.2, img.shape)
    return Image.fromarray(np.clip(img + noise, 0, 255).astype(np.uint8))


def fit_text(draw, text, face, size, max_width, index=0):
    while size > 12:
        f = font(face, size, index)
        if draw.textlength(text, font=f) <= max_width:
            return f
        size -= 2
    return font(face, size, index)


def poster(title, subtitle, pal, seed, path):
    w, h = 600, 900
    art = landscape(1350, 900, pal, seed).crop((375, 0, 975, 900))
    shade = Image.linear_gradient("L").resize((w, h)).point(lambda v: int(max(0, v - 90) * 1.6))
    black = Image.new("RGB", (w, h), (8, 8, 14))
    art = Image.composite(black, art, shade)
    d = ImageDraw.Draw(art)
    words = title.upper().split()
    lines, cur = [], ""
    for word in words:
        if cur and d.textlength(f"{cur} {word}", font=font("Futura.ttc", 72, 4)) > w - 80:
            lines.append(cur)
            cur = word
        else:
            cur = f"{cur} {word}".strip()
    lines.append(cur)
    y = h - 110 - 76 * len(lines)
    for line in lines:
        f = fit_text(d, line, "Futura.ttc", 72, w - 80, 4)
        d.text((w / 2, y), line, font=f, fill=(250, 248, 240), anchor="mt")
        y += 76
    d.text((w / 2, h - 70), subtitle.upper(), font=font("Avenir Next.ttc", 22, 5), fill=(220, 220, 230), anchor="mt")
    art.save(path, quality=90)


def logo(name, colour, index, path):
    w, h = 480, 320
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = tuple(int(x) for x in rgb(colour)) + (255,)
    cx, cy, r = w / 2, 105, 70
    shape = index % 4
    if shape == 0:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=c)
        d.ellipse((cx - r * 0.45, cy - r * 0.45, cx + r * 0.45, cy + r * 0.45), fill=(0, 0, 0, 0))
    elif shape == 1:
        d.polygon([(cx, cy - r), (cx + r, cy + r * 0.8), (cx - r, cy + r * 0.8)], fill=c)
    elif shape == 2:
        d.rounded_rectangle((cx - r, cy - r * 0.8, cx + r, cy + r * 0.8), radius=28, fill=c)
        d.polygon([(cx - 22, cy - 32), (cx + 34, cy), (cx - 22, cy + 32)], fill=(0, 0, 0, 0))
    else:
        for k in range(3):
            d.arc((cx - r + k * 22, cy - r + k * 22, cx + r - k * 22, cy + r - k * 22), 200, 340, fill=c, width=16)
        d.ellipse((cx - 14, cy + 20, cx + 14, cy + 48), fill=c)
    f = fit_text(d, name.upper(), "Avenir Next.ttc", 64, w - 30, 8)
    d.text((cx, 248), name.upper(), font=f, fill=(255, 255, 255, 255), anchor="mm")
    img.save(path)


def video(pal, path):
    """A slow 60-second pan across a wide landscape: a calm stand-in for a live programme."""
    still = path.replace(".mp4", ".jpg")
    landscape(3840, 1440, pal, 99).save(still, quality=92)
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-loop", "1", "-framerate", "25", "-i", still,
        "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.02:sample_rate=48000",
        "-filter_complex", "[0:v]scale=3840:1440,crop=1920:1080:x='(iw-ow)*(0.5-0.5*cos(2*PI*t/60))':y=180,scale=1280:720,format=yuv420p[v]",
        "-map", "[v]", "-map", "1:a", "-t", "60", "-c:v", "libx264", "-preset", "veryfast", "-b:v", "3M", "-g", "50",
        "-c:a", "aac", "-b:a", "64k", "-ac", "2", "-movflags", "+faststart", path,
    ], check=True)


def main():
    for sub in ("logos", "posters", "backdrops", "stills"):
        os.makedirs(os.path.join(OUT, sub), exist_ok=True)
    for i, (name, _, colour) in enumerate(sc.CHANNELS):
        logo(name, colour, i, os.path.join(OUT, "logos", f"{sc.slug(name)}.png"))
    for i, (mid, title, year, genre, *_rest) in enumerate(sc.MOVIES):
        pal = GENRE_PALETTE[i % len(GENRE_PALETTE)]
        poster(title, f"{genre} · {year}", pal, mid, os.path.join(OUT, "posters", f"{mid}.jpg"))
        landscape(1920, 1080, pal, mid).save(os.path.join(OUT, "backdrops", f"{mid}.jpg"), quality=88)
    for i, (sid, title, year, genre, _r, _p, seasons) in enumerate(sc.SERIES):
        pal = GENRE_PALETTE[(i + 3) % len(GENRE_PALETTE)]
        poster(title, f"{genre} · {year}", pal, sid, os.path.join(OUT, "posters", f"{sid}.jpg"))
        landscape(1920, 1080, pal, sid).save(os.path.join(OUT, "backdrops", f"{sid}.jpg"), quality=88)
        for s, eps in seasons.items():
            for e in range(1, len(eps) + 1):
                landscape(640, 360, pal, sid * 100 + s * 10 + e).save(os.path.join(OUT, "stills", f"{sid}-{s}-{e}.jpg"), quality=85)
    for pal in PALETTES:
        dest = os.path.join(OUT, f"{pal}.mp4")
        if not os.path.exists(dest):
            video(pal, dest)
    print(f"showcase art ready in {OUT}")


if __name__ == "__main__":
    main()
