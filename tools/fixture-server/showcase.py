"""
Showcase catalog for screenshots (Xtream username "showcase"): made-up channels, programmes,
films and series with artwork drawn by make_showcase_art.py. Every name is invented; keep it that
way, because store screenshots must not show real channels, broadcasters or titles.
"""
import json
import os
import time
from datetime import datetime, timedelta, timezone
from xml.sax.saxutils import escape

# (category id, name, art palette): the palette picks the landscape colours for that category.
CATEGORIES = [
    ("31", "News", "dawn"),
    ("32", "Sports", "meadow"),
    ("33", "Movies", "dusk"),
    ("34", "Documentary", "glacier"),
    ("35", "Kids", "candy"),
    ("36", "Music", "night"),
]

CHANNELS = [
    # (name, category, logo colour). Short names: the guide's channel column fits about seven letters.
    ("Ledger", "31", "#4FA3FF"), ("Daybook", "31", "#FFB020"), ("Wire 24", "31", "#5CE0C6"), ("Civic", "31", "#FF6B6B"),
    ("Pitch", "32", "#7BE36A"), ("Court", "32", "#FFD23F"), ("Ridge", "32", "#FF8A3D"), ("Apex", "32", "#E5484D"),
    ("Lantern", "33", "#FFC46B"), ("Matinee", "33", "#B78CFF"), ("Silver", "33", "#D9DEE7"), ("Velvet", "33", "#FF5C8A"),
    ("Wilds", "34", "#8CD867"), ("Tide", "34", "#3FC5F0"), ("Orbit", "34", "#9AA8FF"), ("Eras", "34", "#E0B77A"),
    ("Kite", "35", "#6FE3FF"), ("Puzzle", "35", "#FFE066"), ("Toonies", "35", "#A3F56B"), ("Pebble", "35", "#FF9ECF"),
    ("Amp", "36", "#FF4FD8"), ("Vinyl", "36", "#F2A65A"), ("Echo", "36", "#7CF5E0"), ("Tempo", "36", "#C6A2FF"),
]

PROGRAMMES = {
    "31": [("Morning Ledger", "The day's top stories, markets and weather."), ("World at Noon", "Reports from correspondents around the world."),
           ("Market Close", "How the trading day ended and what it means."), ("The Evening Report", "The main news of the day, in depth."),
           ("Weather Watch", "The forecast for the week ahead."), ("Late Edition", "Headlines and analysis to end the day.")],
    "32": [("Matchday Live", "Build-up, kick-off and every goal as it happens."), ("Courtside", "Highlights and interviews from the week's games."),
           ("Race Review", "Every lap of the weekend's race, analysed."), ("Training Ground", "Inside the routines of top athletes."),
           ("Final Whistle", "Results, reaction and the best moments.")],
    "33": [("The Ninth Lighthouse", "A keeper discovers that her lighthouse guides more than ships."), ("Copper Sky", "Two pilots race a storm across the desert."),
           ("Glass Orchard", "A family returns to the farm they left behind."), ("Atlas Road", "Strangers share a car on a road trip that changes them."),
           ("Hollow Pines", "A quiet town keeps a secret in the forest."), ("Salt & Ember", "A chef and a fisherman open a restaurant on the coast.")],
    "34": [("Across the Northern Ridge", "A year in the life of a mountain range."), ("Life in the Reef", "The creatures that keep a coral reef alive."),
           ("Moons of the Outer Planets", "What the probes found beyond the asteroid belt."), ("The Glacier Year", "Following one glacier through four seasons."),
           ("Lost Harbours", "Ports that history forgot, and the people who remember them.")],
    "35": [("Paper Boats", "Mia and Teo build boats for a race on the river."), ("Captain Crumb", "A baker sails the seas in search of rare spices."),
           ("Robot Garden", "Little robots learn to grow vegetables."), ("Bedtime Tales", "Gentle stories for the end of the day.")],
    "36": [("Morning Mix", "Upbeat tracks to start the day."), ("Live Sessions", "Bands play live in the studio."),
           ("Top Twenty", "The week's most played songs."), ("Acoustic Hour", "Stripped-back performances and conversation.")],
}

