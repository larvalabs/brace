#!/bin/sh
# Brace installer — bootstraps the brace launcher.
#
#   curl -fsSL https://github.com/larvalabs/brace/raw/main/install.sh | sh
#
# Installs a framework toolchain under ~/.brace/toolchains/<version> and points
# ~/.brace/bin/brace at it. After the first install, use `brace self-update` to
# move to newer versions — this script is only needed to bootstrap.
#
# Environment overrides:
#   BRACE_DIR           install root (default: ~/.brace)
#   BRACE_VERSION       version to install (default: latest released)
#   BRACE_RELEASE_BASE  base URL for dist zips (<base>/v<V>/brace-<V>.zip)
#   BRACE_LATEST_URL    release-metadata URL whose tag_name names the latest
#   BRACE_MODIFY_PATH   if "1", append the PATH export to your shell rc file
#                       (default: off — the installer only prints the line)

set -eu

BRACE_DIR="${BRACE_DIR:-$HOME/.brace}"
RELEASE_BASE="${BRACE_RELEASE_BASE:-https://github.com/larvalabs/brace/releases/download}"
LATEST_URL="${BRACE_LATEST_URL:-https://api.github.com/repos/larvalabs/brace/releases/latest}"

info() { printf '▸ %s\n' "$*" >&2; }
err()  { printf '✗ %s\n' "$*" >&2; }
die()  { err "$@"; exit 1; }

command -v curl  >/dev/null 2>&1 || die "curl is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

# --- resolve version ---
VERSION="${BRACE_VERSION:-}"
if [ -z "$VERSION" ]; then
    info "Resolving latest brace version ..."
    TAG=$(curl -fsSL "$LATEST_URL" \
        | grep -o '"tag_name"[ ]*:[ ]*"[^"]*"' \
        | head -1 \
        | sed 's/.*"\([^"]*\)"$/\1/')
    [ -n "$TAG" ] || die "Could not determine latest version from $LATEST_URL"
    VERSION="${TAG#v}"
fi
VERSION="${VERSION#v}"

DEST="$BRACE_DIR/toolchains/$VERSION"
BIN="$BRACE_DIR/bin"
URL="$RELEASE_BASE/v$VERSION/brace-$VERSION.zip"

# --- download + unpack ---
if [ -d "$DEST/lib" ]; then
    info "brace $VERSION already present at $DEST"
else
    info "Downloading brace $VERSION ..."
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    curl -fsSL "$URL" -o "$TMP/brace.zip" || die "Download failed: $URL"
    unzip -q "$TMP/brace.zip" -d "$TMP" || die "Unpack failed"
    [ -d "$TMP/brace-$VERSION" ] || die "Unexpected archive layout (missing brace-$VERSION/)"
    mkdir -p "$BRACE_DIR/toolchains"
    rm -rf "$DEST"
    mv "$TMP/brace-$VERSION" "$DEST"
    info "Installed to $DEST"
fi

# --- link launcher ---
mkdir -p "$BIN"
ln -sfn "$DEST/bin/brace" "$BIN/brace"
chmod +x "$DEST/bin/brace" 2>/dev/null || true
info "Linked $BIN/brace -> $DEST/bin/brace"

# --- PATH ---
case ":$PATH:" in
    *":$BIN:"*)
        printf '\n✓ brace %s installed. Run: brace version\n' "$VERSION" >&2
        ;;
    *)
        LINE="export PATH=\"$BIN:\$PATH\""
        # Only touch the user's shell rc when explicitly asked to.
        if [ "${BRACE_MODIFY_PATH:-0}" = "1" ]; then
            RC=""
            case "${SHELL##*/}" in
                zsh)  RC="$HOME/.zshrc" ;;
                bash) [ -f "$HOME/.bashrc" ] && RC="$HOME/.bashrc" || RC="$HOME/.bash_profile" ;;
            esac
            if [ -n "$RC" ] && ! grep -qF "$BIN" "$RC" 2>/dev/null; then
                printf '\n# Added by the brace installer\n%s\n' "$LINE" >> "$RC"
                info "Added $BIN to PATH in $RC"
            fi
        fi
        printf '\n✓ brace %s installed.\n' "$VERSION" >&2
        printf '  Add %s to your PATH:\n    %s\n' "$BIN" "$LINE" >&2
        printf '  (or re-run with BRACE_MODIFY_PATH=1 to append this automatically)\n' >&2
        ;;
esac
