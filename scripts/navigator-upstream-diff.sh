#!/usr/bin/env bash
# Compare Quire's copy of the Readium EPUB navigator (app/src/main/java/com/quire/reader/navigator) with upstream.
#
#   scripts/navigator-upstream-diff.sh local VERSION [--full]
#       What Quire changed: each copied file against Readium VERSION's own source (the official sources jar on Maven
#       Central, which is what the copy was taken from). Lists files that differ only by the vendored header, files
#       Quire changed (with a count of changed lines), and Quire-only files. --full prints the unified diffs.
#
#   scripts/navigator-upstream-diff.sh upstream FROM TO [--full]
#       What to port on an upgrade: Readium's own changes FROM -> TO, limited to the files Quire copied, plus the
#       changes to the navigator assets (readium-css, the JS bundles) that the AAR serves to the copied code, and a
#       check that the JS bundles call no `Android.*` bridge method the scroll frame adapter in HtmlInjector lacks.
#       --full prints the unified diffs of the copied files.
#
# Both modes compare with the package prefixes (org.readium.r2.navigator / com.quire.reader.navigator) folded
# together, and without the "Vendored from" header block, import lines and blank-line changes, so only code changes
# show (an import that points a type at the AAR instead of the copy is a contract question: see VENDORED.md).
# Needs bash, curl, unzip, perl and diff.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
nav="$root/app/src/main/java/com/quire/reader/navigator"
injector="$nav/epub/HtmlInjector.kt"
maven="https://repo1.maven.org/maven2/org/readium/kotlin-toolkit/readium-navigator"

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# fetch VERSION KIND -> directory with the sources jar (KIND=sources) or the AAR (KIND=aar) unpacked
fetch() {
  local version=$1 kind=$2 dir="$work/$1-$2"
  if [[ ! -d $dir ]]; then
    local file="$work/$version.$kind.zip" url
    if [[ $kind == sources ]]; then url="$maven/$version/readium-navigator-$version-sources.jar"
    else url="$maven/$version/readium-navigator-$version.aar"; fi
    curl -fsSL -o "$file" "$url" || { echo "cannot download $url" >&2; exit 1; }
    mkdir -p "$dir"
    unzip -q "$file" -d "$dir"
  fi
  echo "$dir"
}

