# Project Overview

`MailKit` is an IntelliJ IDEA plugin (Java, JDK 21 toolchain) that provides syntax highlighting, MIME part code folding, and per-header color customization for EML (Email Message format) files.

## Tech Stack

- **Language**: Java, targeting JDK 21 bytecode (IntelliJ 2026.1 runs on JBR 21). Toolchain is auto-provisioned via Foojay (`settings.gradle`).
- **Build**: Gradle 9.5.1 (wrapper at `./gradlew`)
- **Plugin**: IntelliJ Platform Gradle Plugin 2.16.0
- **Target IDE**: IntelliJ IDEA 2026.1
- **Dependencies**: one runtime dep — Apache POI `poi-scratchpad` 5.5.1 (reads Outlook `.msg` OLE2 for the MSG→EML converter). Testcontainers + Mailpit are integration-test-only (separate `integrationTest` source set).
- **Testing**: JUnit (junit-bom 6.1.0) — Jupiter for new tests, JUnit 4 via `junit-vintage-engine` for `BasePlatformTestCase` subclasses
- **Package**: `com.github.ttereshchenko.mailkit`

## Architecture

Multi-project Gradle build consisting of the root plugin module and `smtp-client` subproject.

**Root Module (mailkit)**
Source code under `src/main/java/com/github/ttereshchenko/mailkit/`.
Root classes `EmlFileType` / `EmlLanguage` / `EmlTokenTypes`, plus sub-packages:

- `lexer/`, `psi/` — EML lexing/parsing and PSI/token element types
- `folding/`, `highlighting/`, `settings/` — MIME-part folding, syntax & header
  highlighting, per-header color settings
- `attachment/` — attachment detection/decoding and save/open actions (gutter marker)
- `inspections/` — header/MIME inspections, split into `rules/` (pure, unit-testable
  logic) and `tools/` (`LocalInspectionTool` wrappers); HTML descriptions in
  `src/main/resources/inspectionDescriptions/`
- `conversion/` — EML writers and Outlook `.msg`/`.pst` → `.eml` conversions.
- `smtp/` — Plugin-specific UI (`ui`), profiles (`profile`), and audit logging (`audit`).
- `icons/`

Plugin descriptor at `src/main/resources/META-INF/plugin.xml`. Unit tests live in
`src/test/java/com/github/ttereshchenko/mailkit/`.

**Subproject (smtp-client)**
Standalone pure Java library with the core SMTP send stack under `smtp-client/src/main/java/com/github/ttereshchenko/mailkit/smtp/`:
- `auth`, `esmtp`, `tls`, `transport`, `proxy`, `xclient`.

SMTP integration tests live in a SEPARATE `smtp-client/src/integrationTest/` source set that uses
Testcontainers/Mailpit (kept apart so transitive JNA doesn't crash IntelliJ's
Foundation init on macOS). For the always-current package/symbol map prefer Serena
(`mem:code_architecture`, `mem:smtp/core`) over this summary.

## Code Navigation

If the [Serena](https://github.com/oraios/serena) MCP server is connected (setup
in `README.md`), prefer its semantic tools over reading whole files or
text-grepping when locating or changing code — they are more accurate and
token-efficient on this Java codebase. Plain Read/Grep/Edit remain the fallback
when Serena is not connected.

The Serena tools are deferred in the Claude Code harness, so their schemas must
be loaded with `ToolSearch` (e.g. `select:mcp__serena__find_symbol,...`) before
the first call, and Serena's own `initial_instructions` should be run once per
session before coding.

**Session start:** after `initial_instructions`, read Serena's project memories —
begin at the graph root `core` (`read_memory`) and follow its `mem:` references.
The graph deliberately points back to these `.agents/*.md` files for domain
knowledge and holds only Serena-operational notes itself; the key one is
`serena_setup`, which records why symbol queries can silently return empty
(JDTLS imports this project with its bundled Gradle 8.14.2, but the IntelliJ
Platform Gradle Plugin needs Gradle 9+, so the fix `gradle_wrapper_enabled: true`
lives in `.serena/project.yml`).

**Tool surface** (use these, not the built-ins, when Serena is connected):
- Explore — `get_symbols_overview`, then `find_symbol` (`depth` / `include_body`).
- Relations — `find_referencing_symbols`, `find_implementations`,
  `find_declaration`.
- Edit — `replace_symbol_body`, `insert_before_symbol` / `insert_after_symbol`,
  `rename_symbol`, `safe_delete_symbol`, `replace_content` (sub-symbol regex).
- Diagnostics — `get_diagnostics_for_file`.

This guidance is restated in imperative form under "Code navigation" in
`AGENTS.md` (the auto-loaded file) so it applies even on tasks that never trip
this file's architecture/dependency trigger — keep the two in sync.

## Test Conventions

Tests extend `com.intellij.testFramework.fixtures.BasePlatformTestCase` (JUnit 4
inheritance, executed via `junit-vintage-engine`). Method names start with
`test`. Sample EML files live under `src/test/resources/samples/`. Canonical
examples to copy from: `EmlLexerTest`, `EmlFoldingBuilderTest`.
