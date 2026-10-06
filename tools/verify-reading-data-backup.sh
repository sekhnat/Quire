#!/usr/bin/env zsh
# Verifies the reading-data backup story on a disposable emulator (docs/user-data-backup.md).
# Run from the repo root.
#
# Usage:
#   tools/verify-reading-data-backup.sh <phase> <serial> [apk]
# Phases:
#   seed           install the app, grant all-files access, push EPUB fixtures and a crafted user-data.json
#   onboard        drive onboarding with uiautomator taps (folders -> scan -> library), which triggers the import
#   verify-import  assert the seeded user data landed in the database and the snapshot was rewritten
#   backup         select the local test transport and run bmgr backupnow
#   inspect        (rooted image) list the transport payload; assert <1 MB and no database/covers/EPUB blobs
#   reinstall      uninstall and reinstall the same APK so Android restores the backup set
#   verify-restore relaunch, onboard again, and assert every highlight came back after the scan
#   all            seed onboard verify-import backup inspect reinstall verify-restore
#
# Guards: the default user device (emulator-5554) is always refused. Destructive phases (seed/all/reinstall)
# additionally require the device's AVD to be named quire-gate*, unless ALLOW_DESTRUCTIVE=1.

set -euo pipefail

if [[ $# -lt 2 ]]; then sed -n 2,24p "$0"; exit 2; fi
phase="$1"
serial="$2"
apk="${3:-app/build/outputs/apk/debug/app-debug.apk}"
pkg="com.quire.reader"
fixture_root="/sdcard/Books"
tmp="/tmp/quire-gate"
fixture_dir="${FIXTURE_DIR:-$tmp/fixtures}"

source "${HOME}/.config/android/env.zsh"

[[ "$serial" == emulator-5554 ]] && { echo "REFUSED: $serial is the default user device"; exit 2; }
avd_name=$(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
if [[ "$phase" == (seed|all|reinstall) && "$avd_name" != quire-gate* ]]; then
  [[ "${ALLOW_DESTRUCTIVE:-0}" == 1 ]] || { echo "REFUSED: $phase on $serial (AVD '$avd_name') needs ALLOW_DESTRUCTIVE=1"; exit 2; }
fi

adb_() { adb -s "$serial" "$@"; }
run_as() { adb_ shell run-as "$pkg" "$@"; }

wait_boot() {
  for i in $(seq 1 180); do
    [[ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && { echo "booted after ${i}s"; return 0; }
    sleep 1
  done
  echo "device never booted"; exit 1
}

# Taps the first UI node whose text matches the regex, polling until it appears.
tap_text() {
  local pattern="$1" tries="${2:-45}" bounds=""
  for i in $(seq 1 $tries); do
    adb_ shell uiautomator dump /sdcard/quire-ui-dump.xml >/dev/null 2>&1 || { sleep 1; continue; }
    adb_ pull /sdcard/quire-ui-dump.xml "$tmp/ui.xml" >/dev/null 2>&1 || { sleep 1; continue; }
    bounds=$(python3 - "$pattern" "$tmp/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pattern = sys.argv[1]
tree = ET.parse(sys.argv[2])
for node in tree.iter("node"):
    if re.search(pattern, node.get("text", "") + " " + node.get("content-desc", "")):
        b = [int(v) for v in re.findall(r"-?\d+", node.get("bounds"))]
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        break
PY
    )
    if [[ -n "$bounds" ]]; then
      adb_ shell input tap $bounds
      echo "  tapped /$pattern/ at ($bounds), attempt $i"
      return 0
    fi
    sleep 1
  done
  echo "never found /$pattern/ — last dump at $tmp/ui.xml"; exit 1
}

# Pulls a file from the app's data directory, binary-safe, WAL included when asked.
pull_data() { # pull_data <app-relative path> <local path>
  adb_ exec-out run-as "$pkg" cat "$1" > "$2" 2>/dev/null
}

pull_database() { # pull_database <local path prefix>: fetches quire.db alongside its WAL
  pull_data "databases/quire.db" "$1"
  adb_ exec-out run-as "$pkg" cat "databases/quire.db-wal" > "$1-wal" 2>/dev/null || true
  adb_ exec-out run-as "$pkg" cat "databases/quire.db-shm" > "$1-shm" 2>/dev/null || true
}

wait_for_file() { # wait_for_file <app-relative path> <label>
  for i in $(seq 1 90); do
    if run_as ls "$1" >/dev/null 2>&1; then echo "  $2 (attempt $i)"; return 0; fi
    sleep 1
  done
  echo "never saw $1"; exit 1
}

# Small generated EPUBs keep the gate reproducible without shipping copyrighted books.
generate_fixtures() {
  mkdir -p "$fixture_dir"
  python3 - "$fixture_dir" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED
from xml.sax.saxutils import escape
import sys
root = Path(sys.argv[1])
for name, title, author in [("MWM", "MWM", "Mercury Writers"), ("ORV", "Omniscient Reader's Viewpoint — 유서인", "Sing Shong"), ("TASH", "Täsh — a ünïcode title ✦", "A. Ümlaut")]:
    with ZipFile(root / (name + ".epub"), "w") as z:
        z.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
        z.writestr("META-INF/container.xml", '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("content.opf", f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">quire-gate-{name}</dc:identifier><dc:title>{escape(title)}</dc:title><dc:creator>{escape(author)}</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-06T00:00:00Z</meta></metadata><manifest><item id="c" href="chapter1.xhtml" media-type="application/xhtml+xml"/><item id="n" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="c"/></spine></package>')
        z.writestr("nav.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter1.xhtml">Chapter One</a></li></ol></nav></body></html>')
        paragraphs = ''.join(f'<p>The {name} heron stood beside the river. This is passage {i} with Unicode 素晴らしい.</p>' for i in range(400))
        z.writestr("chapter1.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head><body>' + paragraphs + '</body></html>')
PY
}

fixture_snapshot() {
  # The snapshot the app itself would have backed up: identities are computed exactly the way
  # BookIdentity does (sha1 of the last 64 KiB), so the scan must find these books again.
  mkdir -p "$tmp"
  python3 - "$fixture_dir" > "$tmp/user-data.json" <<PY
import hashlib, json, os, sys, time
books = []
fixtures = [
    ("MWM.epub", "MWM", "Mercury Writers"),
    ("ORV.epub", "Omniscient Reader's Viewpoint — 유서인", "Sing Shong"),
    ("TASH.epub", "Täsh — a ünïcode title ✦", "A. Ümlaut"),
]
for index, (name, title, author) in enumerate(fixtures):
    path = os.path.join(sys.argv[1], name)
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        f.seek(max(0, size - 65536)); tail = f.read()
    fp = f"{size}:" + hashlib.sha1(tail).hexdigest()
    locator = {"href": "chapter1.xhtml", "locations": {"progression": 0.3}}
    books.append({
        "identityKey": "fingerprint:" + fp, "entryKey": f"fixture{index:016x}",
        "identity": {"calibreUuid": None, "epubUid": None, "fingerprint": fp},
        "title": title, "author": author, "addedAt": 1700000000000, "missingSince": None,
        "state": {"locatorJson": json.dumps(locator), "progress": 0.42, "status": "reading",
                  "lastOpenedAt": 1700000001000, "finishedAt": 0, "userRating": 4, "prefsJson": "{\"fontSize\":22}"},
        "userTags": ["favourite ✦", "gate-a"],
        "bookmarks": [{"locatorJson": json.dumps(locator), "label": "Chapter of " + name, "progress": 0.1, "createdAt": 1700000002000}],
        "highlights": [
            {"locatorJson": json.dumps(dict(locator, title=f"Chapter {index} — Découvrez ✦")), "text": f"híghlight {index} — 素晴らしい", "note": f"note {index} ✦", "progress": 0.3, "createdAt": 1700000003000 + index, "chapter": None},
            {"locatorJson": json.dumps(locator), "text": f"highlight {index} without note", "progress": 0.5, "createdAt": 1700000004000 + index, "chapter": None},
        ],
    })
# A book the library will not find again: no identity keys at all, so it must survive as a tombstone.
books.append({
    "identityKey": "entry:absent0123456789", "entryKey": "absent0123456789",
    "identity": {"calibreUuid": None, "epubUid": None, "fingerprint": None},
    "title": "A Book Now Absent", "author": "Ghost Writer", "addedAt": 1700000005000, "missingSince": None,
    "state": {"locatorJson": None, "progress": 0.1, "status": "reading", "lastOpenedAt": 1700000006000, "finishedAt": 0, "userRating": None, "prefsJson": None},
    "userTags": ["absent"], "bookmarks": [],
    "highlights": [{"locatorJson": "{\"href\":\"gone.xhtml\"}", "text": "highlight of an absent book", "note": "kept as a tombstone", "progress": 0.2, "createdAt": 1700000007000, "chapter": None}],
})
snapshot = {
    "schemaVersion": 1, "exportedAt": int(time.time() * 1000),
    "settings": {"useCalibre": True, "watchNewBooks": True, "indexingEnabled": True,
                 "indexChargingOnly": False, "brightness": 64,
                 "readerDefaults": {"theme": "Night", "font": 0, "fontSize": 21, "lineHeight": 1.6, "margin": 26, "align": "Justify", "mode": "Paged"}},
    "books": books,
}
print(json.dumps(snapshot))
PY
}

verify_data() {
  local db="$1"
  pull_database "$db"
  [[ -s "$db" ]] || { echo "the database did not come off the device"; exit 1; }
  python3 - "$db" "$tmp/user-data.json" <<'PY'
import json, sqlite3, sys
c = sqlite3.connect(sys.argv[1])
def one(sql): return c.execute(sql).fetchone()[0]
counts = dict(
    books=one("select count(*) from book"),
    highlights=one("select count(*) from highlight"),
    bookmarks=one("select count(*) from bookmark"),
    userTags=one("select count(*) from book_tag where origin='user'"),
    missing=one("select count(*) from book where missingSince is not null"),
    reading42=one("select count(*) from book_state where status='reading' and progress > 0.4"),
    rated=one("select count(*) from book_state where userRating=4"),
    unicodeNotes=one("select count(*) from highlight where note like 'note % ✦'"),
)
print("  database:", " ".join(f"{k}={v}" for k, v in counts.items()))
expected = dict(books=4, highlights=7, bookmarks=3, userTags=7, missing=1, reading42=3, rated=3, unicodeNotes=3)
failures = [f"{k}={counts[k]}, expected {v}" for k, v in expected.items() if counts[k] != v]
if failures:
    print("VERIFY FAILED:", "; ".join(failures)); sys.exit(1)
# Counts alone could hide swapped or corrupted annotations: compare every seeded record as well.
def canonical(raw):
    value = json.loads(raw)
    value.pop('title', None)
    return json.dumps(value, sort_keys=True)
for entry in json.load(open(sys.argv[2]))['books']:
    fp = entry['identity']['fingerprint']
    if fp:
        matches = c.execute('SELECT id FROM book WHERE fingerprint = ?', (fp,)).fetchall()
    else:
        matches = c.execute('SELECT id FROM book WHERE path = ?', ('/Quire missing books/' + entry['entryKey'] + '.epub',)).fetchall()
    assert len(matches) == 1, (entry['title'], matches)
    book_id = matches[0][0]
    state = c.execute('SELECT locatorJson, progress, status, lastOpenedAt, userRating, prefsJson FROM book_state WHERE bookId = ?', (book_id,)).fetchone()
    expected_state = entry['state']
    assert abs(state[1] - expected_state['progress']) < 1e-6, state
    assert state[2:5] == (expected_state['status'], expected_state['lastOpenedAt'], expected_state['userRating']), state
    if expected_state['locatorJson']:
        assert canonical(state[0]) == canonical(expected_state['locatorJson'])
    if expected_state['prefsJson']:
        assert json.loads(state[5]) == json.loads(expected_state['prefsJson'])
    tags = {r[0] for r in c.execute("SELECT tag FROM book_tag WHERE bookId = ? AND origin = 'user'", (book_id,))}
    assert tags == set(entry['userTags']), tags
    highlights = c.execute('SELECT locatorJson, text, note, progress, createdAt FROM highlight WHERE bookId = ?', (book_id,)).fetchall()
    assert len(highlights) == len(entry['highlights'])
    for h in entry['highlights']:
        assert any(canonical(r[0]) == canonical(h['locatorJson']) and r[1] == h['text'] and r[2] == h.get('note') and abs(r[3] - h['progress']) < 1e-6 and r[4] == h['createdAt'] for r in highlights), h
    bookmarks = c.execute('SELECT locatorJson, label, progress, createdAt FROM bookmark WHERE bookId = ?', (book_id,)).fetchall()
    assert len(bookmarks) == len(entry['bookmarks'])
    for b in entry['bookmarks']:
        assert any(canonical(r[0]) == canonical(b['locatorJson']) and r[1] == b['label'] and abs(r[2] - b['progress']) < 1e-6 and r[3] == b['createdAt'] for r in bookmarks), b
print("  OK: every seeded highlight, bookmark, tag, position and rating is in the database")
PY
  pull_data "files/backup/user-data.json" "$tmp/snapshot-seen.json"
  python3 - "$tmp/snapshot-seen.json" <<'PY'
import json, sys
snapshot = json.load(open(sys.argv[1]))
assert snapshot["settings"]["brightness"] == 64, snapshot["settings"]
tombstones = [b for b in snapshot["books"] if b["entryKey"] == "absent0123456789"]
assert len(tombstones) == 1 and tombstones[0]["missingSince"] is not None, "the tombstone should be missing"
assert any(b["identityKey"].startswith("fingerprint:") for b in snapshot["books"]), "fixtures keep their identity keys"
print("  OK: the rewritten snapshot holds the imported data and the tombstone")
PY
}

case "$phase" in
  seed)
    [[ -f "$apk" ]] || { echo "no APK at $apk (build first)"; exit 1; }
    # The install must run without root: with adbd rooted, installd leaves the data dir root-owned
    # and the app cannot read or write anything.
    adb_ uninstall "$pkg" >/dev/null 2>&1 || true
    [[ -n "${FIXTURE_DIR:-}" ]] || generate_fixtures
    adb_ unroot >/dev/null 2>&1; sleep 2
    adb_ install -r -t "$apk" | sed 's/^/  /'
    adb_ shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow
    adb_ shell mkdir -p "$fixture_root"
    for epub in MWM.epub ORV.epub TASH.epub; do
      [[ -f "$fixture_dir/$epub" ]] || { echo "missing fixture $fixture_dir/$epub"; exit 1; }
      adb_ push "$fixture_dir/$epub" "$fixture_root/$epub" >/dev/null && echo "  pushed $epub"
    done
    fixture_snapshot
    adb_ push "$tmp/user-data.json" /sdcard/quire-user-data.json >/dev/null
    # run-as cannot touch FUSE storage (different SELinux domain), so root places the file and
    # hands it back to the app's uid. The image must be rootable (google_apis, not Play).
    adb_ root >/dev/null; sleep 3; wait_boot >/dev/null
    adb_ shell "mkdir -p /data/data/$pkg/files/backup"
    adb_ shell "cp /sdcard/quire-user-data.json /data/data/$pkg/files/backup/user-data.json"
    local uid
    uid=$(adb_ shell dumpsys package "$pkg" 2>/dev/null | grep -o "uid=[0-9]*" | head -1 | cut -d= -f2 | tr -d '\r')
    adb_ shell "chown -R $uid:$uid /data/data/$pkg/files"
    adb_ shell "chmod 700 /data/data/$pkg/files/backup && chmod 600 /data/data/$pkg/files/backup/user-data.json"
    adb_ shell "restorecon -R /data/data/$pkg/files/backup"
    adb_ shell "ls -l /data/data/$pkg/files/backup/user-data.json" | sed 's/^/  /'
    adb_ shell rm -f /sdcard/quire-user-data.json
    echo "seeded: 3 fixture books + 1 absent entry + settings"
    ;;

  onboard)
    adb_ shell am start -W -n "$pkg/.MainActivity" | grep -E "Status" || true
    sleep 3
    tap_text "Choose folders"
    tap_text "Scan .*(folder|folders)"
    tap_text "Open library|Start reading now"
    echo "onboarded; the scan and the automatic import are running"
    ;;

  verify-import)
    wait_for_file "databases/quire.db" "the database exists"
    sleep 6   # let the scan finish and the import run
    verify_data "$tmp/verify.db"
    ;;

  backup)
    adb_ shell bmgr enable true
    transport=$(adb_ shell bmgr list transports | tr -d '\r' | awk '/com.android.localtransport/ { print $NF; exit }')
    [[ -n "$transport" ]] || { echo "no local transport; transports:"; adb_ shell bmgr list transports; exit 1; }
    echo "  transport: $transport"
    adb_ shell bmgr transport "$transport"
    adb_ shell bmgr list transports | tr -d '\r' | grep -F "* $transport" >/dev/null || { echo "selecting transport failed"; exit 1; }
    # Plaintext on purpose: Gate A inspects the payload's file list and size.
    adb_ shell settings put secure backup_local_transport_parameters 'is_encrypted=false'
    result=$(adb_ shell bmgr backupnow "$pkg" | tr -d '\r')
    echo "  $result"
    echo "$result" | grep -q "result: Success" || { echo "BACKUP FAILED"; exit 1; }
    echo "BACKUP OK"
    ;;

  inspect)
    adb_ root >/dev/null; sleep 3; wait_boot >/dev/null
    # The local transport stores full-data backups under its own data directory: one blob per
    # package (files/1/_full/<package>), plus per-key delta blobs. /data/backup holds only @pm@ state.
    adb_ shell "find /data/user/0/com.android.localtransport/files -type f 2>/dev/null" > "$tmp/payload-files.txt" || true
    payload=$(grep -E "$pkg" "$tmp/payload-files.txt" | head -50)
    [[ -n "$payload" ]] || { echo "no package payload found; files seen:"; head -30 "$tmp/payload-files.txt"; exit 1; }
    echo "  package payload files:"
    echo "$payload" | sed 's/^/    /'
    total=0
    for f in $(echo "$payload"); do
      size=$(adb_ shell stat -c %s "$f" 2>/dev/null | tr -d '\r')
      total=$(( total + ${size:-0} ))
    done
    echo "  payload bytes (package-related files): $total"
    [[ "$total" -lt 1048576 ]] || { echo "GATE FAILED: payload is $total bytes (>= 1 MiB)"; exit 1; }
    if echo "$payload" | grep -E "quire\.db|quire-index|\.epub|covers/" | grep -v "user-data"; then
      echo "GATE FAILED: a database, cover or EPUB blob travelled"; exit 1
    fi
    # The full blob itself must carry the snapshot and the settings, and none of the databases.
    full_blob=$(echo "$payload" | grep "_full/" | head -1)
    adb_ shell "cat '$full_blob'" > "$tmp/payload.bin" 2>/dev/null
    python3 - "$tmp/payload.bin" <<'PY'
import re, sys
blob = open(sys.argv[1], "rb").read()
text = blob.decode("utf-8", errors="replace")
assert "backup/user-data.json" in text, "the snapshot must travel"
assert "datastore/settings.preferences_pb" in text, "the settings must travel"
# Only the transported file KEYS count: user data may legitimately mention "MWM.epub" in a note.
keys = set(re.findall(r"apps/com\.quire\.reader/(?:f|db|r|d)/[A-Za-z0-9._/\-]+", text))
for key in sorted(keys):
    print("    key:", key)
assert not any(".epub" in k or "covers/" in k or "quire.db" in k or "quire-index" in k for k in keys), \
    "a database, cover or EPUB file key travelled"
print("  OK: the payload carries exactly the snapshot and the settings file")
PY
    echo "INSPECT OK: payload < 1 MiB, no database, cover or EPUB blob travelled"
    ;;

  reinstall)
    adb_ uninstall "$pkg" >/dev/null && echo "  uninstalled"
    adb_ shell bmgr enable true
    adb_ install -r -t "$apk" | sed 's/^/  /'
    echo "  reinstalled; Android restores the backup set at install"
    sleep 8
    adb_ shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow
    ;;

  verify-restore)
    wait_for_file "files/backup/user-data.json" "the restored snapshot is on the device"
    adb_ shell am start -W -n "$pkg/.MainActivity" >/dev/null || true
    sleep 3
    tap_text "Choose folders"
    tap_text "Scan .*(folder|folders)"
    tap_text "Open library|Start reading now"
    sleep 10
    verify_data "$tmp/verify-restore.db"
    ;;

  all)
    for p in seed onboard verify-import backup inspect reinstall verify-restore; do
      echo "══ $p ══"
      "$0" "$p" "$serial" "$apk"
    done
    echo "GATE COMPLETE: the backup is small, index-free, and restores every highlight after a scan"
    ;;

  *) echo "unknown phase: $phase"; exit 2 ;;
esac
