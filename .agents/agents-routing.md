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
| **EML / MIME / PST specialist** | Parsing, folding, or validating EML/MIME structure; RFC-compliance questions; MSG→EML or PST/OST conversion logic; anything touching `lexer/`, `psi/`, `folding/`, `inspections/`, `conversion/`, `attachment/`. | Reads/edits format code. For EML/MIME cites RFCs as `rfc<NNNN> §<section>` (grep `.docs/rfc/`). For PST/OST (ANSI / Outlook 2003+ / Outlook 2013+) reads `.docs/pst/format.md` first and cites `[MS-PST]` structures. Never reads whole RFCs. Defers test writing to the test author. | `eml-format-specialist` |
| **Test author** | After a feature or defect fix needs coverage; when asked to add/repair JUnit tests or sample EML fixtures. | Writes tests under `src/test/java/...` extending `BasePlatformTestCase` (`test*` methods) + a `snake_case` sample under `src/test/resources/samples/eml/`. A defect fix gets a test that fails on the old behavior. Does not change production logic beyond what the test requires. | `test-author` |
| **Security / audit reviewer** | Reviewing diffs that touch `smtp/` (`auth`, `tls`, `esmtp`, `transport`, `proxy`, `xclient`, `audit`); TLS/egress/credential concerns; any send-path change. | Read-only review by default — reports findings, does not silently flip insecure defaults (owner declined the TLS-NONE/egress-ON change; mitigate with warnings, see memory). | `security-audit-reviewer` |

## Cross-cutting rules every agent inherits

- **Code navigation:** prefer Serena MCP (`mcp__serena__*`) over Read/Grep when
  connected — load schemas via `ToolSearch`, run `initial_instructions` once.
- **Temp files** go in `./.tmp`, never the repo root.
- **Build/style gates:** `./gradlew check` (Checkstyle + Spotless, `-Werror`)
  must stay green; see `build.md`, `code-style.md`.
- Each agent re-reads the relevant `.agents/*.md` sub-files for its trigger
  rather than relying on the orchestrator's context.

## When NOT to delegate

Trivial one-file edits, questions answerable from context, or work that spans all
three roles at once — handle inline. Spawning a fresh agent re-derives context
from cold, so reserve it for genuinely separable, focused sub-tasks.