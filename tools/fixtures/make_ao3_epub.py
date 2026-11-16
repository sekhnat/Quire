#!/usr/bin/env python3
"""Writes the AO3-shaped fixtures for Ao3PrefaceTest: small EPUBs laid out like Archive of Our Own downloads.

Usage: python3 -I tools/fixtures/make_ao3_epub.py app/src/test/resources/epub

AO3 builds its EPUBs with Calibre: the package document lists every tag as a flat dc:subject (plus "Fanworks"), and the
first page of the spine, the "Preface", has the same tags typed in a <dl class="tags"> of <dt>Label:</dt><dd> pairs,
with Calibre's generated class names. The story text here is filler; fandoms, names and tags are made up.

- ao3_work.epub: a finished work, singular labels as AO3 prints them for one tag, series part, collections, stats 6/6.
- ao3_wip.epub: a work in progress (3/?), plural labels, two fandoms, files under OEBPS/Text/ with an escaped href.
- ao3_no_preface.epub: an AO3 book whose preface has no tag list (the subjects alone are left).
- not_ao3.epub: a tag list like AO3's in a book from another publisher, which must be ignored.
"""
import os
import sys
import zipfile
import zlib

FIXED = (2024, 1, 1, 0, 0, 0)

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
   <rootfiles>
      <rootfile full-path="{opf}" media-type="application/oebps-package+xml"/>
   </rootfiles>
</container>
"""


def opf(title, publisher, subjects, pages, extra=""):
    subs = "\n".join(f"    <dc:subject>{s}</dc:subject>" for s in subjects)
    items = "\n".join(f'    <item id="p{i}" href="{href}" media-type="application/xhtml+xml"/>' for i, (href, _) in enumerate(pages))
    refs = "\n".join(f'    <itemref idref="p{i}"/>' for i in range(len(pages)))
    return f"""<?xml version='1.0' encoding='utf-8'?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uuid_id">
  <metadata xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:calibre="http://calibre.kovidgoyal.net/2009/metadata">
    <dc:title>{title}</dc:title>
    <dc:language>en</dc:language>
    <dc:creator opf:file-as="quill" opf:role="aut">quill</dc:creator>
    <dc:identifier id="uuid_id" opf:scheme="uuid">00000000-0000-4000-8000-{zlib.crc32(title.encode()):012d}</dc:identifier>
    <dc:publisher>{publisher}</dc:publisher>
{extra}{subs}
    <dc:date>2021-03-04T00:00:00+00:00</dc:date>
  </metadata>
  <manifest>
{items}
  </manifest>
  <spine>
{refs}
  </spine>
</package>
"""


def page(title, body):
    return f"""<?xml version='1.0' encoding='utf-8'?>
<html xmlns="http://www.w3.org/1999/xhtml" lang="en" xml:lang="en">
  <head><title>{title}</title><meta http-equiv="Content-Type" content="text/html; charset=utf-8"/></head>
  <body class="calibre">
{body}
  </body>
</html>
"""


def links(*names):
    return ", ".join(f'<a href="https://archiveofourown.org/tags/{n.replace(" ", "%20").replace("/", "*s*")}">{n}</a>' for n in names)


def tag_list(rows, stats):
    out = ['    <dl class="tags">']
    for label, value in rows:
        out.append(f'      <dt class="calibre3">{label}</dt>')
        out.append(f'      <dd class="calibre4">{value}</dd>')
    out.append('      <dt class="calibre3">Stats:</dt>')
    out.append(f'      <dd class="calibre5">\n        {stats}\n      </dd>')
    out.append("    </dl>")
    return "\n".join(out)


def preface(title, dl):
    return page(title, f"""<div id="preface" class="calibre1">
  <h2 class="toc-heading">Preface</h2>
  <p class="message"><b class="calibre2">{title}</b><br class="calibre1"/>
    Posted originally on the <a href="http://archiveofourown.org/">Archive of Our Own</a> at <a href="http://archiveofourown.org/works/1000001">http://archiveofourown.org/works/1000001</a>.</p>
  <div class="calibre1">
{dl}
  </div>
