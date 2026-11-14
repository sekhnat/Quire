# The bundled dictionary

Quire ships Open English WordNet (CC BY 4.0), converted at build time into `oewn-<release>.qdict` and stored uncompressed
in the APK so it is read in place. `:qdict-tool:convertOewn` downloads the pinned release (URL and SHA-256 in
`qdict-tool/build.gradle.kts`), converts it, decodes every entry back to check it, and fails if the file is over 5 MB
or more than 10% larger than `size-baseline.txt`.

`size-baseline.txt` holds the size of the dictionary in the last release, in bytes. Update it when a new OEWN release
or a converter change is meant to change the size.

To compare codecs and block sizes on a new release:

    ./gradlew :qdict-tool:run --args='--input <release>.xml.gz --output /tmp/x.qdict --measure'
