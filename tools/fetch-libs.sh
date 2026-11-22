#!/usr/bin/env bash
# Download the jars Masthead compiles against into lib/compile/ (and the extra runtime jars the
# AnthropicWriter mock-server test needs into lib/test/).
#
# Every jar is checked twice:
#   1. against the SHA-1 the repository publishes next to it (<jar>.sha1), and
#   2. against the SHA-256 pinned in this file (recorded the first time with --pin).
# A mismatch on either deletes the download and stops the script.
#
# Usage:  tools/fetch-libs.sh            # fetch missing jars, verify all
#         tools/fetch-libs.sh --pin      # (maintainers) print SHA-256 pins for entries that have none
#
# Versions are listed in tools/LIBS.md. Change a version here and there together, then run --pin.
set -euo pipefail
umask 022

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTRAL="https://repo1.maven.org/maven2"
PAPER="https://repo.papermc.io/repository/maven-public"
PIN_MODE=0
[[ "${1:-}" == "--pin" ]] && PIN_MODE=1

# dest  repo  group  artifact  version  file-version  sha256
# file-version differs from version only for snapshots (the timestamped build is pinned).
LIBS=$(cat <<'EOF'
compile PAPER   io.papermc.paper              paper-api                        1.21.8-R0.1-SNAPSHOT  1.21.8-R0.1-20250906.215025-55  aab18363ca5a1aaadd4e2716ee0de50bc26f352901af962177d86484baff7478
compile CENTRAL net.kyori                     adventure-api                    4.24.0   4.24.0   d8c1d66db0fbaf084e8179b3f2541d9ff64402a7881070a71e8b3c26c26d384e
compile CENTRAL net.kyori                     adventure-key                    4.24.0   4.24.0   d4f21f4281e89dab345a9004567668794819f9cc483eaff8bfd64c57494a9150
compile CENTRAL net.kyori                     adventure-text-minimessage       4.24.0   4.24.0   7a7f44dc3b78379b918f91dce38bff26a0963bfe303a3fe16c91d30a5c5e23e5
compile CENTRAL net.kyori                     adventure-text-serializer-gson   4.24.0   4.24.0   2414c3ce5ffe1b7e31de86968ebd3e9f2a50e354939b276588ed2cd64c431821
compile CENTRAL net.kyori                     adventure-text-serializer-json   4.24.0   4.24.0   ff6a80344cbf14d6a751947caf9db9669dd0740aeac8cc555c9b8648ff5771b2
compile CENTRAL net.kyori                     adventure-text-serializer-legacy 4.24.0   4.24.0   40ed53afe2a7d668b814add69de15acf4bda8e9f951c4e5d4e224d777054685e
compile CENTRAL net.kyori                     adventure-text-serializer-plain  4.24.0   4.24.0   7e52a98e590d088ff07be1f8b0e759864717466fd35db1862fbb6ebb41b245db
compile CENTRAL net.kyori                     adventure-text-logger-slf4j      4.24.0   4.24.0   22e30a6e92a70e0a442eb64c906524a776f3c303e285e183cf2b7c435f90a81c
compile CENTRAL net.kyori                     examination-api                  1.3.0    1.3.0    c9237ffecb05428f6eff86216246ac70ce0b47b04c08ea7ca35020fde57f8492
compile CENTRAL net.kyori                     examination-string               1.3.0    1.3.0    7d01fc25a4bb3af0e1662685455f4541fbf4626216ea5846e455c1491e156b8c
compile CENTRAL net.kyori                     option                           1.1.0    1.1.0    97b69b4b17dfe02217c9131ad342564cbc9aebd04c75eb689639b5f78fd4b11c
compile CENTRAL org.jetbrains                 annotations                      26.0.2   26.0.2   2037be378980d3ba9333e97955f3b2cde392aa124d04ca73ce2eee6657199297
compile CENTRAL com.google.guava              guava                            33.3.1-jre 33.3.1-jre 4bf0e2c5af8e4525c96e8fde17a4f7307f97f8478f11c4c8e35a0e3298ae4e90
compile CENTRAL com.google.code.gson          gson                             2.11.0   2.11.0   57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b
compile CENTRAL org.yaml                      snakeyaml                        2.2      2.2      1467931448a0817696ae2805b7b8b20bfb082652bf9c4efaed528930dc49389b
compile CENTRAL org.joml                      joml                             1.10.8   1.10.8   bf19510145178df82cd3bd37edd514c13f411531ec5545299fd3abcbc98fe7c2
compile CENTRAL org.slf4j                     slf4j-api                        2.0.16   2.0.16   a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a
compile CENTRAL org.jspecify                  jspecify                         1.0.0    1.0.0    1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
compile CENTRAL org.checkerframework          checker-qual                     3.49.2   3.49.2   33b1d8b2c4b00f7d4f4b4931424274f3f729474ce3a87cea09a879976a1dcaeb
compile CENTRAL com.google.errorprone         error_prone_annotations          2.28.0   2.28.0   f3fc8a3a0a4020706a373b00e7f57c2512dd26d1f83d28c7d38768f8682b231e
compile PAPER   com.mojang                    brigadier                        1.3.10   1.3.10   c8ee4136e474ac7723ca2b432ec8d1a2bc88ef7d1ec57c314ba9e33cdc83dd75
compile PAPER   net.md-5                      bungeecord-chat                  1.21-R0.2-deprecated+build.21 1.21-R0.2-deprecated+build.21 ce930b3ee048520a0ab538eb8d1627b301367fc1b300837235387aded6d89ba1
compile CENTRAL com.anthropic                 anthropic-java-core              3.5.0    3.5.0    147a1218ae60112364a3e3c26f4ceb8b8960abba92b08a8a80c02a6186ccc4b9
compile CENTRAL com.anthropic                 anthropic-java-client-okhttp     3.5.0    3.5.0    c39c19da5e812e4844ac1833697949d594bdd72a61629b8922404ed1c2db9f40
compile CENTRAL org.jetbrains.kotlin          kotlin-stdlib                    1.9.0    1.9.0    35aeffbe2db5aa446072cee50fcee48b7fa9e2fc51ca37c0cc7d7d0bc39d952e
compile CENTRAL com.fasterxml.jackson.core    jackson-core                     2.19.4   2.19.4   466ae4dc4f7054f51525f4211df411d9a64d65cc169995d32bf49e20e6f586e5
compile CENTRAL com.fasterxml.jackson.core    jackson-databind                 2.19.4   2.19.4   b781c431be870deb0bf84421e99e774c256a701f4ba678df80248f55cd16ff74
compile CENTRAL com.fasterxml.jackson.core    jackson-annotations              2.19.4   2.19.4   ab26383fa3c10c0df1ffd1bb035d81161c059b1af71927d73c24473c058baa16
compile CENTRAL org.apache.logging.log4j      log4j-api                        2.26.0   2.26.0   820de7bbaf430eaf2e3e5ccb92f23e2085797c5ac4d4f7fc6c8b660fbefa1dbc
compile CENTRAL org.apache.logging.log4j      log4j-core                       2.26.0   2.26.0   406bb3132c47a5d322e5c571feb0787ca84496469f346eb51f1ef2ee45499902
test    CENTRAL com.fasterxml.jackson.datatype jackson-datatype-jdk8           2.19.4   2.19.4   c0e0dc3039f9a37a099b2602838f833ea8c15c56445a0eb5463d0c7e0c355da2
test    CENTRAL com.fasterxml.jackson.datatype jackson-datatype-jsr310         2.19.4   2.19.4   070af4d7c345cf975ff783138050678e6194e5bb88ab489385d0311a5fcb1585
test    CENTRAL com.fasterxml.jackson.module  jackson-module-kotlin            2.19.4   2.19.4   440a2533aa6523296a58c4798ad920cd77b1b819ccc6fbd9e96c81d53794ed90
test    CENTRAL org.jetbrains.kotlin          kotlin-reflect                   1.9.0    1.9.0    2070159be514ea8ea3ce2c8b1ee7993e6ff60a93565a299f200629aa7df7f581
test    CENTRAL com.squareup.okhttp3          okhttp                           4.12.0   4.12.0   b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0
test    CENTRAL com.squareup.okio             okio-jvm                         3.6.0    3.6.0    67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412
test    CENTRAL io.opentelemetry              opentelemetry-api                1.62.0   1.62.0   d58143928c049233653e6bf1282ff8a767ebdd5e6a82b3f336158c30e40b431d
test    CENTRAL io.opentelemetry              opentelemetry-context            1.62.0   1.62.0   8ec86c3609222b9b6307bf40154c1f1c288d1f18ada0155409e7797adbdbe517
EOF
)

