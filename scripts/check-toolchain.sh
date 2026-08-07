#!/usr/bin/env bash
#
# check-toolchain.sh (ADFA-4907)
#
# Assert that every Gradle module in this repo declares the one standard
# toolchain. Pure text inspection -- no Gradle run, no JDK, no network, so it
# is fast enough to gate every pull request.
#
# WHY THESE NUMBERS: they are not "latest". They track the toolchain that Code
# On The Go itself ships on-device, because these plugins are meant to be built
# *with* Code On The Go (ADFA-4693). Verified against CoGo build C-d-0727-1613:
#
#   * android-36 is the ONLY installed platform in CoGo's bundled SDK
#   * AGP 8.11.0 is the ONLY AGP in CoGo's Gradle caches
#   * Gradle 8.14.3 is CoGo's Gradle
#   * CoGo pins no Kotlin version (it fetches per project); 2.3.0 is our standard
#   * Java 17 was already uniform across every plugin
#
# Bumping any value below therefore means re-verifying against a real CoGo
# build first -- not just picking something newer.
#
# Usage:
#   scripts/check-toolchain.sh           # check; exit 1 and print a report on drift
#   scripts/check-toolchain.sh --list    # print what every module declares, exit 0
#
set -euo pipefail

# ---------------------------------------------------------------------------
# The standard
# ---------------------------------------------------------------------------
EXPECTED_COMPILE_SDK="36"
EXPECTED_TARGET_SDK="36"
EXPECTED_AGP="8.11.0"
EXPECTED_KOTLIN="2.3.0"
EXPECTED_GRADLE="8.14.3"
EXPECTED_GRADLE_DIST="bin"        # gradle-<ver>-bin.zip, not -all.zip
EXPECTED_JAVA="VERSION_17"
EXPECTED_JVM_TARGET="JVM_17"

# minSdk is deliberately NOT checked. It legitimately varies by what a plugin
# needs (21 for the template installers, 26 for most, 28 for Beepy and
# sketch-to-ui, 33 for the AI plugins which rely on API-33 runtime behavior).
# Do not "standardize" it here.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LIST_ONLY=0
[ "${1:-}" = "--list" ] && LIST_ONLY=1

failures=()
fail() { failures+=("$1"); }

# Files under src/main/assets/ are shipped project *templates* -- skeletons that
# Code On The Go stamps out for the user's own app. They are not our builds and
# must not be held to our toolchain.
find_gradle() {
  find . -name "$1" \
    -not -path './.git/*' \
    -not -path '*/build/*' \
    -not -path '*/src/main/assets/*' \
    -print0
}

# first_value <file> <extended-regex-with-one-capture-group>
# Prints "<lineno>:<captured>" for the first matching line, or nothing.
#
# Uses bash's own =~ rather than grep|sed: interpolating these regexes into a
# sed expression breaks on BSD/macOS sed (the escaped "\:" in distributionUrl)
# and on greedy ".*" before the capture group. BASH_REMATCH has neither problem.
#
# Always succeeds: "key absent" is a normal answer, not an error, and under
# `set -e` a bare failing grep here would abort the whole script silently.
first_value() {
  local file="$1" re="$2" ln=0 line
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    if [[ $line =~ $re ]]; then
      printf '%s:%s\n' "$ln" "${BASH_REMATCH[1]}"
      return 0
    fi
  done < "$file"
  return 0
}

