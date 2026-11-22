#!/bin/bash
# Build build/Masthead.jar (javac + jar; no Maven/Gradle needed). First run tools/fetch-libs.sh once.
#   ./build.sh          compile + jar
#   ./build.sh test     also compile and run the tests
set -euo pipefail
cd "$(dirname "$0")"
J=${JAVA_HOME:-/usr/lib/jvm/temurin-25-jdk-amd64}/bin
[ -d lib/compile ] || { echo "run tools/fetch-libs.sh first"; exit 1; }
CP=$(ls lib/compile/*.jar | paste -sd:)
rm -rf build && mkdir -p build/classes build/test
"$J/javac" --release 21 -nowarn -Xlint:-options -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
VER=$(grep -m1 '^version:' src/main/resources/plugin.yml | tr -d "' " | cut -d: -f2)
# our own Created-By line: the jar tool would write its exact JDK build there, and the hash would change with every JDK patch
printf 'Created-By: Masthead build.sh\n' > build/manifest.txt
"$J/jar" --create --manifest build/manifest.txt --date="$(git log -1 --format=%cI 2>/dev/null || echo 2026-01-01T00:00:00Z)" --file "build/Masthead-$VER.jar" -C build/classes .
echo "built build/Masthead-$VER.jar"
if [ "${1:-}" = test ]; then
  TCP=$(ls lib/test/*.jar 2>/dev/null | paste -sd: || true)
  "$J/javac" --release 21 -nowarn -Xlint:-options -encoding UTF-8 -cp "build/classes:$CP:$TCP" -d build/test $(find src/test/java -name '*.java')
  R="build/classes:build/test:$CP:$TCP"
  for t in config.SettingsLoaderTest construction.ConstructionTest construction.ConstructionIOTest construction.KindDeskTest construction.FeaturesTest construction.ProductionTest construction.OpeningTest construction.ReviewRound2Test construction.SignNamesTest facts.ConstructionFactsTest facts.ReviewRound2FactsTest edition.ConstructionGateTest facts.SmokeTest facts.BuildingTest edition.EditionUnitTest edition.VoiceTest collect.DeathTextTest collect.RecorderLockTest collect.BlotterOwnerTest pipeline.PipelineTest game.MailroomTest web.WebServerTest writer.AnthropicWriterTest writer.OpenAiCompatibleWriterTest writer.CommandExchangeWriterTest; do
    "$J/java" -cp "$R" io.github.eastmadc.masthead.$t > "build/test-$t.log" 2>&1 && echo "PASS $t" || { echo "FAIL $t (build/test-$t.log)"; exit 1; }
  done
  "$J/java" -cp "$R" io.github.eastmadc.masthead.qr.QrBitmapTest build/qr > build/test-qr.log 2>&1 && echo "PASS qr.QrBitmapTest" || { echo "FAIL qr"; exit 1; }
fi
