# Step 4 — The machine runs the tests (CI) (~45 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-04/) — struggle first*

Your tests only protect you when you remember to run them. A CI pipeline runs them on every push
— GitHub Actions checks out your code, installs Java, runs `./gradlew build`, and reports green
or red on the pull request. You'll make it go red on purpose, then stop a bad merge with it.

**New concepts:** GitHub Actions workflow YAML, branch protection.

---

Stay at the **root of your fork** for this step (not inside `fx-exchange/`):
```bash
git switch main && git pull
git switch -c step-04
```

### 1. Create the workflow

GitHub Actions only reads workflows from the **repo root**. Your build lives two levels down, in
`fx-exchange/fx-app-spring/` — so create `.github/workflows/ci.yml` at your fork's true root
(beside `README.md` and `fx-exchange/`, not inside either):

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
        with:
          build-root-directory: fx-exchange/fx-app-spring
      - name: Build and test
        run: ./gradlew build
        working-directory: fx-exchange/fx-app-spring
```

- `working-directory:` — where `./gradlew` actually runs. Miss it and the step fails with "no
  such file or directory".
- `build-root-directory:` on `setup-gradle` — tells it which build to cache dependencies for.
- `build`, not `test` — it runs `check` and assembles the jar, catching things `test` alone
  would miss.

### 2. Push it and watch the first run

```bash
git add .github/workflows/ci.yml
git commit -m "ci: verify on every push and PR"
git push -u origin step-04
```

Open a PR from `step-04` into `main`, then the **Actions** tab. It should go green.

### 3. Go red on purpose

```bash
git switch -c break-a-test
```

Break an assertion in `com.fx.core.ConversionServiceTest` (expect `999.99` instead of `110.0`),
push, open a PR. A red X lands within a minute — click through to the failing line and confirm it
names your broken assertion. That's the safety net catching a fault you invented.

### 4. Make red mean "you cannot merge"

**Settings → Branches → Add branch ruleset for `main`** → require a pull request, and require the
status check **`build`** to pass before merging.

Back on the red PR, **Merge** is now greyed out. Fix the assertion, push, watch it flip green and
Merge light up. Delete the throwaway branch — you don't need to merge it.

**Checkpoint.** Actions tab shows a red run followed by a green run. Branch protection on `main`
requires `build`. Your real `step-04` PR (the one adding `ci.yml`) is green and mergeable.

### 5. Ship it

Merge the real `step-04` PR into `main`, then:
```bash
git switch main
git pull
```

<details>
<summary>Stuck?</summary>

- **"./gradlew: no such file or directory"** — missing `working-directory:`, or the workflow file
  itself isn't at your fork's true root.
- **"Permission denied" on `./gradlew`** — the wrapper lost its executable bit in git:
  `git update-index --chmod=+x fx-exchange/fx-app-spring/gradlew`.
- **The cache never hits** — `build-root-directory` is missing or points at the repo root.
- **"Java not found"** — `setup-java` must run *before* the Gradle steps.
- **Red PR still shows a green Merge button** — the rule didn't save, the check name doesn't match
  the job (`build`), or you added the rule after opening the PR — push a new commit to re-evaluate.
</details>
