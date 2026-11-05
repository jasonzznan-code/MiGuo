#!/usr/bin/env python3
"""Render the Miguo site from website/release-manifest.json.

Run website/scripts/check.py to validate, verify links, and write website/dist.
"""

from __future__ import annotations

import html
import json
import re
import shutil
from datetime import date
from pathlib import Path
from urllib.parse import urlparse

WEBSITE = Path(__file__).resolve().parents[1]
REPO = WEBSITE.parent
MANIFEST_PATH = WEBSITE / "release-manifest.json"
TEMPLATE_DIR = WEBSITE / "templates"
LICENSE_MD = REPO / "android/app/src/main/assets/opensource_licenses.md"

GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

PRODUCT_KEYS = {
    "nameZh",
    "nameEn",
    "applicationId",
    "operator",
    "contactEmail",
    "contactPhone",
    "sourceUrl",
    "licenseSpdx",
    "privacyCanonicalUrl",
    "privacyEffectiveOn",
    "privacyMirroredFromFetchOn",
    "privacyUpstreamLastModified",
    "minSdk",
    "targetSdk",
}
RELEASE_KEYS = {
    "id",
    "role",
    "platform",
    "channel",
    "versionName",
    "versionCode",
    "gitTag",
    "gitCommit",
    "updatedOn",
    "updatedOnBasis",
    "sha256",
    "sha256Basis",
    "publicScope",
    "artifact",
    "storeStatus",
}
ARTIFACT_KEYS = {"httpsUrl", "fileName", "status", "checkedOn", "evidence"}
TOP_KEYS = {"schemaVersion", "product", "currentReleaseId", "releases"}

