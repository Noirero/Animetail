#!/usr/bin/env python3
import base64
import json
import re
import sys
import urllib.request
from urllib.parse import urljoin, urlparse

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/145.0 Safari/537.36"
BASE = "https://aniwatch.co.at"
EPISODE = BASE + "/one-piece-episode-1-english-subbed/"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": BASE + "/"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, r.read().decode("utf-8", "replace")

def first_match(patterns, text):
    for pattern in patterns:
        m = re.search(pattern, text, re.I | re.S)
        if m:
            return m.group(1)
    return None

def probe_aniwatch():
    status, page = fetch(EPISODE)
    anime_id = first_match([
        r'["\']anime_id["\']\s*:\s*["\']?(\d+)',
        r'anime_id\s*[:=]\s*["\']?(\d+)',
        r'data-anime-id=["\'](\d+)["\']',
    ], page)
    if not anime_id:
        raise RuntimeError("AniWatch: anime_id not found")

    _, list_raw = fetch(f"{BASE}/wp-json/hianime/v1/episode/list/{anime_id}")
    list_html = json.loads(list_raw).get("html", "")
    ep_id = first_match([
        r'data-number=["\']1(?:\.0)?["\'][^>]*data-id=["\'](\d+)["\']',
        r'data-id=["\'](\d+)["\'][^>]*data-number=["\']1(?:\.0)?["\']',
    ], list_html)
    if not ep_id:
        raise RuntimeError("AniWatch: episode id not found")

    _, servers_raw = fetch(f"{BASE}/wp-json/hianime/v1/episode/servers/{ep_id}")
    servers_html = json.loads(servers_raw).get("html", "")
    hashes = re.findall(r'data-hash=["\']([^"\']+)["\']', servers_html, re.I)
    hosts = []
    for value in hashes:
        try:
            decoded = base64.b64decode(value + "===" ).decode("utf-8", "replace").strip()
            if decoded.startswith(("http://", "https://")):
                host = urlparse(decoded).hostname
                if host and host not in hosts:
                    hosts.append(host)
        except Exception:
            pass
    if not hosts:
        raise RuntimeError("AniWatch: no decodable server links")
    print(f"AniWatch OK: HTTP {status}, anime_id={anime_id}, episode_id={ep_id}, hosts={','.join(hosts)}")

def probe_nekopoi():
    try:
        status, page = fetch("https://nekopoi.care/")
        challenge = "cf-chl-" in page or "Just a moment" in page
        print(f"Nekopoi reachability: HTTP {status}, cloudflare_challenge={challenge}")
    except Exception as exc:
        print(f"Nekopoi reachability warning: {exc}")

if __name__ == "__main__":
    try:
        probe_aniwatch()
    except Exception as exc:
        print(f"AniWatch probe failed: {exc}", file=sys.stderr)
        sys.exit(2)
    probe_nekopoi()
