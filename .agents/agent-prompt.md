## Agent answer extension

- **CRITICAL PRE-FLIGHT**: At the very start of any task involving code navigation or modification, you MUST explicitly invoke the Serena MCP tool `initial_instructions`. Do not use `grep_search`, `view_file`, or edit files until Serena is initialized.
- Read this file yourself (do not rely on a subagent) before writing any response
- At the end of every answer, list which `.agents/*.md` files were applied
  Example: `Respected: project.md, agent-prompt.md`