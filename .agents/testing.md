# Testing Instructions

- Every feature and every defect fix must be covered by test(s). For a defect fix,
  add a test that fails on the old behavior (reproduces the bug) and passes with the
  fix, so the regression stays closed.
- Follow the existing test conventions: tests extend `BasePlatformTestCase`, method
  names start with `test`, and live under `src/test/java/com/github/ttereshchenko/mailkit/`.
  Copy from `EmlLexerTest` or `EmlFoldingBuilderTest`.
- Alongside the test, create a sample EML file holding content that exercises the
  feature/fix, so it can be opened in the IDE for manual verification. Put it under
  `src/test/resources/samples/eml/` (use the matching sub-folder, e.g. `edge/`).
- Name the EML file after the feature/fix in `snake_case` so it is easy to navigate —
  the name should describe what the file demonstrates, e.g. `rfc2047_subject.eml`,
  `nested_rfc822_crlf.eml`. Avoid generic names like `1.eml` for new samples.
- Reference the sample from the test that consumes it, so the manual-verification file
  and the automated coverage stay linked.