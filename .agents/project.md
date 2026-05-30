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

## Code Navigation

If the [Serena](https://github.com/oraios/serena) MCP server is connected (setup
in `README.md`), prefer its semantic tools — `find_symbol`,
`find_referencing_symbols`, symbol outlines, and symbol-aware edits — over reading
whole files or text-grepping when locating or changing code. They are more
accurate and token-efficient on this Java codebase. Plain Read/Grep/Edit remain
the fallback when Serena is not connected.

The Serena tools are deferred in the Claude Code harness, so their schemas must
be loaded with `ToolSearch` (e.g. `select:mcp__serena__find_symbol,...`) before
the first call, and Serena's own `initial_instructions` should be run once per
session before coding. This rule is restated in imperative form under "Code
navigation" in `AGENTS.md` (the auto-loaded file) so it applies even on tasks
that never trip this file's architecture/dependency trigger — keep the two in
sync.

## Test Conventions

Tests extend `com.intellij.testFramework.fixtures.BasePlatformTestCase` (JUnit 4
inheritance, executed via `junit-vintage-engine`). Method names start with
`test`. Sample EML files live under `src/test/resources/samples/`. Canonical
examples to copy from: `EmlLexerTest`, `EmlFoldingBuilderTest`.