</div>""")


def chapter(n):
    paras = "\n".join(f'<p class="calibre7">Chapter {n}, paragraph {i}. Lorem ipsum dolor sit amet, consectetur adipiscing elit.</p>' for i in range(1, 6))
    return page(f"Chapter {n}", f'<div class="userstuff1"><h2 class="heading">Chapter {n}</h2>\n{paras}\n</div>')


def write(path, opf_path, opf_text, files):
    with zipfile.ZipFile(path, "w") as z:
        info = zipfile.ZipInfo("mimetype", FIXED)
        z.writestr(info, "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        for name, text in [("META-INF/container.xml", CONTAINER.format(opf=opf_path)), (opf_path, opf_text)] + files:
            z.writestr(zipfile.ZipInfo(name, FIXED), text, compress_type=zipfile.ZIP_DEFLATED)


def work(out):
    title = "Lanterns Over the Harbor"
    rows = [
        ("Rating:", links("Mature")),
        ("Archive Warning:", links("No Archive Warnings Apply")),
        ("Category:", links("F/F")),
        ("Fandom:", links("Starlight Harbor (TV 2031)")),
        ("Relationship:", links("Mira Vale/June Okafor")),
        ("Characters:", links("Mira Vale", "June Okafor", "Captain Ines Roth")),
        ("Additional Tags:", links("Alternate Universe - No Powers", "Fake/Pretend Relationship", "Slow Burn")),
        ("Language:", "English"),
        ("Series:", 'Part 2 of\n<a href="http://archiveofourown.org/series/2000002">the harbor cycle</a>'),
        ("Collections:", '<a href="http://archiveofourown.org/collections/harborfest">Harbor Fest 2021</a>,\n<a href="http://archiveofourown.org/collections/favs">Favs</a>'),
    ]
    stats = "Published: 2021-01-02\n          Completed: 2021-03-04\n        Words: 12,345\n        Chapters: 6/6"
    subjects = ["Fanworks", "Mature", "Starlight Harbor (TV 2031)", "Mira Vale/June Okafor", "Mira Vale", "June Okafor",
                "Captain Ines Roth", "Alternate Universe - No Powers", "Fake/Pretend Relationship", "Slow Burn", "F/F",
                "No Archive Warnings Apply"]
    pages = [("work_split_000.xhtml", preface(title, tag_list(rows, stats))), ("work_split_001.xhtml", chapter(1))]
    write(os.path.join(out, "ao3_work.epub"), "content.opf", opf(title, "Archive of Our Own", subjects, pages), pages)


def wip(out):
    title = "Salt &amp; Static"
    rows = [
        ("Rating:", links("Teen And Up Audiences")),
        ("Archive Warnings:", links("Graphic Depictions Of Violence", "Major Character Death")),
        ("Categories:", links("Gen", "Multi")),
        ("Fandoms:", links("Starlight Harbor (TV 2031)", "Night Ferry (Comics)")),
        ("Relationships:", links("Mira Vale &amp; June Okafor")),
        ("Characters:", links("Mira Vale")),
        ("Additional Tags:", links("Found Family")),
    ]
    stats = "Published: 2022-05-06\n          Updated: 2022-07-08\n        Words: 4.321\n        Chapters: 3/?"
    subjects = ["Fanworks", "Teen And Up Audiences", "Graphic Depictions Of Violence", "Major Character Death", "Gen", "Multi",
                "Starlight Harbor (TV 2031)", "Mira Vale &amp; June Okafor", "Mira Vale", "Found Family"]
    pages = [("Text/pre%20face.xhtml", preface(title, tag_list(rows, stats))), ("Text/chapter1.xhtml", chapter(1))]
    files = [("OEBPS/Text/pre face.xhtml", pages[0][1]), ("OEBPS/Text/chapter1.xhtml", pages[1][1])]
    # No dc:subject for the second fandom: the preface's tags the package lacks are added too.
    write(os.path.join(out, "ao3_wip.epub"), "OEBPS/content.opf", opf(title, "Archive of Our Own", subjects, pages), files)


def no_preface(out):
    title = "Tidewater"
    subjects = ["Fanworks", "Explicit", "Choose Not To Use Archive Warnings", "M/M", "Night Ferry (Comics)", "Other"]
    pages = [("preface.xhtml", page(title, "<h1>Tidewater</h1><p>by quill</p>")), ("chapter1.xhtml", chapter(1))]
    write(os.path.join(out, "ao3_no_preface.epub"), "content.opf", opf(title, "Archive of Our Own", subjects, pages), pages)


def not_ao3(out):
    title = "A Field Guide"
    rows = [("Rating:", links("Mature")), ("Fandom:", links("Birdwatching"))]
    pages = [("intro.xhtml", preface(title, tag_list(rows, "Words: 100"))), ("chapter1.xhtml", chapter(1))]
    write(os.path.join(out, "not_ao3.epub"), "content.opf", opf(title, "Example Press", ["Nature", "Mature"], pages), pages)


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "."
    for make in (work, wip, no_preface, not_ao3):
        make(target)
