# Libraries

`tools/fetch-libs.sh` downloads these jars. Nothing here is shaded into the plugin jar.

- `lib/compile/` is the javac classpath: Paper API 1.21.8 plus the jars its public API needs, and the Anthropic Java SDK.
- `lib/test/` holds extra runtime jars. Only the AnthropicWriter mock-server test needs them; run that test with
  `lib/compile/*:lib/test/*`.

On a server, Paper supplies Paper, Adventure, Gson, Guava and the rest. It downloads the SDK and the SDK's dependencies
from the `plugin.yml` `libraries:` line:

```yaml
libraries:
  - com.anthropic:anthropic-java:3.5.0
```

## Checksums

Each jar is checked twice:

1. against the `.sha1` file the repository publishes next to it, and
2. against a SHA-256 pinned in `fetch-libs.sh`.

If either check fails, the script deletes the file and stops. To change a version, edit it here and in the script, then
run `tools/fetch-libs.sh --pin` and paste the printed SHA-256 into the script.

## Versions (pinned 2026-11-05)

| dir | artifact | version | why |
|---|---|---|---|
| compile | io.papermc.paper:paper-api | 1.21.8-R0.1-20250906.215025-55 | newest 1.21.8 snapshot build (1.21.8 is the first version with the Dialog API) |
| compile | net.kyori:adventure-api | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-key | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-text-minimessage | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-text-serializer-gson | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-text-serializer-json | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-text-serializer-legacy | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-text-serializer-plain | 4.24.0 | Paper API dependency |
| compile | net.kyori:adventure-text-logger-slf4j | 4.24.0 | Paper API dependency |
| compile | net.kyori:examination-api | 1.3.0 | Paper API dependency |
| compile | net.kyori:examination-string | 1.3.0 | Paper API dependency |
| compile | net.kyori:option | 1.1.0 | Paper API dependency |
| compile | org.jetbrains:annotations | 26.0.2 | Paper API dependency |
| compile | com.google.guava:guava | 33.3.1-jre | Paper API dependency |
| compile | com.google.code.gson:gson | 2.11.0 | Paper API dependency |
| compile | org.yaml:snakeyaml | 2.2 | Paper API dependency |
| compile | org.joml:joml | 1.10.8 | Paper API dependency |
| compile | org.slf4j:slf4j-api | 2.0.16 | Paper API dependency |
| compile | org.jspecify:jspecify | 1.0.0 | Paper API dependency |
| compile | org.checkerframework:checker-qual | 3.49.2 | Paper API dependency |
| compile | com.google.errorprone:error_prone_annotations | 2.28.0 | annotations used by Gson and Guava (stops -Xlint warnings) |
| compile | com.mojang:brigadier | 1.3.10 | Paper API dependency |
| compile | net.md-5:bungeecord-chat | 1.21-R0.2-deprecated+build.21 | Paper API dependency |
| compile | com.anthropic:anthropic-java-core | 3.5.0 | Anthropic SDK (newest release on Maven Central) |
| compile | com.anthropic:anthropic-java-client-okhttp | 3.5.0 | Anthropic SDK (newest release on Maven Central) |
| compile | org.jetbrains.kotlin:kotlin-stdlib | 1.9.0 | types used in SDK signatures (stops javac class-file warnings) |
| compile | com.fasterxml.jackson.core:jackson-core | 2.19.4 | types used in SDK signatures (stops javac class-file warnings) |
| compile | com.fasterxml.jackson.core:jackson-databind | 2.19.4 | types used in SDK signatures (stops javac class-file warnings) |
| compile | com.fasterxml.jackson.core:jackson-annotations | 2.19.4 | types used in SDK signatures (stops javac class-file warnings) |
| compile | org.apache.logging.log4j:log4j-api | 2.26.0 | the console filter that keeps letters out of the server log (same version as Paper 26.3; Paper supplies it) |
| compile | org.apache.logging.log4j:log4j-core | 2.26.0 | the console filter that keeps letters out of the server log (same version as Paper 26.3; Paper supplies it) |
| test | com.fasterxml.jackson.datatype:jackson-datatype-jdk8 | 2.19.4 | SDK runtime dependency (mock-server test only) |
| test | com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | 2.19.4 | SDK runtime dependency (mock-server test only) |
| test | com.fasterxml.jackson.module:jackson-module-kotlin | 2.19.4 | SDK runtime dependency (mock-server test only) |
| test | org.jetbrains.kotlin:kotlin-reflect | 1.9.0 | SDK runtime dependency (mock-server test only) |
| test | com.squareup.okhttp3:okhttp | 4.12.0 | SDK runtime dependency (mock-server test only) |
| test | com.squareup.okio:okio-jvm | 3.6.0 | SDK runtime dependency (mock-server test only) |
| test | io.opentelemetry:opentelemetry-api | 1.62.0 | SDK runtime dependency (mock-server test only) |
| test | io.opentelemetry:opentelemetry-context | 1.62.0 | SDK runtime dependency (mock-server test only) |

These are left out on purpose: fastutil, the maven-resolver jars and adventure-text-serializer-commons. javac
does not need them to compile against the API we use. (`Plugin.getLog4JLogger()` returns a log4j type, so do not call
it.)

Note: Paper 1.21.8 ships Gson 2.11.0, so code that compiles against a newer Gson might call a method missing at runtime.
Keep only one Gson jar (the pinned 2.11.0) on the classpath.
