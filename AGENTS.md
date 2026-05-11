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

## Sub-files (re-read when the trigger applies)

- [.agents/project.md](.agents/project.md) — tech stack, JDK, packages, test layout. Re-read before any architecture or dependency decision.
- [.agents/build.md](.agents/build.md) — gradle commands. Re-read before running, building, or packaging.
- [.agents/workflow.md](.agents/workflow.md) — process rules. Re-read before adding a feature or touching `README.md` / `CHANGELOG.md`.
- [.agents/code-style.md](.agents/code-style.md) — Java conventions. Re-read before writing or editing any `.java` file.
- [.agents/agent-prompt.md](.agents/agent-prompt.md) — answer-format rules. The "list which files you read" rule above is duplicated here; keep the two in sync.
