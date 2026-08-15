#!/bin/sh
# Fetches the spelling-error corpora used by EnglishCorrectorRealCorpusTest.
#
#   sh scripts/fetch-corpora.sh
#
# These are NOT committed and must not be. Roger Mitton's corpora page states no
# licence for any of them, so we can measure against them locally but cannot
# redistribute them in an MIT repository. The test skips itself when they are
# absent, so a clean checkout still builds and passes.
#
# Only the two TYPED corpora are fetched. The larger Birkbeck (missp.dat) and
# Holbrook sets are transcriptions of handwriting by schoolchildren: they model
# how people misspell when they do not know a word, not how thumbs miss keys on
# glass, which is the error distribution this keyboard actually faces.
#
# Source: https://titan.dcs.bbk.ac.uk/~roger/corpora.html
set -e

DIR="$(dirname "$0")/../.corpora"
mkdir -p "$DIR"

for name in wikipedia aspell; do
  echo "fetching $name.dat"
  curl -fsS -o "$DIR/$name.dat" "https://titan.dcs.bbk.ac.uk/~roger/$name.dat"
done

echo "done. Files are in .corpora/ and are gitignored."
echo "Run: ./gradlew testDebugUnitTest --tests '*RealCorpusTest'"
