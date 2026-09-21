#!/usr/bin/env python3
import base64
import json
import re
import sys
import urllib.request
from urllib.parse import urlparse

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/145.0 Safari/537.36"
BASE = "https://aniwatch.co.at"
EPISODE = BASE + "/one-piece-episode-1-english-subbed/"

def fetch(url, referer=None):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Referer": referer or BASE + "/",
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        },
    )
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, r.headers.get("Content-Type", ""), r.read().decode("utf-8", "replace")

def fetch_embed_variant(url, mode):
    headers = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    }
    if mode == "site":
        headers["Referer"] = BASE + "/"
        headers["Origin"] = BASE
    elif mode == "episode":
        headers["Referer"] = EPISODE
        headers["Origin"] = BASE

    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, r.headers.get("Content-Type", ""), r.read().decode("utf-8", "replace")

def first_match(patterns, text):
    for pattern in patterns:
        m = re.search(pattern, text, re.I | re.S)
        if m:
            return m.group(1)
    return None

def probe_media_endpoint(url):
    try:
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": UA,
                "Referer": "https://my.1anime.site/",
                "Range": "bytes=0-0",
                "Accept": "*/*",
            },
        )
        with urllib.request.urlopen(req, timeout=25) as r:
            return (
                f"http={r.status}, content_type={r.headers.get('Content-Type', '')}, "
                f"final_host={urlparse(r.geturl()).hostname}"
            )
    except Exception as exc:
        return f"error={type(exc).__name__}:{exc}"

def inspect_embed(url):
    variants = []
    selected_page = ""
    selected_status = 0
    selected_content_type = ""

    for mode in ("site", "episode", "none"):
        try:
            status, content_type, page = fetch_embed_variant(url, mode)
        except Exception as exc:
            variants.append(f"{mode}:error={type(exc).__name__}")
            continue

        token_present = bool(re.search(r'VIDEO_TOKEN\s*=\s*["\'][^"\']+["\']', page, re.I))
        source_present = bool(re.search(r'<source\b[^>]+src=', page, re.I))
        relative_stream = "/stream/" in page
        fetch_call = "fetch(" in page
        xhr = "XMLHttpRequest" in page
        meta_refresh = bool(re.search(r'<meta[^>]+http-equiv=["\']?refresh', page, re.I))
        variants.append(
            f"{mode}:http={status},len={len(page)},token={token_present},source={source_present},"
            f"stream_path={relative_stream},fetch={fetch_call},xhr={xhr},refresh={meta_refresh}"
        )

        if mode == "site":
            selected_page = page
            selected_status = status
            selected_content_type = content_type

    page = selected_page
    direct = []
    for pattern in [
        r'https?://[^"\'\\\s<>]+?\.m3u8(?:\?[^"\'\\\s<>]*)?',
        r'https?://[^"\'\\\s<>]+?\.mp4(?:\?[^"\'\\\s<>]*)?',
    ]:
        direct.extend(re.findall(pattern, page, re.I))

    script_hosts = []
    for src in re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', page, re.I):
        if src.startswith("//"):
            src = "https:" + src
        host = urlparse(src).hostname
        if host and host not in script_hosts:
            script_hosts.append(host)

    title = first_match([r'<title[^>]*>(.*?)</title>'], page)
    markers = []
    for marker in ["jwplayer", "videojs", "plyr", "hls.js", "sources:", "file:", ".m3u8", ".mp4"]:
        if marker.lower() in page.lower():
            markers.append(marker)

    token_match = re.search(r'VIDEO_TOKEN\s*=\s*["\']([^"\']+)["\']', page, re.I)
    has_video_token = token_match is not None
    has_source_tag = bool(re.search(r'<source\b[^>]+src=', page, re.I))
    stream_probe = "not_available"
    if token_match:
        stream_probe = probe_media_endpoint(f"https://my.1anime.site/stream/{token_match.group(1)}")

    return (
        f"selected_http={selected_status}, content_type={selected_content_type}, title={title!r}, "
        f"direct_media={len(direct)}, video_token={has_video_token}, source_tag={has_source_tag}, "
        f"stream_probe={stream_probe}, markers={markers}, script_hosts={script_hosts[:8]}, "
        f"variants={variants}"
    )

