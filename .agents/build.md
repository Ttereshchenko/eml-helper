# Build Commands

```bash
./gradlew build          # Compile and test
./gradlew compileJava    # Compile only
./gradlew test           # Run tests
./gradlew build -x test  # Build without tests
./gradlew clean          # Clean build output
./gradlew runIde         # Launch sandboxed IDE with plugin loaded
./gradlew buildPlugin    # Package plugin as a .zip artifact (output: build/distributions/)
./gradlew verifyPlugin   # Run IntelliJ plugin verifier
./gradlew check          # Run tests + Checkstyle + Spotless
./gradlew spotlessApply  # Auto-fix formatting and unused imports
./gradlew spotlessCheck  # Verify formatting without modifying files
```

## Build rules

- `build.gradle` is **Groovy**, not Kotlin DSL. Do not use `tasks.withType<T> { ... }` or `listOf(...)` — use `tasks.withType(T).configureEach { ... }` and `['a', 'b']` list literals.
- `JavaCompile` runs with `-Xlint:deprecation -Werror`, so any usage of a `@Deprecated` API fails the build. Migrate the call site to the documented replacement rather than adding `@SuppressWarnings("deprecation")`.
