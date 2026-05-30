# AGENTS.md

Guidance for AI agents working in this repository.

## How to use this file

`CLAUDE.md` (symlinked to this file) is auto-loaded each session — these rules
already apply. The five files in `.agents/` are NOT auto-loaded; each has a
trigger below. Re-read each one yourself (do not delegate to a sub-agent) when
its trigger applies in the current task.

At the END of every answer, list which sub-files you actually applied, e.g.:
`Respected: code-style.md, agent-prompt.md`. List only files whose content
materially shaped the answer — empty list is fine for trivial answers.

## Code navigation

Before locating, exploring, or explaining code, use the Serena MCP server if it
is connected (the `mcp__serena__*` tools). Serena's Java backend (Eclipse JDTLS)
is symbol-aware and token-efficient, so prefer it over reading whole files or
text-grepping. Per session, in order:

1. Load the deferred tool schemas with `ToolSearch` (e.g.
   `select:mcp__serena__find_symbol,...`).
2. Run `initial_instructions` once to load Serena's own operating manual.
3. Read the project memories — start at the graph root `core` via `read_memory`,
   then follow its `mem:` references. They hold Serena-operational knowledge
   (e.g. the JDTLS/Gradle setup) that is not duplicated in these docs.

Then work through Serena's tools rather than the built-ins:
- Explore: `get_symbols_overview` on a file, then `find_symbol` (with `depth` /
  `include_body`) to drill in.
- Relations: `find_referencing_symbols`, `find_implementations`,
  `find_declaration`.
- Edit code: `replace_symbol_body`, `insert_before_symbol` /
  `insert_after_symbol`, `rename_symbol`, `safe_delete_symbol`, or
  `replace_content` for sub-symbol regex edits. (Serena's manual forbids the
  built-in Edit on code files you discovered through it.)
- Check: `get_diagnostics_for_file` after edits.

This applies to every task, not just architecture work. Fall back to plain Read /
Grep / find / Edit only when Serena is not connected. Rationale and details:
[.agents/project.md](.agents/project.md) — keep the two in sync.

## Sub-files (re-read when the trigger applies)

- [.agents/project.md](.agents/project.md) — tech stack, JDK, packages, test layout. Re-read before any architecture or dependency decision.
- [.agents/build.md](.agents/build.md) — gradle commands. Re-read before running, building, or packaging.
- [.agents/workflow.md](.agents/workflow.md) — process rules. Re-read before adding a feature or touching `README.md` / `CHANGELOG.md`.
- [.agents/code-style.md](.agents/code-style.md) — Java conventions. Re-read before writing or editing any `.java` file.
- [.agents/testing.md](.agents/testing.md) — test & sample-EML rules. Re-read before implementing a feature or fixing a defect.
- [.agents/agent-prompt.md](.agents/agent-prompt.md) — answer-format rules. The "list which files you read" rule above is duplicated here; keep the two in sync.
- [.docs/](.docs/) — internal reference material, incl. `.docs/rfc/` (full email RFC corpus, grouped by topic). NOT auto-loaded, and the RFCs are huge. Re-read [.docs/README.md](.docs/README.md) before consulting an RFC; then `grep` to the relevant section — never read a whole RFC, scan the folder, or open RFCs speculatively. Cite as `rfc<NNNN> §<section>`.
