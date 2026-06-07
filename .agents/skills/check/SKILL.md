---
name: check
description: Run code style check and all tests using Gradle
---

# Check Skill

When the user invokes this skill, you must execute the following command in the workspace root to run the code style checks and all automated tests:

```bash
./gradlew check
```

This command will run tests, Checkstyle, and Spotless formatting verification. Wait for the command to finish and report the results back to the user.
