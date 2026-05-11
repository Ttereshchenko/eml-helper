# PR Checks — Verification Steps

## 1. Happy-path (PR trigger)
1. Create a feature branch and push at least one commit.
2. Open a Pull Request against `main`.
3. In the PR's **Checks** tab confirm three jobs appear:
   - `Resolve PR SHA`
   - `Check (tests · Checkstyle · Spotless · JaCoCo)`
   - `Verify Plugin (2025.3 · 2026.1)`
4. Wait for all three to turn green.
5. Download the `jacoco-report`, `check-reports`, and `plugin-verifier-report` artifacts
   and spot-check the HTML output.

## 2. Comment trigger (`/verify`)
1. On the open PR, add a comment containing `/verify`.
2. Confirm the workflow triggers a new run (visible in the **Actions** tab).
3. After the run completes, confirm a summary comment appears on the PR
   with a pass/fail table and a link to the run logs.

## 3. Failure path — Checkstyle
1. In the feature branch, add a star import to any `.java` file (e.g. `import java.util.*;`).
2. Push the commit.
3. Confirm the `check` job fails and the PR check turns red.
4. Revert the import, push again — confirm the check goes green.

## 4. Failure path — Spotless
1. Add trailing whitespace to any `.java` file.
2. Push — confirm `check` fails.
3. Run `./gradlew spotlessApply` locally, commit the fix, push — confirm it goes green.

## 5. Concurrency (cancel-in-progress)
1. Push two commits to the feature branch in quick succession.
2. In the **Actions** tab confirm the first run is cancelled and only the second completes.
