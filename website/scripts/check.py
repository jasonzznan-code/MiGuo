#!/usr/bin/env python3
"""Validate the release manifest, render the site, and refuse unpublished URLs."""

from __future__ import annotations

import hashlib
import http.server
import json
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))

import build  # noqa: E402

REPO = build.REPO
WEBSITE = build.WEBSITE
DIST = WEBSITE / "dist"

PINNED_PRODUCT = {
    "nameZh": "米果",
    "nameEn": "Migo",
    "applicationId": "com.miguo.voice",
    "operator": "米果智能（个人开发者）",
    "contactEmail": "420603523@qq.com",
    "contactPhone": None,
    "sourceUrl": "https://github.com/jasonzznan-code/MiGuo",
    "privacyCanonicalUrl": "https://ai.njzyb.com:3010/privacy",
}

PINNED_RELEASE = {
    "id": "android-store-1.0.0-vc102",
    "versionName": "1.0.0",
    "versionCode": 102,
    "sha256": "434d88fe0c187f5f7f0582cac28a3b541e07d204da8a93576a35b7bb45d9702c",
    "updatedOn": "2026-11-02",
    "gitTag": "v1.0.0",
    "gitCommit": "5d9f086ba52ec08dc004fbe6ee1db2a40c991295",
    "channel": "storeRelease",
    "publicScopeMustContain": [
        "不在这个安装包内",
        "内容由 AI 生成",
        "5d9f086ba52ec08dc004fbe6ee1db2a40c991295",
    ],
}

FORBIDDEN = (
    "nanomuse",
    "nano muse",
    "192.168.",
    "127.0.0.1",
    "localhost",
    "trycloudflare",
    "测试账号",
    "debug",
    "已发布",
    "已提审",
    "已上架",
    "已上线",
    "keystore",
    "内验",
)
PRIVATE_IP = re.compile(
    r"\b(?:127(?:\.\d+){3}|10(?:\.\d+){3}|192\.168(?:\.\d+){2}|172\.(?:1[6-9]|2\d|3[0-1])(?:\.\d+){2})\b"
)
PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
USER_AGENT = "Mozilla/5.0 (compatible; MiguoWebsiteCheck/1.0)"


class RedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not str(newurl).startswith("https://"):
            raise build.ManifestError(f"redirect left https: {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(RedirectHandler)


def fetch(url: str, limit: int = 2_000_000) -> tuple[int, dict, bytes]:
    build.assert_public_https(url)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with OPENER.open(request, timeout=30) as response:
        final = response.geturl()
        build.assert_public_https(final)
        body = response.read(limit + 1)
        if len(body) > limit:
            raise build.ManifestError(f"response is larger than expected: {url}")
        headers = {key.lower(): value for key, value in response.headers.items()}
        return response.status, headers, body


def sha256_of(url: str) -> tuple[str, str]:
    build.assert_public_https(url)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    digest = hashlib.sha256()
    with OPENER.open(request, timeout=60) as response:
        build.assert_public_https(response.geturl())
        content_type = response.headers.get("Content-Type", "")
        while True:
            chunk = response.read(1024 * 256)
            if not chunk:
                break
            digest.update(chunk)
        return digest.hexdigest(), content_type


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.lang = ""
        self.title = ""
        self._in_title = False
        self.h1 = 0
        self.ids: list[str] = []
        self.images: list[dict[str, str | None]] = []
        self.anchors: list[dict[str, str | None]] = []
        self.buttons: list[dict[str, str | None]] = []
        self.metas: list[dict[str, str | None]] = []
        self.times: list[str] = []
        self.main = False
        self.skip = False
        self._capture_id = ""
        self.code_text: dict[str, str] = {}
        self.csp = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr = {key: value for key, value in attrs}
        if tag == "html":
            self.lang = attr.get("lang") or ""
        elif tag == "title":
            self._in_title = True
        elif tag == "h1":
            self.h1 += 1
        elif tag == "main" and attr.get("id") == "main" and attr.get("tabindex") == "-1":
            self.main = True
        elif tag == "img":
            self.images.append(attr)
        elif tag == "a":
            self.anchors.append(attr)
            if attr.get("href") == "#main" and "skip" in (attr.get("class") or ""):
                self.skip = True
        elif tag == "button":
            self.buttons.append(attr)
        elif tag == "meta":
            self.metas.append(attr)
            content = attr.get("content") or ""
            if attr.get("http-equiv", "").lower() == "content-security-policy" and "script-src 'self'" in content:
                self.csp = True
        elif tag == "time":
            self.times.append(attr.get("datetime") or "")
        elif tag == "code" and attr.get("id"):
            self._capture_id = attr["id"]
            self.code_text[self._capture_id] = ""
        if "id" in attr and attr["id"]:
            self.ids.append(attr["id"])

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False
        elif tag == "code":
            self._capture_id = ""

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title += data
        if self._capture_id:
            self.code_text[self._capture_id] = self.code_text.get(self._capture_id, "") + data


def tree_bytes(root: Path) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for path in root.rglob("*"):
        if path.is_file():
            files[path.relative_to(root).as_posix()] = path.read_bytes()
    return files


def norm_text(value: str) -> str:
    value = re.sub(r"<br\s*/?>", " ", value, flags=re.I)
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def h2_sections(fragment: str) -> list[tuple[str, str]]:
    parts = re.split(r"<h2>", fragment, flags=re.I)
    sections = []
    for part in parts[1:]:
        title, _, rest = part.partition("</h2>")
        sections.append((norm_text(title), norm_text(rest)))
    return sections


def git_tag_commit_and_date(tag: str) -> tuple[str, str]:
    commit = subprocess.check_output(
        ["git", "rev-parse", f"{tag}^{{commit}}"],
        cwd=REPO,
        text=True,
    ).strip()
    info = subprocess.check_output(
        ["git", "for-each-ref", f"refs/tags/{tag}", "--format=%(objecttype)%09%(creatordate:short)"],
        cwd=REPO,
        text=True,
    ).strip()
    if not info:
        raise build.ManifestError(f"git tag {tag} is not in this clone")
    kind, _, created = info.partition("\t")
    if kind != "tag" or not created:
        raise build.ManifestError(f"git tag {tag} must be an annotated tag with a creation date")
    return commit, created


def expect_manifest_error(data: dict, snippet: str, failures: list[str]) -> None:
    try:
        build.validate_manifest(data)
    except build.ManifestError as exc:
        if snippet not in str(exc):
            failures.append(f"wrong error for {snippet!r}: {exc}")
        return
    failures.append(f"manifest validation should fail: {snippet}")


def negative_checks(failures: list[str]) -> None:
    base = json.loads(build.MANIFEST_PATH.read_text(encoding="utf-8"))

    def with_copy(mutate) -> dict:
        data = json.loads(json.dumps(base))
        mutate(data)
        return data

    def two_current(data: dict) -> None:
        other = json.loads(json.dumps(data["releases"][0]))
        other["id"] = "second-current"
        other["versionCode"] = 1
        other["sha256"] = "b" * 64
        data["releases"].append(other)

    expect_manifest_error(with_copy(two_current), "more than one release", failures)

    def published_without_url(data: dict) -> None:
        data["releases"][0]["artifact"]["status"] = "published"

    expect_manifest_error(with_copy(published_without_url), "httpsUrl and fileName", failures)

    def unpublished_with_url(data: dict) -> None:
        data["releases"][0]["artifact"]["httpsUrl"] = "https://example.com/app.apk"

    expect_manifest_error(with_copy(unpublished_with_url), "cannot carry a URL", failures)

    def http_download(data: dict) -> None:
        artifact = data["releases"][0]["artifact"]
        artifact["status"] = "published"
        artifact["httpsUrl"] = "http://example.com/app.apk"
        artifact["fileName"] = "app.apk"

    expect_manifest_error(with_copy(http_download), "https", failures)

    expect_manifest_error(
        with_copy(lambda data: data["product"].__setitem__("contactPhone", "010-00000000")),
        "contactPhone",
        failures,
    )
    expect_manifest_error(
        with_copy(lambda data: data["releases"][0].__setitem__("sha256", "ABC")),
        "sha256",
        failures,
    )
    expect_manifest_error(
        with_copy(lambda data: data.__setitem__("finalPackage", True)),
        "manifest keys",
        failures,
    )
    expect_manifest_error(
        with_copy(lambda data: data["releases"][0].__setitem__("storeStatus", "submitted")),
        "storeStatus",
        failures,
    )

    def add_unexplained(data: dict) -> None:
        other = json.loads(json.dumps(data["releases"][0]))
        other["id"] = "older"
        other["role"] = "superseded"
        other["versionCode"] = 1
        other["sha256"] = "a" * 64
        data["releases"].append(other)

    expect_manifest_error(with_copy(add_unexplained), "supersededBecause", failures)

    for bad in (
        "http://192.168.1.9/a.apk",
        "https://127.0.0.1/a.apk",
        "https://10.1.2.3/a.apk",
        "https://user:pass@example.com/a.apk",
    ):
        try:
            build.assert_public_https(bad)
        except build.ManifestError:
            continue
        failures.append(f"accepted a non-public URL: {bad}")

    release = json.loads(json.dumps(base["releases"][0]))
    release["artifact"]["status"] = "published"
    release["artifact"]["httpsUrl"] = "https://example.com/app.apk"
    release["artifact"]["fileName"] = "app.apk"
    try:
        build._download_block(release, False)
    except build.ManifestError:
        pass
    else:
        failures.append("download link was rendered before hash verification")


def scan_text(label: str, text: str, failures: list[str]) -> None:
    folded = text.casefold()
    for word in FORBIDDEN:
        if word.casefold() in folded:
            failures.append(f"{label} contains forbidden text {word}")
    if PRIVATE_IP.search(text):
        failures.append(f"{label} contains a private address")
    if PHONE.search(text):
        failures.append(f"{label} contains a mobile number")
    if "tel:" in folded:
        failures.append(f"{label} contains a telephone link")


def check_page(path: Path, release: dict, failures: list[str]) -> list[str]:
    text = path.read_text(encoding="utf-8")
    scan_text(path.name, text, failures)
    parser = PageParser()
    parser.feed(text)
    if parser.lang != "zh-CN":
        failures.append(f"{path.name} lang is {parser.lang!r}")
    if parser.h1 != 1:
        failures.append(f"{path.name} has {parser.h1} h1 elements")
    if not parser.title.strip():
        failures.append(f"{path.name} is missing a title")
    if not parser.main or not parser.skip:
        failures.append(f"{path.name} is missing the main landmark or skip link")
    if not parser.csp:
        failures.append(f"{path.name} is missing the content security policy")
    if not any(item.get("charset") == "utf-8" for item in parser.metas):
        failures.append(f"{path.name} is missing charset")
    if not any(item.get("name") == "viewport" for item in parser.metas):
        failures.append(f"{path.name} is missing viewport")
    if len(parser.ids) != len(set(parser.ids)):
        failures.append(f"{path.name} has duplicate ids")
    for image in parser.images:
        if "alt" not in image:
            failures.append(f"{path.name} has an image without alt")
    urls: list[str] = []
    for anchor in parser.anchors:
        href = anchor.get("href") or ""
        if href.startswith(("mailto:", "#", "index.html", "product.html", "download.html", "privacy.html", "opensource.html", "contact.html", "release-manifest.json", "store-materials.txt", "assets/")):
            if anchor.get("target") == "_blank" and "noopener" not in (anchor.get("rel") or ""):
                failures.append(f"{path.name} blank target is missing rel: {href}")
            continue
        if not href.startswith("https://"):
            failures.append(f"{path.name} has a non-https external link: {href}")
            continue
        rel = anchor.get("rel") or ""
        if anchor.get("target") != "_blank" or "noopener" not in rel or "noreferrer" not in rel:
            failures.append(f"{path.name} external link is missing target or rel: {href}")
        try:
            build.assert_public_https(href)
        except build.ManifestError as exc:
            failures.append(f"{path.name} {exc}")
        urls.append(href)
    if path.name in {"index.html", "download.html"}:
        sha = release["sha256"]
        if parser.code_text.get("sha256", "").strip() != sha:
            failures.append(f"{path.name} does not show the manifest SHA-256")
        copies = [button for button in parser.buttons if button.get("data-copy")]
        if len(copies) != 1 or copies[0].get("data-copy") != sha or copies[0].get("type") != "button":
            failures.append(f"{path.name} copy control does not match the SHA-256")
        if release["updatedOn"] not in parser.times:
            failures.append(f"{path.name} does not mark the update date")
        if "客服电话：未提供" not in text or PINNED_PRODUCT["contactEmail"] not in text:
            failures.append(f"{path.name} is missing contact details")
    if release["artifact"]["status"] != "published":
        if re.search(r'href="[^"]+\.apk', text, flags=re.I):
            failures.append(f"{path.name} links to an apk while the artifact is unpublished")
        if "下载 Android 安装包" in text:
            failures.append(f"{path.name} offers a download that was not verified")
    for required in ("客服电话：未提供", PINNED_PRODUCT["contactEmail"], PINNED_PRODUCT["privacyCanonicalUrl"], PINNED_PRODUCT["sourceUrl"]):
        if required not in text:
            failures.append(f"{path.name} is missing {required}")
    return urls


def _contrast(foreground: str, background: str) -> float:
    def channel(hex_color: str) -> tuple[float, float, float]:
        value = hex_color.lstrip("#")
        red, green, blue = (int(value[index:index + 2], 16) / 255 for index in (0, 2, 4))

        def curve(component: float) -> float:
            return component / 12.92 if component <= 0.04045 else ((component + 0.055) / 1.055) ** 2.4

        red, green, blue = curve(red), curve(green), curve(blue)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue, 0.0, 0.0

    first = channel(foreground)[0]
    second = channel(background)[0]
    lighter, darker = max(first, second), min(first, second)
    return (lighter + 0.05) / (darker + 0.05)


def check_rendered(dest: Path, data: dict, release: dict, failures: list[str]) -> list[str]:
    css = (dest / "assets/site.css").read_text(encoding="utf-8")
    if "min-height: 44px" not in css or "@media (max-width: 800px)" not in css:
        failures.append("stylesheet is missing the touch target or the responsive rule")
    if ":focus-visible" not in css:
        failures.append("stylesheet is missing a visible focus style")
    colors = dict(re.findall(r"--(paper|card|ink|muted|sage):\s*(#[0-9a-fA-F]{6})", css))
    contrast_pairs = (
        ("ink", "paper", 7),
        ("muted", "paper", 4.5),
        ("sage", "paper", 4.5),
        ("ink", "card", 7),
    )
    if set(colors) != {"paper", "card", "ink", "muted", "sage"}:
        failures.append("stylesheet lost a documented color")
    else:
        for foreground, background, minimum in contrast_pairs:
            ratio = _contrast(colors[foreground], colors[background])
            if ratio < minimum:
                failures.append(f"{foreground} on {background} contrast is {ratio:.2f}")
        if _contrast("#ffffff", colors["sage"]) < 4.5:
            failures.append("white text on sage is below 4.5:1")
    urls: list[str] = []
    expected = {
        "index.html",
        "product.html",
        "download.html",
        "privacy.html",
        "opensource.html",
        "contact.html",
        "404.html",
        "assets/site.css",
        "assets/site.js",
        "assets/migo.png",
        "release-manifest.json",
        "store-materials.txt",
        "robots.txt",
    }
    found = set(tree_bytes(dest))
    if found != expected:
        failures.append(f"dist files differ from the expected set: {sorted(found ^ expected)}")
    for name in ("index.html", "product.html", "download.html", "privacy.html", "opensource.html", "contact.html", "404.html"):
        urls.extend(check_page(dest / name, release, failures))
    materials = (dest / "store-materials.txt").read_text(encoding="utf-8")
    scan_text("store-materials.txt", materials, failures)
    sha = release["sha256"]
    if f"SHA-256：{sha}" not in materials or "客服电话：未提供" not in materials:
        failures.append("store materials drifted from the manifest")
    if release["artifact"]["status"] == "not_published":
        if "公开下载地址：无" not in materials or ".apk" in materials:
            failures.append("store materials imply a download file")
    manifest_copy = (dest / "release-manifest.json").read_bytes()
    if manifest_copy != build.MANIFEST_PATH.read_bytes():
        failures.append("dist manifest is not the source manifest")
    for phrase in PINNED_RELEASE["publicScopeMustContain"]:
        for name in ("index.html", "download.html", "product.html"):
            if phrase not in (dest / name).read_text(encoding="utf-8"):
                failures.append(f"{name} dropped the scope sentence ({phrase})")
    if "内容由 AI 生成" in (dest / "download.html").read_text(encoding="utf-8"):
        download = (dest / "download.html").read_text(encoding="utf-8")
        if "不在这个安装包内" not in download.split("内容由 AI 生成", 1)[0][-80:]:
            failures.append("AI label mention is not tied to the exclusion sentence")
    return urls


def check_links(urls: list[str], failures: list[str]) -> None:
    seen: set[str] = set()
    for url in urls:
        if url in seen:
            continue
        seen.add(url)
        try:
            status, _, body = fetch(url)
        except Exception as exc:  # noqa: BLE001 - report the failing URL and continue
            failures.append(f"link check failed for {url}: {exc}")
            continue
        if status != 200 or not body:
            failures.append(f"link {url} returned HTTP {status}")


def check_privacy_mirror(data: dict, failures: list[str]) -> None:
    product = data["product"]
    try:
        status, headers, body = fetch(product["privacyCanonicalUrl"])
    except Exception as exc:  # noqa: BLE001
        failures.append(f"privacy URL check failed: {exc}")
        return
    if status != 200:
        failures.append(f"privacy URL returned HTTP {status}")
        return
    modified = headers.get("last-modified")
    if not modified:
        failures.append("privacy URL did not send Last-Modified")
    else:
        observed = parsedate_to_datetime(modified).date().isoformat()
        if observed != product["privacyUpstreamLastModified"]:
            failures.append(f"privacy Last-Modified date is {observed}")
    filled = build.fill(
        (WEBSITE / "content/privacy-sections.html").read_text(encoding="utf-8"),
        {
            "OPERATOR": product["operator"],
            "EMAIL": product["contactEmail"],
            "PRIVACY_URL": product["privacyCanonicalUrl"],
        },
        raw=set(),
    )
    live_sections = h2_sections(body.decode("utf-8"))
    our_sections = h2_sections(filled)
    if len(live_sections) < 11 or len(our_sections) != 11:
        failures.append("privacy section count does not match the formal page")
        return
    for index, (live, ours) in enumerate(zip(live_sections[:10], our_sections[:10]), start=1):
        if live != ours:
            failures.append(f"privacy section {index} differs from the formal page")
    title, rest = our_sections[10]
    if title != live_sections[10][0]:
        failures.append("privacy contact heading differs from the formal page")
    if product["contactEmail"] not in rest or product["privacyCanonicalUrl"] not in rest:
        failures.append("privacy contact section lost the email or formal URL")
    if "本站所在 URL" in rest:
        failures.append("privacy copy still points at an unspecified page URL")


def check_source_identity(release: dict, failures: list[str]) -> None:
    sha = release["sha256"]
    text_suffixes = {".html", ".css", ".js", ".json", ".md", ".txt", ".yml"}
    for path in WEBSITE.rglob("*"):
        if not path.is_file() or "dist" in path.parts or path.name == "check.py":
            continue
        if path.suffix not in text_suffixes:
            continue
        text = path.read_text(encoding="utf-8")
        if path.name != "release-manifest.json" and sha in text:
            failures.append(f"{path.relative_to(WEBSITE)} repeats the SHA-256 outside the manifest")
        scan_text(str(path.relative_to(WEBSITE)), text, failures)
    try:
        commit, created = git_tag_commit_and_date(release["gitTag"])
    except (subprocess.CalledProcessError, build.ManifestError) as exc:
        failures.append(f"git tag check failed: {exc}")
        return
    if commit != release["gitCommit"]:
        failures.append(f"tag {release['gitTag']} points at {commit}")
    if created != release["updatedOn"]:
        failures.append(f"tag date {created} does not match updatedOn {release['updatedOn']}")


def serve_and_request(dest: Path, failures: list[str]) -> None:
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(dest), **kwargs)

        def log_message(self, format: str, *args) -> None:
            return

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    try:
        for name in ("index.html", "product.html", "download.html", "privacy.html", "opensource.html", "contact.html", "assets/site.css", "assets/site.js", "assets/migo.png", "release-manifest.json"):
            url = f"http://{host}:{port}/{name}"
            with urllib.request.urlopen(url, timeout=10) as response:
                if response.status != 200 or not response.read(1):
                    failures.append(f"local request failed for {name}")
    except (urllib.error.URLError, socket.timeout, TimeoutError) as exc:
        failures.append(f"local server check failed: {exc}")
    finally:
        server.shutdown()
        thread.join(timeout=5)


