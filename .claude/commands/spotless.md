---
description: Apply Spotless and show what was reformatted
---
Run `./gradlew spotlessApply`, then `git diff --stat -- '*.java'`. If nothing
changed, say so. Do not commit.