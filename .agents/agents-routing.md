# Agent Routing

Which specialized agent to spin up, and when. This is the **portable policy**
both Claude Code and Antigravity can read (via `AGENTS.md`). The mechanics differ
per tool:

- **Claude Code** discovers spawnable sub-agents from `.claude/agents/*.md`. The
  orchestrator picks one by matching the task against each file's `description`
  frontmatter. The roles below map 1:1 to those files (names in parentheses).
- **Antigravity** reads `AGENTS.md` as its rules file; use this table as the
  delegation policy for its Agent Manager. If a role has no native definition,
  run it inline following the same trigger + scope.

Keep this table and the `.claude/agents/*.md` `description` fields in sync — the
"When to spin up" column is the source of truth for both.

## Roles

| Role | When to spin up | Scope / boundaries | Claude agent file |
|------|-----------------|--------------------|-------------------|
| **EML language specialist** | IDE-facing `.eml` tooling: parsing, lexing, folding, syntax/semantic highlighting, RFC-compliance inspections, attachment decoding for display, EML-header settings; anything touching app `lexer/`, `psi/`, `folding/`, `highlighting/`, `inspections/`, `attachment/`, `settings/`, `ui/`, or the `EmlLanguage`/`EmlTokenTypes`/`EmlFileType` roots. | Reads/edits the EML language surface. RFC scope is parsing/validation/display (rfc5322, rfc2045–rfc2049, rfc2047); cites RFCs as `rfc<NNNN> §<section>` (grep `.docs/rfc/`, never whole RFCs). PSI/settings extensions implement `DumbAware`. Defers conversion to the conversion specialist, the send path to the SMTP specialist, tests to the test author. | `eml-language-specialist` |
| **Mail conversion specialist** | Binary container parsing and conversion-to-EML: MSG→EML, PST/OST→EML, the [MS-PST] byte parser, OLE/CFB, and the shared EML/iCalendar/vCard/S-MIME output stack; anything touching app `conversion/{,msg,pst}/` or the standalone **`pst-parser/`** module. | Reads/edits conversion code. For PST/OST (ANSI / Outlook 2003+ / Outlook 2013+) reads `.docs/pst/format.md` first; the byte-level parser is `pst-parser/` (`PstFile`, `NodeDatabase`, `HeapOnNode`, `TableContext`, `PropertyContext`), with `conversion/pst/` being only IntelliJ glue. Cites `[MS-PST]` / `[MS-OXMSG]` structures; emits standards-clean iCalendar (rfc5545) / vCard (rfc6350) / DSN (rfc3464). Defers test writing to the test author. | `mail-conversion-specialist` |
| **SMTP specialist** (implementer) | IMPLEMENTING or fixing send-stack code: client/session, AUTH/SASL, STARTTLS/TLS, ESMTP, transport, proxy, XCLIENT, and the app send glue; write changes in the **`smtp-client/`** module or the app's `smtp/{audit,profile,ui}`. | Reads/edits the send path. Cites wire specs (rfc5321 / rfc3207 / rfc4954 / rfc4422 / rfc5802). Keeps the insecure defaults (owner declined the TLS-NONE/egress-ON flip — warn, don't flip); never logs credentials. Hands non-trivial diffs to the **security/audit reviewer** before shipping; live-server tests go in `smtp-client/src/integrationTest/`. | `smtp-specialist` |
| **Test author** | After a feature or defect fix needs coverage; when asked to add/repair JUnit tests or sample fixtures. | Puts the test in the SAME module as the code: app (`src/test/java/...`, `BasePlatformTestCase`, JUnit 4 vintage, fixtures in `samples/{eml,msg,pst}/`); `smtp-client/` (JUnit 5 Jupiter, fixtures in `resources/smtp/`, Testcontainers in the separate `integrationTest` source set); `pst-parser/` (JUnit 5 Jupiter, byte fixtures in `samples/pst/`). A defect fix gets a test that fails on the old behavior. Does not change production logic beyond what the test requires. | `test-author` |
| **Security / audit reviewer** (read-only) | Reviewing diffs that touch the send stack: the **`smtp-client/`** module (`auth`, `tls`, `esmtp`, `transport`, `proxy`, `xclient`) or the app's `smtp/{audit,profile,ui}` glue; TLS/egress/credential concerns; any send-path change. | Read-only review by default — reports findings, does not silently flip insecure defaults (owner declined the TLS-NONE/egress-ON change; mitigate with warnings, see memory). Pairs with the **SMTP specialist**, who implements. | `security-audit-reviewer` |

## Cross-cutting rules every agent inherits

- **Code navigation:** prefer Serena MCP (`mcp__serena__*`) over Read/Grep when
  connected — load schemas via `ToolSearch`, run `initial_instructions` once.
- **Temp files** go in `./.tmp`, never the repo root.
- **Build/style gates:** `./gradlew check` (Checkstyle + Spotless, `-Werror`)
  must stay green; see `build.md`, `code-style.md`.
- Each agent re-reads the relevant `.agents/*.md` sub-files for its trigger
  rather than relying on the orchestrator's context.
- **Integration tests:** the `smtp-client/` module has a separate
  `src/integrationTest/` source set (JUnit 5 + Testcontainers/Mailpit) that
  drives a real SMTP server for plain and STARTTLS+AUTH happy paths. Keep it out
  of the unit `test` source set — its transitive JNA crashes IntelliJ's
  Foundation init on macOS, and it needs Docker. Run it explicitly with
  `./gradlew :smtp-client:integrationTest` (also wired into `check`, after
  `test`). New send-path coverage that needs a live server goes here; the **SMTP
  specialist** (implements the send-path change), the **test author** (writes the
  coverage) and the **security/audit reviewer** (may request a STARTTLS/auth
  scenario) all route to this source set, not `smtp-client/src/test/`.

## When NOT to delegate

Trivial one-file edits, questions answerable from context, or work that spans
multiple roles at once — handle inline. Spawning a fresh agent re-derives context
from cold, so reserve it for genuinely separable, focused sub-tasks.