MOVIES = [
    # (stream id, title, year, genre, rating, plot)
    (7001, "The Ninth Lighthouse", 2025, "Mystery", 7.8, "On a remote island, a new keeper finds that her lighthouse has been guiding something other than ships."),
    (7002, "Copper Sky", 2024, "Adventure", 7.2, "Two rival pilots must fly together to outrun a storm that is crossing the desert."),
    (7003, "Glass Orchard", 2025, "Drama", 8.1, "Years after leaving, a family returns to the orchard that shaped them all."),
    (7004, "Atlas Road", 2023, "Comedy", 6.9, "Four strangers share one car, one map and a very long road."),
    (7005, "Hollow Pines", 2024, "Thriller", 7.4, "When the forest around a quiet town starts to move, the townspeople have to face what they buried."),
    (7006, "Salt & Ember", 2025, "Romance", 7.0, "A chef and a fisherman bet everything on a restaurant at the end of the pier."),
    (7007, "The Long Thaw", 2023, "Drama", 7.6, "As the ice melts, a village rediscovers what lies beneath."),
    (7008, "Iron Valley", 2024, "Western", 6.8, "A surveyor arrives in a valley where every family claims the same river."),
    (7009, "Paper Lanterns", 2025, "Family", 7.9, "A girl and her grandfather make a thousand lanterns for one night."),
    (7010, "Last Signal", 2024, "Science fiction", 7.3, "The final transmission from a deep-space probe contains a reply."),
    (7011, "Quiet Harbor", 2023, "Drama", 7.1, "A retired captain teaches a restless teenager to sail."),
    (7012, "Blue Horizon", 2025, "Documentary", 8.0, "Sailing around the world on wind alone."),
]

SERIES = [
    # (series id, title, year, genre, rating, plot, seasons -> episode titles)
    (8001, "Harbor Lights", 2024, "Drama", 8.4, "Life, love and storms in a small harbour town.",
     {1: ["Pilot", "Arrival", "Storm Warning", "Low Water", "The Regatta", "Lights Out"], 2: ["Return", "New Moorings", "Fog", "Finale"]}),
    (8002, "The Cartographers", 2025, "Adventure", 8.1, "A team of mapmakers charts the last blank spaces on Earth.",
     {1: ["Blank Spaces", "The Ice Shelf", "Underground", "True North"]}),
    (8003, "Westmark", 2023, "Crime", 7.7, "A detective returns to the city she swore she'd never work in again.",
     {1: ["Homecoming", "Old Debts", "The Bridge", "Undertow", "Westmark"]}),
    (8004, "Second Orbit", 2025, "Science fiction", 8.6, "The crew of a research station prepares for a journey no one has made.",
     {1: ["Launch Window", "Drift", "Signal", "Burn"], 2: ["Far Side", "Contact"]}),
    (8005, "Low Tide", 2024, "Comedy", 7.3, "Three friends try to save their beach café, one bad idea at a time.",
     {1: ["Grand Reopening", "Surf's Up", "Health Inspector", "Off Season"]}),
    (8006, "Ninefold", 2025, "Fantasy", 8.0, "Nine kingdoms, one crown, and a thief who didn't mean to take it.",
     {1: ["The Crown", "The Ferryman", "Glass Mountain", "Ninefold"]}),
]


def channel_id(i):
    return 6001 + i


def slug(name):
    return "".join(c.lower() if c.isalnum() else "-" for c in name).strip("-").replace("--", "-")


def palette(category_id):
    return next(p for c, _, p in CATEGORIES if c == category_id)


# ---------------------------------------------------------------- Xtream API and guide

ART = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media", "showcase")
PALETTES = [p for _, _, p in CATEGORIES]
# Live HLS variants 5.. are the showcase landscapes, one per palette (see server.start_live_encoders).
FIRST_VARIANT = 5
CAST = ["Mara Ellison", "Tobias Reyne", "Ines Calloway", "Jonah Petrakis", "Ada Morrow", "Felix Okonkwo-Lind", "Rosa Vantelle", "Henrik Sato"]
DIRECTORS = ["Lena Hartwell", "Sami Arnaud", "Yuki Barrow", "Owen Castell"]


def variant(stream_id):
    i = stream_id - channel_id(0)
    return FIRST_VARIANT + PALETTES.index(palette(CHANNELS[i][1])) if 0 <= i < len(CHANNELS) else None


def video_file(stream_id):
    """The landscape video a film or episode plays."""
    return os.path.join(ART, f"{PALETTES[stream_id % len(PALETTES)]}.mp4")


def _cast(seed):
    return ", ".join(CAST[(seed + k * 3) % len(CAST)] for k in range(3))