TOKEN = re.compile(r"@@([A-Z0-9_]+)@@")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
TAG_RE = re.compile(r"^v[0-9A-Za-z._-]+$")
CHANNEL_RE = re.compile(r"^[A-Za-z0-9._-]+$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

NAV_ITEMS = [
    ("index.html", "首页"),
    ("product.html", "产品简介"),
    ("download.html", "下载"),
    ("privacy.html", "隐私政策"),
    ("opensource.html", "开源"),
    ("contact.html", "联系"),
]


class ManifestError(Exception):
    pass


def load_manifest(path: Path = MANIFEST_PATH) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ManifestError("manifest root must be an object")
    return data


def assert_public_https(url: str) -> None:
    if not isinstance(url, str) or not url.startswith("https://"):
        raise ManifestError(f"URL must be https: {url!r}")
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ManifestError(f"URL is not a public https address: {url}")
    if parsed.username or parsed.password:
        raise ManifestError(f"URL must not include credentials: {url}")
    host = parsed.hostname.rstrip(".").lower()
    if host in {"localhost", "127.0.0.1", "::1"} or host.endswith(".local"):
        raise ManifestError(f"URL host is not public: {url}")
    try:
        import ipaddress

        ip = ipaddress.ip_address(host)
    except ValueError:
        ip = None
    if ip is not None and (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    ):
        raise ManifestError(f"URL host is not public: {url}")
    if parsed.port == 0:
        raise ManifestError(f"URL port is not usable: {url}")


def _date(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise ManifestError(f"{label} must be a date string")
    try:
        date.fromisoformat(value)
    except ValueError as exc:
        raise ManifestError(f"{label} is not a real date: {value}") from exc
    return value


def _text(value: object, label: str, min_len: int = 1) -> str:
    if not isinstance(value, str) or len(value.strip()) < min_len:
        raise ManifestError(f"{label} must be a non-empty string")
    return value.strip()


def validate_manifest(data: dict) -> dict:
    extra = set(data) - TOP_KEYS
    if extra or set(data) != TOP_KEYS:
        raise ManifestError(f"manifest keys must be {sorted(TOP_KEYS)}")
    if data["schemaVersion"] != 1:
        raise ManifestError("schemaVersion must be 1")
    product = data["product"]
    if not isinstance(product, dict) or set(product) != PRODUCT_KEYS:
        raise ManifestError("product keys do not match the schema")
    if product["contactPhone"] is not None:
        raise ManifestError("contactPhone must stay null until a real number is supplied")
    if not EMAIL_RE.fullmatch(product["contactEmail"]):
        raise ManifestError("contactEmail is not a single email address")
    assert_public_https(product["sourceUrl"])
    assert_public_https(product["privacyCanonicalUrl"])
    if product["sourceUrl"].rstrip("/").endswith(".git"):
        raise ManifestError("sourceUrl must be the repository page, not a git remote")
    for label in ("privacyEffectiveOn", "privacyMirroredFromFetchOn", "privacyUpstreamLastModified"):
        _date(product[label], label)
    for label in ("nameZh", "nameEn", "operator", "licenseSpdx", "applicationId"):
        _text(product[label], label)
    if product["applicationId"] != "com.miguo.voice":
        raise ManifestError("applicationId must be com.miguo.voice")
    if not isinstance(product["minSdk"], int) or not isinstance(product["targetSdk"], int):
        raise ManifestError("minSdk and targetSdk must be integers")
    if product["minSdk"] < 1 or product["targetSdk"] < product["minSdk"]:
        raise ManifestError("SDK levels are inconsistent")

    releases = data["releases"]
    if not isinstance(releases, list) or not releases:
        raise ManifestError("releases must be a non-empty list")
    current_id = _text(data["currentReleaseId"], "currentReleaseId")
    seen_ids: set[str] = set()
    seen_sha: set[str] = set()
    seen_codes: set[int] = set()
    current = None
    for release in releases:
        if not isinstance(release, dict):
            raise ManifestError("each release must be an object")
        unknown = set(release) - RELEASE_KEYS - {"supersededBecause"}
        if unknown:
            raise ManifestError(f"unknown release fields: {sorted(unknown)}")
        missing = RELEASE_KEYS - set(release)
        if missing:
            raise ManifestError(f"release missing fields: {sorted(missing)}")
        rel_id = _text(release["id"], "id")
        if rel_id in seen_ids:
            raise ManifestError(f"duplicate release id {rel_id}")
        seen_ids.add(rel_id)
        role = release["role"]
        if role not in {"current", "superseded", "withdrawn"}:
            raise ManifestError(f"invalid role on {rel_id}")
        if release["platform"] != "android":
            raise ManifestError("schema 1 only records the Android package")
        if not CHANNEL_RE.fullmatch(str(release["channel"])):
            raise ManifestError(f"invalid channel on {rel_id}")
        version_name = _text(release["versionName"], "versionName")
        if any(ch.isspace() for ch in version_name):
            raise ManifestError("versionName must not contain whitespace")
        code = release["versionCode"]
        if not isinstance(code, int) or code < 1:
            raise ManifestError("versionCode must be a positive integer")
        if code in seen_codes:
            raise ManifestError(f"duplicate versionCode {code}")
        seen_codes.add(code)
        if not TAG_RE.fullmatch(str(release["gitTag"])):
            raise ManifestError(f"gitTag is not a tag name: {release['gitTag']}")
        if not COMMIT_RE.fullmatch(str(release["gitCommit"])):
            raise ManifestError("gitCommit must be a 40-character commit id")
        _date(release["updatedOn"], "updatedOn")
        _text(release["updatedOnBasis"], "updatedOnBasis", 8)
        sha = release["sha256"]
        if not isinstance(sha, str) or not SHA256_RE.fullmatch(sha):
            raise ManifestError("sha256 must be 64 lowercase hex characters")
        if sha in seen_sha:
            raise ManifestError("duplicate sha256")
        seen_sha.add(sha)
        _text(release["sha256Basis"], "sha256Basis", 8)
        _text(release["publicScope"], "publicScope", 20)
        if release["storeStatus"] != "not_recorded":
            raise ManifestError("storeStatus cannot claim a store submission in schema 1")
        artifact = release["artifact"]
        if not isinstance(artifact, dict) or set(artifact) != ARTIFACT_KEYS:
            raise ManifestError("artifact fields do not match the schema")
        status = artifact["status"]
        if status not in {"not_published", "published"}:
            raise ManifestError("artifact.status must be not_published or published")
        _date(artifact["checkedOn"], "artifact.checkedOn")
        _text(artifact["evidence"], "artifact.evidence", 8)
        url = artifact["httpsUrl"]
        file_name = artifact["fileName"]
        if role == "current":
            if "supersededBecause" in release:
                raise ManifestError("the current release must not include supersededBecause")
            if current is not None:
                raise ManifestError("more than one release has role current")
            current = release
        else:
            reason = release.get("supersededBecause")
            if not isinstance(reason, str) or len(reason.strip()) < 10:
                raise ManifestError(f"{rel_id} needs supersededBecause explaining why it is not current")
            if status == "published" or url is not None:
                raise ManifestError("only the current release may have a download address")
        if status == "not_published":
            if url is not None or file_name is not None:
                raise ManifestError("an unpublished artifact cannot carry a URL or file name")
        elif status == "published":
            if role != "current":
                raise ManifestError("a non-current release cannot be the download")
            if not isinstance(url, str) or not isinstance(file_name, str):
                raise ManifestError("a published artifact needs httpsUrl and fileName")
            assert_public_https(url)
            if "/" in file_name or "\\" in file_name or not file_name.endswith(".apk"):
                raise ManifestError("fileName must be a single .apk file name")
            if not url.split("?", 1)[0].endswith(".apk"):
                raise ManifestError("download URL must end with .apk")
    if current is None or current["id"] != current_id:
        raise ManifestError("currentReleaseId must match the single current release")
    return current


def license_rows(path: Path = LICENSE_MD) -> list[list[str]]:
    rows: list[list[str]] = []
    started = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("| 组件 |"):
            started = True
            continue
        if not started:
            continue
        if not line.startswith("|"):
            break
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 3:
            raise ManifestError(f"license table row is not 3 columns: {line}")
        if all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            continue
        rows.append(cells)
    if not rows:
        raise ManifestError("no third-party rows found in opensource_licenses.md")
    return rows


def fill(template: str, values: dict[str, str], raw: set[str]) -> str:
    def repl(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in values:
            raise ManifestError(f"missing token @@{key}@@")
        value = values[key]
        return value if key in raw else html.escape(value, quote=True)

    rendered = TOKEN.sub(repl, template)
    leftover = TOKEN.findall(rendered)
    if leftover:
        raise ManifestError(f"unfilled tokens: {leftover}")
    return rendered


def _download_block(release: dict, download_verified: bool) -> str:
    artifact = release["artifact"]
    if artifact["status"] == "not_published":
        if download_verified:
            raise ManifestError("nothing to verify when the artifact is unpublished")
        return (
            '<div class="notice" role="status">'
            "<p><strong>下载文件</strong></p>"
            "<p>还没有经过核验的 HTTPS 安装包地址。这页不放置下载链接。</p>"
            "</div>"
        )
    if not download_verified:
        raise ManifestError("refusing to render a download link before the file hash is verified")
    url = html.escape(artifact["httpsUrl"], quote=True)
    name = html.escape(artifact["fileName"], quote=True)
    return (
        '<div class="notice">'
        "<p><strong>下载文件</strong></p>"
        f"<p>文件名 {name}。下载后请再核对 SHA-256 与本页一致。</p>"
        f'<p><a class="button" href="{url}" target="_blank" rel="noopener noreferrer">'
        "下载 Android 安装包<span class=\"vh\">（在新窗口打开）</span></a></p>"
        "</div>"
    )


def _values(data: dict, release: dict, download_verified: bool) -> dict[str, str]:
    product = data["product"]
    source = product["sourceUrl"].rstrip("/")
    tag = release["gitTag"]
    artifact = release["artifact"]
    rows = "\n".join(
        "<tr>"
        + "".join(f"<td>{html.escape(cell)}</td>" for cell in row)
        + "</tr>"
        for row in license_rows()
    )
    privacy_template = (WEBSITE / "content/privacy-sections.html").read_text(encoding="utf-8")
    base_values = {
        "NAME_ZH": product["nameZh"],
        "NAME_EN": product["nameEn"],
        "APPLICATION_ID": product["applicationId"],
        "OPERATOR": product["operator"],
        "EMAIL": product["contactEmail"],
        "SOURCE_URL": source,
        "SOURCE_TAG": tag,
        "TAG_URL": f"{source}/tree/{tag}",
        "LICENSE_FILE_URL": f"{source}/blob/{tag}/LICENSE",
        "LICENSE": product["licenseSpdx"],
        "GPL_URL": GPL_URL,
        "APACHE_URL": APACHE_URL,
        "PRIVACY_URL": product["privacyCanonicalUrl"],
        "PRIVACY_EFFECTIVE": product["privacyEffectiveOn"],
        "PRIVACY_FETCHED_ON": product["privacyMirroredFromFetchOn"],
        "PRIVACY_UPSTREAM_MODIFIED": product["privacyUpstreamLastModified"],
        "VERSION_NAME": release["versionName"],
        "VERSION_CODE": str(release["versionCode"]),
        "UPDATED_ON": release["updatedOn"],
        "UPDATED_ON_BASIS": release["updatedOnBasis"],
        "SHA256": release["sha256"],
        "SHA256_BASIS": release["sha256Basis"],
        "CHANNEL": release["channel"],
        "GIT_COMMIT": release["gitCommit"],
        "MIN_SDK": str(product["minSdk"]),
        "TARGET_SDK": str(product["targetSdk"]),
        "PUBLIC_SCOPE": release["publicScope"],
        "ARTIFACT_EVIDENCE": release["artifact"]["evidence"],
        "CHECKED_ON": artifact["checkedOn"],
        "LICENSE_ROWS": rows,
        "DOWNLOAD_BLOCK": _download_block(release, download_verified),
    }
    for key in ("TAG_URL", "LICENSE_FILE_URL", "GPL_URL", "APACHE_URL"):
        assert_public_https(base_values[key])
    base_values["PRIVACY_BODY"] = fill(
        privacy_template,
        base_values,
        raw=set(),
    )
    return base_values


def _nav(current: str | None) -> str:
    parts = []
    for href, label in NAV_ITEMS:
        current_attr = ' aria-current="page"' if href == current else ""
        parts.append(f'<a href="{href}"{current_attr}>{html.escape(label)}</a>')
    return "\n".join(parts)


def _footer(values: dict[str, str]) -> str:
    template = """<footer class="site-footer">
  <div class="wrap">
    <p>@@OPERATOR@@</p>
    <p>联系邮箱：<a href="mailto:@@EMAIL@@">@@EMAIL@@</a></p>
    <p>客服电话：未提供</p>
    <p>隐私政策正式地址：<a href="@@PRIVACY_URL@@" target="_blank" rel="noopener noreferrer">@@PRIVACY_URL@@<span class="vh">（在新窗口打开）</span></a></p>
    <p>源码：<a href="@@SOURCE_URL@@" target="_blank" rel="noopener noreferrer">@@SOURCE_URL@@<span class="vh">（在新窗口打开）</span></a></p>
    <p>许可证：@@LICENSE@@</p>
  </div>
</footer>"""
    return fill(template, values, raw=set())


def render(dest: Path, data: dict | None = None, *, download_verified: bool = False) -> dict:
    data = load_manifest() if data is None else data
    release = validate_manifest(data)
    values = _values(data, release, download_verified)
    base = (TEMPLATE_DIR / "base.html").read_text(encoding="utf-8")
    pages = [
        ("index.html", "home.html", "米果（Migo）", "米果 Android 客户端的产品说明、隐私政策、源码入口与当前版本记录。", "index.html"),
        ("product.html", "product.html", "产品简介 · 米果", "米果当前版本记录里实际包含的能力，以及还不应当成已提供的部分。", "product.html"),
        ("download.html", "download.html", "Android 下载 · 米果", "米果 Android 当前版本号、更新日期、SHA-256 与下载地址状态。", "download.html"),
        ("privacy.html", "privacy.html", "隐私政策 · 米果", "米果隐私政策正文，以及正式隐私政策地址。", "privacy.html"),
        ("opensource.html", "opensource.html", "开源与源码 · 米果", "米果 Android 客户端的源码入口、许可证和第三方组件。", "opensource.html"),
        ("contact.html", "contact.html", "联系方式 · 米果", "米果智能的公开邮箱。客服电话未提供。", "contact.html"),
        ("404.html", "not-found.html", "没有找到页面 · 米果", "这个地址不在米果官网页面中。", None),
    ]
    if dest.exists():
        shutil.rmtree(dest)
    (dest / "assets").mkdir(parents=True)
    shutil.copyfile(WEBSITE / "assets/migo.png", dest / "assets/migo.png")
    shutil.copyfile(WEBSITE / "src/site.css", dest / "assets/site.css")
    shutil.copyfile(WEBSITE / "src/site.js", dest / "assets/site.js")
    shutil.copyfile(MANIFEST_PATH, dest / "release-manifest.json")
    (dest / "robots.txt").write_text("User-agent: *\nAllow: /\n", encoding="utf-8")
    raw = {"NAV", "MAIN", "FOOTER"}
    for filename, template_name, title, description, current in pages:
        main = fill((TEMPLATE_DIR / template_name).read_text(encoding="utf-8"), values, raw={"DOWNLOAD_BLOCK", "PRIVACY_BODY", "LICENSE_ROWS"})
        page_values = {
            **values,
            "TITLE": title,
            "DESCRIPTION": description,
            "NAV": _nav(current),
            "MAIN": main,
            "FOOTER": _footer(values),
        }
        page = fill(base, page_values, raw=raw)
        (dest / filename).write_text(page, encoding="utf-8")
    return release


def main() -> None:
    raise SystemExit("请运行 python3 website/scripts/check.py")


if __name__ == "__main__":
    main()
