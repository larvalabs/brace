#!/usr/bin/env bash
# Dependency-vulnerability gate (security review 2026-10, finding M12).
#
# Scans pom.xml's resolved dependency tree (direct + transitive, all scopes) with
# OSV-Scanner and fails on any HIGH/CRITICAL advisory; lower-severity ones are
# printed and do not fail. The threshold rule lives in osv_gate.py. Ignores go in
# osv-scanner.toml at the repo root ([[IgnoredVulns]] with ignoreUntil and reason).
#
# Runs in CI (ci.yml, publish.yml) and locally, from anywhere in the repo:
#
#   .github/scripts/osv-scan.sh                    # scan + Maven coverage cross-check
#   .github/scripts/osv-scan.sh --no-maven-check   # skip the `mvn dependency:list` cross-check
#
# Needs bash, curl, python3, sha256sum or shasum, and (for the cross-check) mvn.
# The pinned OSV-Scanner release binary is downloaded once, checked against the
# sha256 below, and cached under ${XDG_CACHE_HOME:-~/.cache}/brace/.
#
# To upgrade OSV-Scanner: set OSV_SCANNER_VERSION and replace the four sums with the
# matching lines of that release's osv-scanner_SHA256SUMS asset.
set -euo pipefail

OSV_SCANNER_VERSION="2.7.0"
sha256_for() {
    case "$1" in
        linux_amd64)  echo "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b" ;;
        linux_arm64)  echo "4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7" ;;
        darwin_amd64) echo "e82c5df7ab0a42dff29a1cd0660711447b56b4c0483ff66ad8634df10fd81a45" ;;
        darwin_arm64) echo "d4926ef8da134332543be276dff157e4c170f99ad91fc48e89448a8816bdf70b" ;;
        *) return 1 ;;
    esac
}

maven_check=1
for arg in "$@"; do
    case "$arg" in
        --no-maven-check) maven_check=0 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "osv-scan: unknown argument: $arg" >&2; exit 2 ;;
    esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cd "$repo_root"

case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=darwin ;;
    *) echo "osv-scan: unsupported OS $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) echo "osv-scan: unsupported architecture $(uname -m)" >&2; exit 2 ;;
esac
platform="${os}_${arch}"
expected_sha="$(sha256_for "$platform")" || { echo "osv-scan: no pinned checksum for $platform" >&2; exit 2; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/brace/osv-scanner-${OSV_SCANNER_VERSION}"
scanner="$cache_dir/osv-scanner_${platform}"
if [[ ! -x "$scanner" || "$(sha256_of "$scanner")" != "$expected_sha" ]]; then
    mkdir -p "$cache_dir"
    url="https://github.com/google/osv-scanner/releases/download/v${OSV_SCANNER_VERSION}/osv-scanner_${platform}"
    echo "osv-scan: downloading OSV-Scanner ${OSV_SCANNER_VERSION} (${platform})"
    partial="$(mktemp "$cache_dir/download.XXXXXX")"
    trap 'rm -f "$partial"' EXIT
    curl -fsSL --proto '=https' --tlsv1.2 --retry 3 -o "$partial" "$url"
    actual_sha="$(sha256_of "$partial")"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
        echo "osv-scan: checksum mismatch for $url" >&2
        echo "  expected $expected_sha" >&2
        echo "  actual   $actual_sha" >&2
        exit 1
    fi
    chmod +x "$partial"
    mv "$partial" "$scanner"
    trap - EXIT
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# OSV-Scanner resolves pom.xml's transitive tree itself (deps.dev data source).
# Exit status: 0 = no advisories, 1 = advisories found (osv_gate.py rates them),
# anything else = the scan itself failed (e.g. resolution error), which fails the gate.
status=0
"$scanner" scan source \
    --lockfile pom.xml \
    --config osv-scanner.toml \
    --format json \
    --all-packages \
    --output-file "$work/osv.json" || status=$?
if [[ "$status" -ne 0 && "$status" -ne 1 ]]; then
    echo "osv-scan: OSV-Scanner failed (exit $status); the dependency gate cannot pass without a complete scan." >&2
    exit 1
fi

gate_args=("$work/osv.json" --config osv-scanner.toml)
if [[ "$maven_check" -eq 1 ]]; then
    # Maven's own resolution, to check OSV-Scanner scanned exactly what Maven builds with.
    mvn_cmd="${MVN:-mvn}"
    "$mvn_cmd" -B -q dependency:list -DoutputFile="$work/maven-deps.txt" -DappendOutput=false
    gate_args+=(--maven-list "$work/maven-deps.txt")
fi

python3 "$script_dir/osv_gate.py" "${gate_args[@]}"
