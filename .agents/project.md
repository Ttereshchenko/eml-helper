# Project Overview

`MailKit` is an IntelliJ IDEA plugin (Java, JDK 21 toolchain) that provides syntax highlighting, MIME part code folding, and per-header color customization for EML (Email Message format) files.

## Tech Stack

- **Language**: Java, targeting JDK 21 bytecode (IntelliJ 2026.1 runs on JBR 21). Toolchain is auto-provisioned via Foojay (`settings.gradle`).
- **Build**: Gradle 9.2.0 (wrapper at `./gradlew`)
- **Plugin**: IntelliJ Platform Gradle Plugin 2.16.0
- **Target IDE**: IntelliJ IDEA 2026.1
- **Testing**: JUnit6 (6.0.3)
- **Package**: `com.github.ttereshchenko.mailkit`

## Architecture

Multi-package project under `src/main/java/com/github/ttereshchenko/mailkit/` with sub-packages: `folding`, `highlighting`, `lexer`, `settings`. Plugin descriptor at `src/main/resources/META-INF/plugin.xml`. No external runtime dependencies — only JUnit 5 for testing. Tests go in `src/test/java/com/github/ttereshchenko/mailkit/`.

## Test Conventions

Tests extend `com.intellij.testFramework.fixtures.BasePlatformTestCase` (JUnit 4
inheritance, executed via `junit-vintage-engine`). Method names start with
`test`. Sample EML files live under `src/test/resources/samples/`. Canonical
examples to copy from: `EmlLexerTest`, `EmlFoldingBuilderTest`.
