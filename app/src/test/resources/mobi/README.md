# MOBI fixtures

Made with Calibre 8.16.2 from `source.html`, `cover.png` and `figure.png`:

```sh
OPTS="--title 'Fixture Book' --authors 'Ada Writer&Bo Second' --cover cover.png --language fr \
  --tags 'Alpha,Beta' --comments 'A short description.' --publisher 'Quire Press' --pubdate 2001-02-03 \
  --isbn 9780000000002 --level1-toc //h:h1 --level2-toc //h:h2 --chapter //h:h1"
ebook-convert source.html mobi6.mobi $OPTS --mobi-file-type old                  # MOBI 6, PalmDOC
ebook-convert source.html mobi6-uncompressed.mobi $OPTS --mobi-file-type old --dont-compress
ebook-convert source.html joint.mobi $OPTS --mobi-file-type both                 # MOBI 6 + KF8
ebook-convert source.html kf8.azw3 $OPTS                                         # KF8 only
```

The long second chapter spreads the text over several 4 KiB records, so multibyte characters cross record
boundaries.
