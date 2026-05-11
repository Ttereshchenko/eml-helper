---
description: Run plugin verifier and structure the report
---
Run `./gradlew verifyPlugin` and summarize results from
`build/reports/pluginVerifier/`. Group findings by target IDE version and
severity (errors, warnings, internal API usage). If clean, say so in one line.
Do not propose fixes unless asked.