# normalize FILE -> stdout: no vendored header block, one package prefix, a final newline
normalize() {
  perl -0pe '
    s{^//\n// Vendored from .*?modifications\.\n\n?}{}ms;
    s{^import [^\n]*\n}{}mg;
    s{\b(?:org\.readium\.r2\.navigator|com\.quire\.reader\.navigator)\b}{NAV}g;
    $_ .= "\n" unless /\n\z/;
  ' "$1"
}

# upstream_path SRC_ROOT REL -> the upstream file for a copied file, by path or else by its top-level declaration
upstream_path() {
  local src="$1/org/readium/r2/navigator" rel=$2
  if [[ -f $src/$rel ]]; then echo "$src/$rel"; return; fi
  local name; name=$(basename "${rel%.*}")
  grep -rlE "^(public |internal |private )?(abstract |open |data |sealed )?(class|interface|object) $name\b" "$src" 2>/dev/null | head -1 || true
}

changed_lines() { diff -u -B "$1" "$2" | grep -cE '^[+-]([^+-]|$)' || true; }

copied_files() { (cd "$nav" && find . -type f \( -name '*.kt' -o -name '*.java' \) | sed 's|^\./||' | sort); }

mode_local() {
  local version=$1 full=${2:-}
  local src; src=$(fetch "$version" sources)
  local same=() changed=() own=()
  while read -r rel; do
    local up; up=$(upstream_path "$src" "$rel")
    if [[ -z $up ]]; then own+=("$rel ($(wc -l < "$nav/$rel") lines)"); continue; fi
    normalize "$up" > "$work/a"; normalize "$nav/$rel" > "$work/b"
    local n; n=$(changed_lines "$work/a" "$work/b")
    local from=""; [[ $up != */org/readium/r2/navigator/$rel ]] && from=" (upstream ${up#"$src"/org/readium/r2/navigator/})"
    if [[ $n == 0 ]]; then same+=("$rel$from"); else changed+=("$(printf '%5d  %s%s' "$n" "$rel" "$from")"); fi
    if [[ -n $full && $n != 0 ]]; then
      diff -u -B --label "upstream/$rel" --label "quire/$rel" "$work/a" "$work/b" >> "$work/full" || true
    fi
  done < <(copied_files)

  echo "Quire's navigator against Readium $version"
  echo
  echo "Changed by Quire (changed lines, file):"
  printf '%s\n' "${changed[@]}" | sort -rn
  echo
  echo "Same as upstream apart from the vendored header (${#same[@]}):"
  printf '  %s\n' "${same[@]}"
  echo
  echo "Quire's own files, not in upstream (${#own[@]}):"
  printf '  %s\n' "${own[@]}"
  [[ -n $full && -f $work/full ]] && { echo; cat "$work/full"; }
  return 0
}

mode_upstream() {
  local from=$1 to=$2 full=${3:-}
  local a b; a=$(fetch "$from" sources); b=$(fetch "$to" sources)
  echo "Readium's changes $from -> $to in the files Quire copied"
  echo
  local any=""
  while read -r rel; do
    local pa pb; pa=$(upstream_path "$a" "$rel"); pb=$(upstream_path "$b" "$rel")
    [[ -z $pa && -z $pb ]] && continue
    if [[ -z $pb ]]; then echo "  removed upstream: $rel"; any=1; continue; fi
    if [[ -z $pa ]]; then echo "  new upstream: $rel"; any=1; continue; fi
    normalize "$pa" > "$work/a"; normalize "$pb" > "$work/b"
    local n; n=$(changed_lines "$work/a" "$work/b")
    if [[ $n != 0 ]]; then
      printf '%5d  %s\n' "$n" "$rel"; any=1
      [[ -n $full ]] && { diff -u -B --label "$from/$rel" --label "$to/$rel" "$work/a" "$work/b" >> "$work/full" || true; }
    fi
  done < <(copied_files)
  [[ -z $any ]] && echo "  (none)"

  echo
  echo "New upstream files in the packages Quire copied from (ported code may need them):"
  local dirs; dirs=$(copied_files | xargs -n1 dirname | sort -u)
  local new=""
  for d in $dirs; do
    local dir="$b/org/readium/r2/navigator/$d"; [[ $d == . ]] && dir="$b/org/readium/r2/navigator"
    for f in "$dir"/*.kt "$dir"/*.java; do
      [[ -f $f ]] || continue
      local rel=${f#"$b"/org/readium/r2/navigator/}
      [[ -f $a/org/readium/r2/navigator/$rel ]] || { echo "  $rel"; new=1; }
    done
  done
  [[ -z $new ]] && echo "  (none)"

  echo
  echo "Navigator assets the AAR serves ($from -> $to):"
  local aa ab; aa=$(fetch "$from" aar); ab=$(fetch "$to" aar)
  diff -rq "$aa/assets" "$ab/assets" | sed "s|$aa/assets/||; s|$ab/assets/||; s/^/  /" || true

  echo
  echo "JS bridge: Android.* methods the $to bundles call that the scroll frame adapter (HtmlInjector) lacks:"
  local facade; facade=$(grep -oE '^\s+[A-Za-z]+: function' "$injector" | sed -E 's/^\s+//; s/: function//' | sort -u)
  local missing=""
  for m in $(cat "$ab"/assets/readium/scripts/*.js | grep -oE 'Android\.[A-Za-z]+' | sed 's/^Android\.//' | sort -u); do
    grep -qx "$m" <<< "$facade" || { echo "  $m"; missing=1; }
  done
  [[ -z $missing ]] && echo "  (none)"
  [[ -n $full && -f $work/full ]] && { echo; cat "$work/full"; }
  return 0
}

case "${1:-}" in
  local) [[ $# -ge 2 ]] || usage; mode_local "$2" "${3:-}" ;;
  upstream) [[ $# -ge 3 ]] || usage; mode_upstream "$2" "$3" "${4:-}" ;;
  *) usage ;;
esac
