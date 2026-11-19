#!/usr/bin/env bash
# Publishes a Qbotics release on GitHub: the jar built here, attached to a tagged commit.
#
#   ./release.sh 0.2.0                  release version 0.2.0
#   ./release.sh 0.2.0 --notes notes.md  put notes.md at the top of the release text
#   ./release.sh 0.2.0 --dry-run        do every check, the tests and the build, publish nothing
#
# What it does, stopping at the first problem:
#   1. checks: on main, no uncommitted changes, up to date with GitHub, the tag is new,
#      the version isn't lower than the current one, the GitHub CLI (gh) is signed in;
#   2. sets mod_version in gradle.properties, so the jar and the game both show it;
#   3. runs the game tests; any failure stops the release;
#   4. builds the jar;
#   5. commits the version, tags it v<version>-mc<minecraft version>, pushes both;
#   6. makes the GitHub release with the jar as qbotics-<version>-mc<minecraft version>.jar
#      and the commits since the last release listed as its changes.
#
# Needs the GitHub CLI once: winget install GitHub.cli, then: gh auth login
set -euo pipefail
cd "$(dirname "$0")"

die() { echo "release: $*" >&2; exit 1; }

VERSION=""
NOTES_FILE=""
DRY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1 ;;
        --notes) shift; NOTES_FILE="${1:-}"; [ -f "$NOTES_FILE" ] || die "no notes file: $NOTES_FILE" ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        -*) die "unknown option $1 (see ./release.sh --help)" ;;
        *) [ -z "$VERSION" ] || die "give one version"; VERSION="$1" ;;
    esac
    shift
done
[ -n "$VERSION" ] || die "give the version, e.g. ./release.sh 0.2.0 (see --help)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must look like 1.2.3, not $VERSION"

prop() { grep -E "^$1=" gradle.properties | head -1 | cut -d= -f2- | tr -d '\r'; }
MC=$(prop minecraft_version)
NEO=$(prop neo_version)
CURRENT=$(prop mod_version)
TAG="v$VERSION-mc$MC"
ASSET="qbotics-$VERSION-mc$MC.jar"
OUT=neoforge/build/release
TITLE="Qbotics $VERSION for Minecraft $MC"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot}"

# ---- 1. checks ----
GH=$(command -v gh || true)
[ -n "$GH" ] || [ ! -x "/c/Program Files/GitHub CLI/gh.exe" ] || GH="/c/Program Files/GitHub CLI/gh.exe"

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "not on main"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "there are uncommitted changes; commit them first"
git fetch -q origin main
[ "$(git rev-list --count HEAD..origin/main)" = 0 ] || die "GitHub's main has commits this one doesn't; pull first"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"
[ -z "$(git ls-remote --tags origin "refs/tags/$TAG")" ] || die "tag $TAG already exists on GitHub"
[ "$(printf '%s\n%s\n' "$CURRENT" "$VERSION" | sort -V | head -1)" = "$CURRENT" ] \
    || die "version $VERSION is lower than the current $CURRENT"
if [ $DRY = 0 ]; then
    [ -n "$GH" ] || die "the GitHub CLI isn't installed: winget install GitHub.cli, then gh auth login"
    "$GH" auth status >/dev/null 2>&1 || die "the GitHub CLI isn't signed in: gh auth login"
elif [ -z "$GH" ]; then
    echo "release: (dry run) note: the GitHub CLI isn't installed; a real release needs it"
fi

echo "Releasing $TITLE (NeoForge $NEO) as tag $TAG"

# ---- 2. version ----
CHANGED=0
restore() { if [ $CHANGED = 1 ]; then git checkout -q -- gradle.properties; fi; }
trap restore EXIT
if [ "$CURRENT" != "$VERSION" ]; then
    sed -i "s/^mod_version=.*/mod_version=$VERSION/" gradle.properties
    CHANGED=1
fi
mkdir -p "$OUT"

# ---- 3. tests ----
echo "Running the game tests (log: $OUT/gametests.log)..."
./gradlew :neoforge:runGameTestServer -q > "$OUT/gametests.log" 2>&1 || true
if grep -qE "All [0-9]+ required tests passed" "$OUT/gametests.log"; then
    grep -oE "All [0-9]+ required tests passed" "$OUT/gametests.log" | tail -1
else
    grep -E "failed at|required tests failed" "$OUT/gametests.log" | head -20 >&2 || true
    die "the game tests didn't all pass; nothing was released"
fi

# ---- 4. build ----
echo "Building the jar..."
./gradlew :neoforge:build -x test -q > "$OUT/build.log" 2>&1 || { tail -20 "$OUT/build.log" >&2; die "the build failed"; }
JAR="neoforge/build/libs/qbotics-$VERSION.jar"
[ -f "$JAR" ] || die "the build made no $JAR"
cp "$JAR" "$OUT/$ASSET"

# The release text: the given notes, then the commits since the last release.
PREVIOUS=$(git describe --tags --abbrev=0 --match 'v*-mc*' 2>/dev/null || true)
{
    if [ -n "$NOTES_FILE" ]; then cat "$NOTES_FILE"; echo; fi
    echo "For Minecraft $MC on NeoForge $NEO. Download **$ASSET** below and put it in your \`mods\` folder."
    echo
    if [ -n "$PREVIOUS" ]; then echo "## Changes since $PREVIOUS"; else echo "## Changes"; fi
    echo
    git log --no-merges --format='- %s' ${PREVIOUS:+"$PREVIOUS"..HEAD}
} > "$OUT/notes.md"

if [ $DRY = 1 ]; then
    # The jar named for a version that wasn't released isn't left beside the real ones.
    [ $CHANGED = 0 ] || rm -f "$JAR"
    echo
    echo "Dry run: everything passed. A real run would now commit the version, tag $TAG, push,"
    echo "and publish \"$TITLE\" with $OUT/$ASSET. Its text is in $OUT/notes.md."
    exit 0
fi

# ---- 5. commit, tag, push ----
if [ $CHANGED = 1 ]; then
    MESSAGE="Release $VERSION for Minecraft $MC"
    [ -z "${RELEASE_COMMIT_TRAILER:-}" ] || MESSAGE="$MESSAGE"$'\n\n'"$RELEASE_COMMIT_TRAILER"
    git commit -q -m "$MESSAGE" -- gradle.properties
    CHANGED=0
fi
git tag -a "$TAG" -m "$TITLE (NeoForge $NEO)"
git push -q --atomic origin main "refs/tags/$TAG"

# ---- 6. GitHub release ----
"$GH" release create "$TAG" "$OUT/$ASSET" --title "$TITLE" --notes-file "$OUT/notes.md" --verify-tag
echo "Released: $TITLE"
