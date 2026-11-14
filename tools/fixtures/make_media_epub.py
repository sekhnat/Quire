#!/usr/bin/env python3
"""Writes media.epub, the fixture for zooming images and tables (spec feature 8).

Usage: python3 -I tools/fixtures/make_media_epub.py app/src/test/resources/epub/media.epub

One chapter with: a large raster image with small text-like detail, an SVG image file, an inline SVG drawing, a wide
table (8 columns), and a narrow table (2 columns, which gets no expand button).
"""
import struct
import sys
import zipfile
import zlib


def png(width, height):
    """A grey grid with a dark diagonal, so zooming and panning are visible."""
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            v = 70 if (x % 100 < 3 or y % 100 < 3) else 220
            if abs(x * height - y * width) < width * 3:
                v = 20
            row += bytes([v, v, v])
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


SVG_FILE = """<?xml version="1.0" encoding="utf-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200" width="400" height="200">
  <rect x="0" y="0" width="400" height="200" fill="#dde"/>
  <circle cx="100" cy="100" r="80" fill="#447"/>
  <text x="200" y="110" font-size="10" fill="#222">tiny SVG label, zoom to read</text>
</svg>
"""

INLINE_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 120" width="300" height="120" role="img">
  <title>An inline diagram</title>
  <rect x="5" y="5" width="290" height="110" fill="none" stroke="#888" stroke-width="2"/>
  <line x1="10" y1="110" x2="290" y2="10" stroke="#c44" stroke-width="3"/>
  <text x="20" y="30" font-size="8">inline svg, small print</text>
</svg>"""

WIDE_TABLE = "<table><caption>Table 1: eight columns of figures</caption>" + "<tr>" + "".join(
    f"<th>Column {c}</th>" for c in range(1, 9)) + "</tr>" + "".join(
    "<tr>" + "".join(f"<td>{r * c * 137 % 10000}</td>" for c in range(1, 9)) + "</tr>" for r in range(1, 13)) + "</table>"

NARROW_TABLE = "<table><tr><th>Name</th><th>Value</th></tr><tr><td>a</td><td>1</td></tr><tr><td>b</td><td>2</td></tr></table>"

CHAPTER = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="en" xml:lang="en">
<head><title>Media</title></head>
<body>
<h1>Media</h1>
<p>A large raster image (long-press it):</p>
<p><img src="grid.png" alt="A grid with a diagonal line" style="width:100%"/></p>
<p>An SVG image file:</p>
<p><img src="drawing.svg" alt="A circle and a label"/></p>
<p>An inline SVG drawing:</p>
{INLINE_SVG}
<p>A wide table:</p>
{WIDE_TABLE}
<p>A narrow table (no expand button):</p>
{NARROW_TABLE}
<p>The end.</p>
</body>
</html>
"""

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="en">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:5b0c1f8e-quire-fixture-media</dc:identifier>
    <dc:title>Media Fixture</dc:title>
    <dc:creator>Quire Tests</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-11-14T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch" href="media.xhtml" media-type="application/xhtml+xml" properties="svg"/>
    <item id="grid" href="grid.png" media-type="image/png"/>
    <item id="drawing" href="drawing.svg" media-type="image/svg+xml"/>
  </manifest>
  <spine><itemref idref="ch"/></spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Nav</title></head>
<body><nav epub:type="toc"><ol><li><a href="media.xhtml">Media</a></li></ol></nav></body></html>
"""

CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""


def main(out):
    with zipfile.ZipFile(out, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)

        def add(name, data):
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)

        add("META-INF/container.xml", CONTAINER)
        add("OEBPS/content.opf", OPF)
        add("OEBPS/nav.xhtml", NAV)
        add("OEBPS/media.xhtml", CHAPTER)
        add("OEBPS/grid.png", png(1600, 1000))
        add("OEBPS/drawing.svg", SVG_FILE)


if __name__ == "__main__":
    main(sys.argv[1])
