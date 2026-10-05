#!/usr/bin/env python3
"""License compliance check for Nyral (zero Gradle plugin).

Resolves dependency coordinates from a `./gradlew :app:dependencies
--configuration releaseRuntimeClasspath` dump, fetches each dependency POM
(Maven Central first, then Google Maven for androidx artifacts), walks the
<parent> chain for license metadata and fails if any dependency falls outside
the allowlist or no license can be determined.

Usage: python3 .github/ci/license-check.py <dependencies-dump.txt>
"""
import re
import time
import sys
import urllib.request
import xml.etree.ElementTree as ET

MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
USER_AGENT = "nyral-ci-license-check/1.0"

# Allowlist: license names matched case-insensitively as substrings.
# Covers the project's declared dependency table (README.md "License" section).
ALLOWLIST = [
    "apache-2.0",
    "apache license",
    "apache software license",
    "mit",
    "0bsd",
    "bsd-2-clause",
    "bsd-3-clause",
    "bsd 2-clause",
    "bsd 3-clause",
    "mpl-2.0",
    "mozilla public license",
    "epl-1.0",
    "epl-2.0",
    "eclipse public license",
    "isc",
    "public domain",
    "bouncy castle",
    "json license",
    "unrar",
]

# Dependencies whose POM carries no <licenses> metadata but are known-licensed.
# Key: (groupId, artifactId); Value: license names for the allowlist match.
KNOWN_LICENSE_MAP = {
    ("org.bouncycastle", "bcprov-jdk18on"): ["Bouncy Castle Licence"],
}

# Gradle tree lines look like:  +--- group:artifact:version -> 2.0.21 (*)
# Capture the coordinate anywhere in the line; prefer the resolved version after "->".
LINE_RE = re.compile(
    r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?:\s*->\s*([A-Za-z0-9_.\-]+))?"
)

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def extract_coords(path: str) -> set:
    coords = set()
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = LINE_RE.search(line)
            if m:
                ver = m.group(4) or m.group(3)
                coords.add((m.group(1), m.group(2), ver))
    return coords


def pom_url(repo: str, group: str, artifact: str, version: str) -> str:
    return f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"


def fetch(url: str, tries: int = 3) -> str:
    """Fetch with retries; CI to Maven Central/Google can be flaky."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last_exc = None
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            if attempt < tries - 1:
                time.sleep(1.5 * (attempt + 1))
    raise last_exc or RuntimeError("unreachable")


def fetch_pom(group: str, artifact: str, version: str):
    """Return (xml_text, final_url_repo) trying Central then Google Maven."""
    repos = (MAVEN_CENTRAL, GOOGLE_MAVEN)
    last_exc = None
    for repo in repos:
        url = pom_url(repo, group, artifact, version)
        try:
            return fetch(url), repo
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
    raise last_exc or RuntimeError("no repo reachable")


def pom_licenses(group: str, artifact: str, version: str, depth: int = 0) -> list:
    if depth > 3:
        return ["<PARENT_CHAIN_TOO_DEEP>"]
    try:
        xml, _repo = fetch_pom(group, artifact, version)
        root = ET.fromstring(xml)
    except Exception as exc:  # noqa: BLE001
        return [f"<FETCH_FAILED: {exc}>"]
    except ET.ParseError as exc:
        return [f"<PARSE_FAILED: {exc}>"]

    names = []
    for lic in root.findall(".//m:licenses/m:license/m:name", NS):
        if lic.text and lic.text.strip():
            names.append(lic.text.strip())

    if not names:
        # License metadata often lives in the parent POM; walk up the chain.
        parent = root.find("m:parent", NS)
        if parent is not None:
            pg = parent.findtext("m:groupId", None, NS)
            pa = parent.findtext("m:artifactId", None, NS)
            pv = parent.findtext("m:version", None, NS)
            if pg and pa and pv:
                return pom_licenses(pg, pa, pv, depth + 1)
    if not names:
        # Known exceptions for projects that don't publish <licenses> in POM.
        known = KNOWN_LICENSE_MAP.get((group, artifact))
        if known:
            return list(known)
        names = ["<NONE>"]
    return names


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python3 .github/ci/license-check.py <dependencies-dump.txt>")
        return 2
    coords = extract_coords(sys.argv[1])
    if not coords:
        print("FATAL: no dependency coordinates found in dump")
        return 1
    print(f"Resolved {len(coords)} unique dependencies; fetching POM licenses...")

    failures = []
    checked = 0
    for c in sorted(coords):
        checked += 1
        lic_names = pom_licenses(c[0], c[1], c[2])
        joined = " | ".join(lic_names).lower()
        if any(w in joined for w in ALLOWLIST):
            continue
        failures.append(f"{c[0]}:{c[1]}:{c[2]} -> {lic_names}")

    if failures:
        print(f"FAIL: {len(failures)} of {checked} dependencies failed the allowlist:")
        for f in failures:
            print("  " + f)
        return 1
    print(f"OK: {checked} dependencies all within the license allowlist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
