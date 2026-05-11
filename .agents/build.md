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
