# Code Style

- Do not use asterisk (`*`) wildcard imports; always import types explicitly
- do not use fully qualified class names in code (e.g. `java.nio.charset.CodingErrorAction`); always import them instead
- use methods from java.util.Objects for null checks
- use `var` for value declaration
- use a descriptive named throwaway (e.g. `ignored`, `event`) for unused catch parameters and unused lambda parameters — the project targets JDK 21 bytecode, so `_` (Java 22+) is not available
- prefer switch expressions with pattern matching over chains of `instanceof` checks
- use record patterns to destructure records in `instanceof` and `switch`
- use full words for variable names (no abbreviations like "m" for "matcher")
- class names must convey purpose — never placeholders like `My*`, `Foo`, `Bar`, `Stuff`, `Helper`, `Util` (the JetBrains plugin templates ship `MyState` / `MyService`; rename on first edit). A nested class can be short when the outer class supplies the domain — e.g., a `PersistentStateComponent`'s inner state class is `State`, not `MyState`
- don't keep unused import
- use methods from `java.util.Objects` for `null` checks
- when reading the message of a `com.intellij.openapi.options.ConfigurationException`, call `getLocalizedMessage()` rather than `getMessage()` — the latter is deprecated on `ConfigurationException` and breaks the build under `-Werror`
- IntelliJ extensions that extend `PossiblyDumbAware` (`FoldingBuilder`, `Annotator`, `LineMarkerProvider`, etc.) must implement `DumbAware` when their logic only walks PSI / reads settings and never queries indices — otherwise the platform skips them during indexing and their decorations disappear in dumb mode. `SyntaxHighlighter` is exempt: it is not `PossiblyDumbAware` and always runs.

Rules above are enforced by Checkstyle (`config/checkstyle/checkstyle.xml`) and Spotless (Palantir Java Format) where possible. `./gradlew build` fails on violations. Run `./gradlew spotlessApply` to auto-fix formatting. Rules that no static analyser can enforce (use `Objects` for null checks, prefer `var`, prefer switch expressions and record patterns, use `_` for unused parameters, descriptive class names) remain reviewer responsibility.