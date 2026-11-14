#!/usr/bin/env python3
"""Writes links.epub, the fixture for the reader's link checks (spec features 1 and 2).

Usage: python3 -I tools/fixtures/make_links_epub.py app/src/test/resources/epub/links.epub

The book has three chapters in its reading order, plus an in-book contents page. It covers each kind of link the
reader must handle:

- a web link (https), a mailto: link and a target=_blank web link;
- a link to another chapter file and to an anchor in the middle of another chapter;
- a same-document link (#note3) and an endnote round trip;
- a link wrapped around an image;
- a link to the navigation document, which is not in the reading order.
"""
import sys
import zipfile

def _png(size=8, grey=0x88):
    """A plain grey square PNG, built here so the fixture needs no binary input."""
    import struct, zlib
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes([grey]) * size for _ in range(size))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

PNG = _png()

def page(title, body):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
<head><title>{title}</title></head>
<body>
{body}
</body>
</html>
"""

def filler(n, prefix):
    return "\n".join(
        f"<p>{prefix} paragraph {i}. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
        f"incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco.</p>"
        for i in range(1, n + 1)
    )

CONTENTS = page("Contents", """
<h1>Contents</h1>
<ul>
  <li><a href="chapter1.xhtml">Chapter 1: Links</a></li>
  <li><a href="chapter2.xhtml">Chapter 2: The long one</a></li>
  <li><a href="chapter2.xhtml#sec4">Chapter 2, section 4</a></li>
  <li><a href="notes.xhtml">Notes</a></li>
  <li><a href="nav.xhtml">The navigation document</a></li>
</ul>
""")

CH1 = page("Chapter 1", f"""
<h1 id="top">Chapter 1: Links</h1>
<p>A web link: <a id="web" href="https://example.org/">example.org</a>.</p>
<p>A web link with a target: <a id="blank" href="https://example.com/" target="_blank">example.com in a new window</a>.</p>
<p>A mail link: <a id="mail" href="mailto:nobody@example.invalid">write to nobody</a>.</p>
<p>Another chapter: <a id="chapter" href="chapter2.xhtml">go to chapter 2</a>.</p>
<p>An anchor in another chapter: <a id="anchor" href="chapter2.xhtml#sec4">go to chapter 2, section 4</a>.</p>
<p>An endnote<a id="ref1" epub:type="noteref" href="notes.xhtml#n1"><sup>1</sup></a>.</p>
<p>Within this page: <a id="local" href="#note3">jump to note 3 below</a>.</p>
<p>An image link: <a id="imglink" href="https://example.net/"><img src="dot.png" alt="a grey dot" width="48" height="48"/></a></p>
{filler(30, "Chapter 1")}
<p id="note3"><strong>Note 3.</strong> This is the target of the same-page link. <a href="#top">Back to top</a>.</p>
{filler(10, "Chapter 1 tail")}
""")

CH2 = page("Chapter 2", f"""
<h1>Chapter 2: The long one</h1>
{"".join(f'<h2 id="sec{s}">Section {s}</h2>' + filler(25, f"Section {s}") for s in range(1, 8))}
<p><a href="contents.xhtml">Back to the contents</a></p>
""")

NOTES = page("Notes", """
<h1>Notes</h1>
<aside id="n1" epub:type="endnote"><p>1. The endnote text. <a href="chapter1.xhtml#ref1">Back to the text</a>.</p></aside>
""")

NAV = page("Navigation", """
<nav epub:type="toc" id="toc"><h1>Navigation</h1>
<ol>
  <li><a href="contents.xhtml">Contents</a></li>
  <li><a href="chapter1.xhtml">Chapter 1: Links</a></li>
  <li><a href="chapter2.xhtml">Chapter 2: The long one</a>
    <ol><li><a href="chapter2.xhtml#sec4">Section 4</a></li></ol></li>
  <li><a href="notes.xhtml">Notes</a></li>
</ol>
</nav>
""")

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="en">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:5b0c1f8e-quire-fixture-links</dc:identifier>
    <dc:title>Link Fixture</dc:title>
    <dc:creator>Quire Tests</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-11-14T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="contents" href="contents.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
    <item id="dot" href="dot.png" media-type="image/png"/>
  </manifest>
  <spine>
    <itemref idref="contents"/>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="notes"/>
  </spine>
</package>
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
        add("OEBPS/contents.xhtml", CONTENTS)
        add("OEBPS/chapter1.xhtml", CH1)
        add("OEBPS/chapter2.xhtml", CH2)
        add("OEBPS/notes.xhtml", NOTES)
        add("OEBPS/dot.png", PNG)

if __name__ == "__main__":
    main(sys.argv[1])