def probe_aniwatch():
    status, _, page = fetch(EPISODE)
    anime_id = first_match([
        r'["\']anime_id["\']\s*:\s*["\']?(\d+)',
        r'anime_id\s*[:=]\s*["\']?(\d+)',
        r'data-anime-id=["\'](\d+)["\']',
    ], page)
    if not anime_id:
        raise RuntimeError("AniWatch: anime_id not found")

    _, _, list_raw = fetch(f"{BASE}/wp-json/hianime/v1/episode/list/{anime_id}", EPISODE)
    list_html = json.loads(list_raw).get("html", "")
    ep_id = first_match([
        r'data-number=["\']1(?:\.0)?["\'][^>]*data-id=["\'](\d+)["\']',
        r'data-id=["\'](\d+)["\'][^>]*data-number=["\']1(?:\.0)?["\']',
    ], list_html)
    if not ep_id:
        raise RuntimeError("AniWatch: episode id not found")

    _, _, servers_raw = fetch(f"{BASE}/wp-json/hianime/v1/episode/servers/{ep_id}", EPISODE)
    servers_html = json.loads(servers_raw).get("html", "")
    hashes = re.findall(r'data-hash=["\']([^"\']+)["\']', servers_html, re.I)
    links = []
    hosts = []
    for value in hashes:
        try:
            decoded = base64.b64decode(value + "===" ).decode("utf-8", "replace").strip()
            if decoded.startswith(("http://", "https://")):
                links.append(decoded)
                host = urlparse(decoded).hostname
                if host and host not in hosts:
                    hosts.append(host)
        except Exception:
            pass
    if not hosts:
        raise RuntimeError("AniWatch: no decodable server links")

    print(f"AniWatch OK: HTTP {status}, anime_id={anime_id}, episode_id={ep_id}, hosts={','.join(hosts)}")
    print(f"AniWatch embed probe: host={urlparse(links[0]).hostname}, {inspect_embed(links[0])}")

def probe_nekopoi():
    try:
        base = "https://nekopoi.care/"
        status, _, page = fetch(base, base)
        challenge = "cf-chl-" in page or "Just a moment" in page
        listing_markers = {
            "nk-post-card": page.count("nk-post-card"),
            "nk-episode-card": page.count("nk-episode-card"),
            "nk-search-item": page.count("nk-search-item"),
        }

        hrefs = re.findall(r'href=["\']([^"\']+)["\']', page, re.I)
        candidate = None
        for href in hrefs:
            lower = href.lower()
            if any(token in lower for token in ["/hentai/", "/anime/", "-episode-", "/episode/"]):
                if href.startswith("//"):
                    href = "https:" + href
                elif href.startswith("/"):
                    href = "https://nekopoi.care" + href
                if href.startswith("http"):
                    candidate = href
                    break

        detail_episode_links = 0
        player_present = False
        iframe_hosts = []
        final_kind = "none"

        if candidate and not challenge:
            try:
                _, _, detail = fetch(candidate, base)
                episode_hrefs = []
                for href in re.findall(r'href=["\']([^"\']+)["\']', detail, re.I):
                    lower = href.lower()
                    if "-episode-" not in lower and "/episode/" not in lower:
                        continue
                    if href.startswith("//"):
                        href = "https:" + href
                    elif href.startswith("/"):
                        href = "https://nekopoi.care" + href
                    if href.startswith("http") and href not in episode_hrefs:
                        episode_hrefs.append(href)

                detail_episode_links = len(episode_hrefs)
                target = episode_hrefs[0] if episode_hrefs else candidate
                final_kind = "episode" if episode_hrefs or "-episode-" in target.lower() else "detail"
                _, _, player_page = fetch(target, candidate)
                player_present = 'id="nk-player"' in player_page or "nk-player-frame" in player_page

                for src in re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', player_page, re.I):
                    if src.startswith("//"):
                        src = "https:" + src
                    host = urlparse(src).hostname
                    if host and host not in iframe_hosts:
                        iframe_hosts.append(host)
            except Exception:
                pass

        print(
            f"Nekopoi reachability: HTTP {status}, cloudflare_challenge={challenge}, "
            f"listing_markers={listing_markers}, detail_episode_links={detail_episode_links}, "
            f"final_kind={final_kind}, player_present={player_present}, iframe_hosts={iframe_hosts[:8]}"
        )
    except Exception as exc:
        print(f"Nekopoi reachability warning: {exc}")

if __name__ == "__main__":
    try:
        probe_aniwatch()
    except Exception as exc:
        print(f"AniWatch probe failed: {exc}", file=sys.stderr)
        sys.exit(2)
    probe_nekopoi()
