"""Collect source-owned RouVideo responses without logging page content or credentials."""

import argparse
import concurrent.futures
import hashlib
import html
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse


BASE = "https://rou.video"
HEADERS = [
    "Accept: text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    "Referer: https://rou.video/",
    "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
]


def write_proxy_config(destination):
    raw = os.environ["ROUVIDEO_PROXY_CONFIG"]
    if raw.lstrip().startswith("{"):
        config = json.loads(raw)
    else:
        section = ""
        entry = None
        for line in raw.splitlines():
            line = line.strip()
            if line.startswith("["):
                section = line
            elif section == "[Proxy]" and "=" in line:
                parts = [part.strip() for part in line.split("=", 1)[1].split(",")]
                if parts[0] == "ss":
                    entry = parts
                    break
        if entry is None:
            raise ValueError("No Shadowsocks node in proxy configuration")
        options = dict(part.split("=", 1) for part in entry[3:] if "=" in part)
        config = {
            "server": entry[1],
            "server_port": int(entry[2]),
            "method": options["encrypt-method"],
            "password": options["password"],
        }
    required = {"server", "server_port", "method", "password"}
    if not required.issubset(config):
        raise ValueError("Incomplete Shadowsocks configuration")
    if config["method"].startswith("2022-"):
        raise ValueError("This runner client requires a legacy AEAD Shadowsocks node")
    Path(destination).write_text(json.dumps({key: config[key] for key in required}))


def fetch(label, url, output):
    body_path = output / f"{label}.body"
    command = [
        "curl", "--silent", "--show-error", "--location",
        "--proto", "=https", "--proto-redir", "=https",
        "--max-time", "45", "--connect-timeout", "15",
        "--max-filesize", "20000000", "--compressed",
        "--output", str(body_path),
        "--write-out", "%{json}",
    ]
    for header in HEADERS:
        command.extend(["--header", header])
    proxy = os.environ.get("ROUVIDEO_SOCKS_PROXY")
    if proxy:
        command.extend(["--socks5-hostname", proxy])
    command.append(url)
    result = subprocess.run(command, capture_output=True, text=True)
    try:
        info = json.loads(result.stdout)
    except json.JSONDecodeError:
        info = {}
    body = body_path.read_bytes() if body_path.exists() else b""
    metadata = {
        "label": label,
        "url": url,
        "effective_url": info.get("url_effective"),
        "status": info.get("http_code", 0),
        "content_type": info.get("content_type"),
        "bytes": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
        "curl_exit": result.returncode,
        "has_next_data": b"__NEXT_DATA__" in body,
        "has_next_flight": b"self.__next_f.push" in body,
        "site_unavailable": b"<title>Site Unavailable</title>" in body,
    }
    if result.returncode:
        metadata["error"] = result.stderr.strip()[:400]
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        text = ""
    next_data = re.search(r'<script\b[^>]*\bid=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>', text, re.S)
    if next_data:
        try:
            payload = json.loads(html.unescape(next_data.group(1)))
            metadata["page_props_keys"] = list(payload.get("props", {}).get("pageProps", {}))
            (output / f"{label}.next.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2))
        except json.JSONDecodeError:
            metadata["next_data_invalid"] = True
    (output / f"{label}.meta.json").write_text(json.dumps(metadata, indent=2))
    print(json.dumps({key: metadata[key] for key in ("label", "status", "bytes", "curl_exit", "has_next_data", "has_next_flight", "site_unavailable")}))
    return metadata, text


def collect(output):
    output.mkdir(parents=True, exist_ok=True)
    print("transport=" + ("shadowsocks" if os.environ.get("ROUVIDEO_SOCKS_PROXY") else "direct"))
    pages = [
        ("home", BASE + "/home"),
        ("latest-page-1", BASE + "/v?order=createdAt&page=1"),
        ("latest-page-2", BASE + "/v?order=createdAt&page=2"),
        ("popular", BASE + "/v?order=likeCount&page=1"),
        ("categories", BASE + "/cat"),
        ("search-home", BASE + "/search"),
        ("search-results", BASE + "/search?q=cos&page=1"),
        ("watching-api", BASE + "/api/v/watching"),
    ]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        responses = list(executor.map(lambda item: fetch(*item, output), pages))
    metadata = [item[0] for item in responses]
    page_text = "\n".join(item[1] for item in responses)
    links = re.findall(r'\bhref=["\']([^"\']+)', page_text)
    normalized = [urllib.parse.urljoin(BASE, html.unescape(link)) for link in links]
    details = next((url for url in normalized if urllib.parse.urlparse(url).netloc == "rou.video" and re.fullmatch(r"/v/[^/]+", urllib.parse.urlparse(url).path)), None)
    tag = next((url for url in normalized if urllib.parse.urlparse(url).netloc == "rou.video" and urllib.parse.urlparse(url).path.startswith("/t/")), None)
    if details:
        info, text = fetch("detail", details, output)
        metadata.append(info)
        page_text += "\n" + text
    if tag:
        info, _ = fetch("tag", tag, output)
        metadata.append(info)
    scripts = re.findall(r'<script\b[^>]*\bsrc=["\']([^"\']+)', page_text)
    assets = sorted({urllib.parse.urljoin(BASE, html.unescape(src)) for src in scripts})
    assets = [url for url in assets if urllib.parse.urlparse(url).netloc == "rou.video"][:30]
    (output / "asset-urls.json").write_text(json.dumps(assets, indent=2))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(lambda item: fetch(f"asset-{item[0]:02d}", item[1], output), enumerate(assets)))
    metadata.extend(item[0] for item in results)
    report = {"responses": metadata, "detail_url": details, "asset_count": len(assets)}
    (output / "report.json").write_text(json.dumps(report, indent=2))
    successful = sum(200 <= info["status"] < 300 and info["bytes"] > 1000 and not info["site_unavailable"] for info in metadata[:len(pages)])
    print(f"usable_page_responses={successful}; assets={len(assets)}; detail_discovered={bool(details)}")
    if not successful:
        raise SystemExit("No usable original pages were obtained; inspect the response artifact")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--proxy-config", type=Path)
    args = parser.parse_args()
    if args.proxy_config:
        write_proxy_config(args.proxy_config)
    elif args.output:
        collect(args.output)
    else:
        parser.error("Select --output or --proxy-config")