die() { echo "fetch-libs: $*" >&2; exit 1; }
command -v curl >/dev/null || die "curl is required"
command -v sha256sum >/dev/null || die "sha256sum is required"

fetch() {  # url out
  curl --proto '=https' --tlsv1.2 -fsSL --retry 3 --max-time 300 -o "$2" "$1"
}

fail=0
while read -r dest repo group artifact version fver pin; do
  [[ -z "$dest" || "$dest" == \#* ]] && continue
  case "$repo" in CENTRAL) base="$CENTRAL" ;; PAPER) base="$PAPER" ;; *) die "bad repo $repo" ;; esac
  dir="$ROOT/lib/$dest"; mkdir -p "$dir"
  name="$artifact-$fver.jar"
  out="$dir/$name"
  url="$base/${group//.//}/$artifact/$version/$name"
  if [[ ! -f "$out" ]]; then
    tmp="$(mktemp "$dir/.dl.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    fetch "$url" "$tmp" || { rm -f "$tmp"; die "download failed: $url"; }
    want_sha1="$(curl --proto '=https' -fsSL --max-time 60 "$url.sha1" | awk '{print $1}' | tr -d '\r\n')" \
      || { rm -f "$tmp"; die "no published .sha1 for $name"; }
    got_sha1="$(sha1sum "$tmp" | awk '{print $1}')"
    [[ "$want_sha1" == "$got_sha1" ]] || { rm -f "$tmp"; die "SHA-1 mismatch for $name (repo says $want_sha1, got $got_sha1)"; }
    mv -f "$tmp" "$out"
    trap - EXIT
  fi
  got="$(sha256sum "$out" | awk '{print $1}')"
  if [[ "$pin" == "-" ]]; then
    if (( PIN_MODE )); then echo "PIN $artifact $got"; else echo "fetch-libs: $name has no pinned SHA-256 (run --pin)" >&2; fail=1; fi
  elif [[ "$pin" != "$got" ]]; then
    rm -f "$out"; die "SHA-256 mismatch for $name (pinned $pin, got $got) - file removed"
  fi
done <<< "$LIBS"

(( fail )) && exit 1
echo "fetch-libs: all jars present and verified ($ROOT/lib/compile, $ROOT/lib/test)"
