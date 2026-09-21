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

def first_match(patterns, text):
    for pattern in patterns:
        m = re.search(pattern, text, re.I | re.S)
        if m:
            return m.group(1)
    return None

def inspect_embed(url):
    try:
        status, content_type, page = fetch(url, EPISODE)
    except Exception as exc:
        return f"fetch_error={type(exc).__name__}:{exc}"

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

    has_video_token = bool(re.search(r'VIDEO_TOKEN\s*=\s*["\'][^"\']+["\']', page, re.I))
    has_source_tag = bool(re.search(r'<source\b[^>]+src=', page, re.I))

    return (
        f"http={status}, content_type={content_type}, title={title!r}, "
        f"direct_media={len(direct)}, video_token={has_video_token}, source_tag={has_source_tag}, "
        f"markers={markers}, script_hosts={script_hosts[:8]}"
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
            if any(token in lower for token in ["/hentai/", "/anime/", "/episode/"]):
                if href.startswith("//"):
                    href = "https:" + href
                elif href.startswith("/"):
                    href = "https://nekopoi.care" + href
                if href.startswith("http"):
                    candidate = href
                    break

        iframe_hosts = []
        if candidate and not challenge:
            try:
                _, _, detail = fetch(candidate, base)
                for src in re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', detail, re.I):
                    if src.startswith("//"):
                        src = "https:" + src
                    host = urlparse(src).hostname
                    if host and host not in iframe_hosts:
                        iframe_hosts.append(host)
            except Exception:
                pass

        print(
            f"Nekopoi reachability: HTTP {status}, cloudflare_challenge={challenge}, "
            f"listing_markers={listing_markers}, iframe_hosts={iframe_hosts[:8]}"
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