# ---------------------------------------------------------------------------
# build.gradle.kts -- SDK levels, Java level, Kotlin jvmTarget, stdlib pin
# ---------------------------------------------------------------------------
while IFS= read -r -d '' f; do
  mod="${f#./}"; mod="${mod%/build.gradle.kts}"

  # --- compileSdk (every Android module has one) ---
  hit="$(first_value "$f" '^[[:space:]]*compileSdk[[:space:]]*=[[:space:]]*([0-9]+)')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$LIST_ONLY" = 1 ] && echo "$mod compileSdk=$val"
    [ "$val" != "$EXPECTED_COMPILE_SDK" ] && \
      fail "$f:$ln  compileSdk = $val   (expected $EXPECTED_COMPILE_SDK)"
  fi

  # --- targetSdk (application modules only; library modules have none) ---
  hit="$(first_value "$f" '^[[:space:]]*targetSdk[[:space:]]*=[[:space:]]*([0-9]+)')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$LIST_ONLY" = 1 ] && echo "$mod targetSdk=$val"
    [ "$val" != "$EXPECTED_TARGET_SDK" ] && \
      fail "$f:$ln  targetSdk = $val   (expected $EXPECTED_TARGET_SDK)"
  elif grep -q 'com\.android\.application' "$f"; then
    fail "$f  declares com.android.application but no targetSdk"
  fi

  # --- Java source/target compatibility ---
  while IFS= read -r bad; do
    fail "$f:${bad%%:*}  ${bad#*:}   (expected JavaVersion.$EXPECTED_JAVA)"
  done < <(grep -nE '^[[:space:]]*(source|target)Compatibility[[:space:]]*=' "$f" \
             | grep -v "JavaVersion\.$EXPECTED_JAVA" | sed 's/[[:space:]]*$//')

  # --- Kotlin jvmTarget ---
  # Kotlin 2.3.0 made the old string DSL (kotlinOptions.jvmTarget = "17") a hard
  # error. That exact pattern broke template-manager during the ADFA-4907 bump,
  # so reject it outright rather than waiting for a build to fail.
  while IFS= read -r bad; do
    fail "$f:${bad%%:*}  deprecated string DSL '${bad#*:}' -- Kotlin ${EXPECTED_KOTLIN} rejects it; use kotlin { compilerOptions { jvmTarget.set(JvmTarget.$EXPECTED_JVM_TARGET) } }"
  done < <(grep -nE 'jvmTarget[[:space:]]*=[[:space:]]*"' "$f" | sed 's/[[:space:]]*$//')

  if grep -qE 'org\.jetbrains\.kotlin|kotlin\("' "$f"; then
    if ! grep -q "JvmTarget\.$EXPECTED_JVM_TARGET" "$f"; then
      fail "$f  applies a Kotlin plugin but never sets jvmTarget to JvmTarget.$EXPECTED_JVM_TARGET"
    fi
  fi

  # --- explicit kotlin-stdlib pin must match the Kotlin plugin ---
  # An explicit pin older than the Kotlin Gradle plugin is the "version skew"
  # the plugin-review rubric flags. Most modules correctly omit the line and
  # inherit the stdlib from the plugin; if you do pin it, pin it to the standard.
  while IFS= read -r bad; do
    fail "$f:${bad%%:*}  kotlin-stdlib pinned to ${bad#*:}   (expected $EXPECTED_KOTLIN, or drop the line and inherit it)"
  done < <(grep -nE 'kotlin-stdlib:[0-9]' "$f" \
             | sed -E "s/^([0-9]+):.*kotlin-stdlib:([0-9][^\"']*).*/\1:\2/" \
             | grep -v ":$EXPECTED_KOTLIN\$")
done < <(find_gradle build.gradle.kts)