def pin_release(data: dict, release: dict, failures: list[str]) -> None:
    product = data["product"]
    for key, expected in PINNED_PRODUCT.items():
        if product.get(key) != expected:
            failures.append(f"product.{key} changed from the pinned public value")
    if release["id"] != PINNED_RELEASE["id"]:
        failures.append("current release id changed; review the pin before replacing vc102")
        return
    for key in ("versionName", "versionCode", "sha256", "updatedOn", "gitTag", "gitCommit", "channel"):
        if release[key] != PINNED_RELEASE[key]:
            failures.append(f"pinned vc102 field {key} changed")
    for phrase in PINNED_RELEASE["publicScopeMustContain"]:
        if phrase not in release["publicScope"]:
            failures.append(f"vc102 scope no longer contains {phrase}")


def main() -> int:
    failures: list[str] = []
    negative_checks(failures)
    try:
        data = build.load_manifest()
        release = build.validate_manifest(data)
    except build.ManifestError as exc:
        print(f"manifest: {exc}")
        return 1
    pin_release(data, release, failures)
    check_source_identity(release, failures)
    verified = False
    artifact = release["artifact"]
    if artifact["status"] == "published":
        try:
            digest, content_type = sha256_of(artifact["httpsUrl"])
        except Exception as exc:  # noqa: BLE001
            failures.append(f"download verification failed: {exc}")
            digest, content_type = "", ""
        if digest != release["sha256"]:
            failures.append("downloaded file SHA-256 does not match the manifest")
        if content_type.lower().startswith("text/html"):
            failures.append("download URL returned HTML instead of an apk")
        verified = digest == release["sha256"] and not content_type.lower().startswith("text/html")
    if failures and artifact["status"] == "published":
        verified = False
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "dist"
        try:
            build.render(dest, download_verified=verified and artifact["status"] == "published")
        except build.ManifestError as exc:
            print(f"render: {exc}")
            return 1
        urls = check_rendered(dest, data, release, failures)
        check_links(urls, failures)
        check_privacy_mirror(data, failures)
        js = WEBSITE / "src/site.js"
        syntax = subprocess.run(["node", "--check", str(js)], cwd=REPO, text=True, capture_output=True)
        if syntax.returncode != 0:
            failures.append(syntax.stderr.strip() or "site.js failed the syntax check")
        serve_and_request(dest, failures)
        if failures:
            print("\n".join(failures))
            return 1
        stale = DIST.exists() and tree_bytes(DIST) != tree_bytes(dest)
        if stale or not DIST.exists():
            if DIST.exists():
                shutil.rmtree(DIST)
            shutil.copytree(dest, DIST)
        if stale:
            print("website/dist was regenerated from the manifest. Review the diff and run the check again.")
            return 1
    print("website check passed")
    print(f"current release: {release['id']} {release['versionName']} / {release['versionCode']}")
    print(f"sha256: {release['sha256']}")
    print(f"updated: {release['updatedOn']}")
    print(f"download: {artifact['status']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
