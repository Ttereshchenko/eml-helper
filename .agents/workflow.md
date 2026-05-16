# Workflow Instructions

- When a new feature is being implemented, ask: `should it be enabled by default or always enabled`
- As part of implementing a new feature, update `README.md`
- As part of implementing a new feature (or meaningfully extending an existing one), update the `<description>` block in `src/main/resources/META-INF/plugin.xml` so the JetBrains Marketplace listing reflects the new capability. A one-line `<li>` under the existing bullet list is enough; keep the tone consistent with the surrounding entries.
- Update `AGENTS.md` or the relevant `.agents/` sub-file if project-level guidance changes
- Write test cases for each new feature and update them if behavior changes
- Edit `CHANGELOG.md` for user-visible changes (test-only changes do not need a changelog entry)