# ---------------------------------------------------------------------------
# settings.gradle.kts -- AGP and Kotlin Gradle plugin on the buildscript classpath
# ---------------------------------------------------------------------------
while IFS= read -r -d '' f; do
  mod="${f#./}"; mod="${mod%/settings.gradle.kts}"

  hit="$(first_value "$f" 'com\.android\.tools\.build:gradle:([0-9][^"]*)')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$LIST_ONLY" = 1 ] && echo "$mod agp=$val"
    [ "$val" != "$EXPECTED_AGP" ] && \
      fail "$f:$ln  AGP $val   (expected $EXPECTED_AGP -- the only AGP in CoGo's caches)"
  fi

  hit="$(first_value "$f" 'kotlin-gradle-plugin:([0-9][^"]*)')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$LIST_ONLY" = 1 ] && echo "$mod kotlin=$val"
    [ "$val" != "$EXPECTED_KOTLIN" ] && \
      fail "$f:$ln  Kotlin $val   (expected $EXPECTED_KOTLIN)"
  fi

  hit="$(first_value "$f" 'compose-compiler-gradle-plugin:([0-9][^"]*)')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$val" != "$EXPECTED_KOTLIN" ] && \
      fail "$f:$ln  Compose compiler plugin $val   (must match Kotlin $EXPECTED_KOTLIN)"
  fi
done < <(find_gradle settings.gradle.kts)

# ---------------------------------------------------------------------------
# gradle-wrapper.properties -- Gradle version AND distribution flavor
# ---------------------------------------------------------------------------
while IFS= read -r -d '' f; do
  hit="$(first_value "$f" 'distributions/gradle-([0-9][^-]*)-')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$LIST_ONLY" = 1 ] && echo "${f#./} gradle=$val"
    [ "$val" != "$EXPECTED_GRADLE" ] && \
      fail "$f:$ln  Gradle $val   (expected $EXPECTED_GRADLE -- CoGo's Gradle)"
  fi
  hit="$(first_value "$f" 'gradle-[0-9][^-]*-(bin|all)\.zip')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$val" != "$EXPECTED_GRADLE_DIST" ] && \
      fail "$f:$ln  gradle-...-$val.zip   (expected -$EXPECTED_GRADLE_DIST.zip, matching the root wrapper)"
  fi
done < <(find_gradle gradle-wrapper.properties)

# ---------------------------------------------------------------------------
# libs.versions.toml -- catalogs must agree with the classpath they document
# ---------------------------------------------------------------------------
# Only three modules use a catalog, and two of them carry a hand-written
# "Must match the kotlin-gradle-plugin classpath pinned in settings.gradle.kts"
# comment. This automates that comment. A stale entry here is not inert: it is
# the number the next person greps for.
while IFS= read -r -d '' f; do
  hit="$(first_value "$f" '^agp[[:space:]]*=[[:space:]]*"([^"]+)"')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$val" != "$EXPECTED_AGP" ] && \
      fail "$f:$ln  catalog agp = \"$val\"   (expected $EXPECTED_AGP; disagrees with settings.gradle.kts)"
  fi
  hit="$(first_value "$f" '^kotlin[[:space:]]*=[[:space:]]*"([^"]+)"')"
  if [ -n "$hit" ]; then
    ln="${hit%%:*}"; val="${hit#*:}"
    [ "$val" != "$EXPECTED_KOTLIN" ] && \
      fail "$f:$ln  catalog kotlin = \"$val\"   (expected $EXPECTED_KOTLIN; disagrees with settings.gradle.kts)"
  fi
done < <(find_gradle libs.versions.toml)

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
[ "$LIST_ONLY" = 1 ] && exit 0

if [ ${#failures[@]} -eq 0 ]; then
  echo "toolchain OK - compileSdk/targetSdk $EXPECTED_COMPILE_SDK, AGP $EXPECTED_AGP, Kotlin $EXPECTED_KOTLIN, Gradle $EXPECTED_GRADLE-$EXPECTED_GRADLE_DIST, Java 17"
  exit 0
fi

{
  echo
  echo "Toolchain drift: ${#failures[@]} problem(s)."
  echo
  printf '  %s\n' "${failures[@]}"
  echo
  echo "The standard is defined at the top of scripts/check-toolchain.sh and tracks"
  echo "Code On The Go's own on-device toolchain (ADFA-4907). Fix the files above, or"
  echo "-- if Code On The Go itself has moved -- update the script's constants and say"
  echo "which CoGo build you verified against."
  echo
} >&2
exit 1
