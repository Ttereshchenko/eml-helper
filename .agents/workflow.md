# Workflow Instructions

- When a new feature is being implemented, ask: `should it be enabled by default or always enabled`
- As part of implementing a new feature, update `README.md`
- As part of implementing a new feature (or meaningfully extending an existing one), update the `<description>` block in `src/main/resources/META-INF/plugin.xml` so the JetBrains Marketplace listing reflects the new capability. A one-line `<li>` under the existing bullet list is enough; keep the tone consistent with the surrounding entries.
- Update `AGENTS.md` or the relevant `.agents/` sub-file if project-level guidance changes
- Cover every feature and defect fix with tests — see [testing.md](testing.md) for the test & sample-EML rules
- Edit `CHANGELOG.md` for user-visible changes (test-only changes do not need a changelog entry). Describe the change in user-facing terms — what the user now sees or can do — not the implementation. Name the visible features/behaviors, not classes, methods, or internal mechanics. E.g. "MIME-part folding, header highlighting, and the attachment gutter icon no longer disappear while the IDE is indexing (\"dumb mode\")." — not "`FoldingBuilder` now implements `DumbAware`".