def xtream(action, q, base):
    art = f"{base}/art"
    if action == "get_live_categories":
        return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n, _ in CATEGORIES]
    if action == "get_live_streams":
        return [{"num": i + 1, "name": n, "stream_type": "live", "stream_id": channel_id(i), "stream_icon": f"{art}/logos/{slug(n)}.png",
                 "epg_channel_id": f"{slug(n)}.show", "added": "1700000000", "category_id": c,
                 "tv_archive": 1 if c in ("31", "32", "34") else 0, "tv_archive_duration": 3 if c in ("31", "32", "34") else 0}
                for i, (n, c, _) in enumerate(CHANNELS) if q.get("category_id") in (None, c)]
    if action == "get_vod_categories":
        return [{"category_id": "41", "category_name": "New Releases", "parent_id": 0}, {"category_id": "42", "category_name": "Popular", "parent_id": 0}]
    if action == "get_vod_streams":
        return [{"num": i + 1, "name": t, "stream_type": "movie", "stream_id": mid, "stream_icon": f"{art}/posters/{mid}.jpg", "rating": str(r),
                 "added": str(int(time.time()) - i * 86400), "category_id": "41" if y == 2025 else "42", "container_extension": "mp4"}
                for i, (mid, t, y, _g, r, _p) in enumerate(MOVIES)]
    if action == "get_vod_info":
        mid = int(q.get("vod_id", 0))
        m = next((m for m in MOVIES if m[0] == mid), MOVIES[0])
        return {"info": {"name": m[1], "plot": m[5], "cast": _cast(mid), "director": DIRECTORS[mid % len(DIRECTORS)], "genre": m[3],
                         "duration_secs": 6420, "duration": "01:47:00", "backdrop_path": [f"{art}/backdrops/{mid}.jpg"],
                         "movie_image": f"{art}/posters/{mid}.jpg", "releasedate": f"{m[2]}-05-12", "rating": str(m[4])},
                "movie_data": {"stream_id": mid, "name": m[1], "container_extension": "mp4"}}
    if action == "get_series_categories":
        return [{"category_id": "51", "category_name": "Top Series", "parent_id": 0}]
    if action == "get_series":
        return [{"num": i + 1, "name": t, "series_id": sid, "cover": f"{art}/posters/{sid}.jpg", "plot": p, "genre": g,
                 "releaseDate": f"{y}-03-01", "rating": str(r), "category_id": "51", "last_modified": str(int(time.time()) - i * 86400)}
                for i, (sid, t, y, g, r, p, _s) in enumerate(SERIES)]
    if action == "get_series_info":
        sid = int(q.get("series_id", 0))
        s = next((s for s in SERIES if s[0] == sid), SERIES[0])
        episodes = {str(n): [{"id": f"{sid}{n}{e:02d}", "episode_num": e, "title": title, "container_extension": "mp4", "season": n,
                              "info": {"duration_secs": 2700 + e * 120, "movie_image": f"{art}/stills/{sid}-{n}-{e}.jpg"}}
                             for e, title in enumerate(eps, 1)] for n, eps in s[6].items()}
        return {"seasons": [{"season_number": n} for n in s[6]], "episodes": episodes,
                "info": {"name": s[1], "plot": s[5], "cast": _cast(sid), "genre": s[3], "backdrop_path": [f"{art}/backdrops/{sid}.jpg"]}}
    if action == "get_short_epg":
        return {"epg_listings": []}
    return []


def xmltv():
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    start, end, fmt = now - timedelta(days=1), now + timedelta(days=2), "%Y%m%d%H%M%S +0000"
    out = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="tuner-showcase">']
    out += [f'<channel id="{slug(n)}.show"><display-name>{escape(n)}</display-name></channel>' for n, _, _ in CHANNELS]
    for i, (n, c, _) in enumerate(CHANNELS):
        shows, t, k = PROGRAMMES[c], start, i * 2
        while t < end:
            title, desc = shows[k % len(shows)]
            dur = timedelta(minutes=[30, 60, 45, 90, 30, 60][(k + i) % 6] if c != "33" else 120)
            out.append(f'<programme start="{t.strftime(fmt)}" stop="{(t + dur).strftime(fmt)}" channel="{slug(n)}.show">'
                       f'<title lang="en">{escape(title)}</title><desc lang="en">{escape(desc)}</desc>'
                       f'<category lang="en">{dict((a, b) for a, b, _ in CATEGORIES)[c]}</category></programme>')
            t, k = t + dur, k + 1
    out.append("</tv>")
    return "\n".join(out).encode()
