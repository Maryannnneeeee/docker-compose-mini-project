# Step 7 — The machine runs the tests (CI) (~45 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-06-09.html) · [overview & cheatsheet](../decks/overview-steps-06-09.html) · [answer](../solutions/step-07/) — struggle first*

### Why we're doing this

You have three altitudes of tests. They protect you exactly as often as you remember to run
them — which, honestly, is "before the interesting commits and not the boring ones". That gap
is where broken code reaches `main`. A CI pipeline closes it: no human runs the full suite
before every push, but a robot does, cheerfully, forever.

The moment you push, GitHub Actions receives the event, spins up a fresh Ubuntu VM, checks out
your code, installs Java, runs `./gradlew build` — all three altitudes, including the Testcontainers
integration test, because `ubuntu-latest` ships a running Docker daemon — and reports green or
red back to the pull request. The value isn't the green. **The value is the red.** A pipeline
that has never gone red has never caught anything, so you'll make it go red on purpose and
watch it stop a bad merge.

**Skills you're building**
- YAML workflow structure: `on:`, `jobs:`, `steps:`, `uses:` vs `run:`
- Pointing a workflow at a subfolder of a workspace with `defaults.run.working-directory`
- `actions/checkout@v4` + `actions/setup-java@v4` + `gradle/actions/setup-gradle@v4`, pointed at the right build
- Reading the Actions tab: the job graph, step logs, the first `[ERROR]` line
- Turning a deliberate failure into branch protection nobody can click past

### What you're producing

A `.github/workflows/ci.yml` at the **repo root** with two parallel jobs (`build` and `lint`);
one Actions run that goes red because you asked it to; and a branch-protection rule that greys
out the merge button until `build` is green.

---

### Step-by-step

**0. Branch first.** Clean `main`, new branch:

```bash
cd fx-exchange
git switch main && git pull
git switch -c step-07
```

**1. Create the workflow — and mind where it lives.** GitHub Actions only reads workflows from
the **repo root**, never from a subfolder. But your Gradle build isn't at the root — it's in
`fx-app-spring/`, and there is a second one in `fx-orchestrator/`. So the file goes at the root
and *tells every step* which app to run in:

Create `.github/workflows/ci.yml` at the root of `fx-exchange` (beside `docker-compose.yml`,
**not** inside `fx-app-spring/`):

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
          build-root-directory: fx-app-spring   # where the build lives, not the repo root
      - name: Build and test (unit + slice + Testcontainers IT)
        run: ./gradlew build
        working-directory: fx-app-spring
```

Two lines carry the whole workspace nuance:

- `working-directory: fx-app-spring` — so `./gradlew` runs where `build.gradle` and the wrapper
  are. Leave it out and the step fails with "no such file or directory: ./gradlew", because the
  root has none.
- `build-root-directory: fx-app-spring` — `setup-gradle` caches Gradle's dependency cache and
  build cache between runs, and it needs to know which build to key on. Point it at the root
  (there is no build file there) and caching silently does nothing.

And note the verb: `build`, not `test`. That one word is what makes CI run **all three
altitudes** — `build` runs `check`, and in Step 6 you hung `integrationTest` off `check`.
`ubuntu-latest` has Docker, so the container boots with zero extra wiring.

**2. Push it and watch the first run.**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: verify on every push and PR"
git push -u origin step-07
```

Open a PR from `step-07` into `main`, then open the **Actions** tab. The run should go green
because your suite is green. Click into the `build` job, expand *Build and test*, and confirm the
Gradle output matches what you see locally — **including the Testcontainers lines pulling and
starting `mysql:8`**, and the two count lines your `afterSuite` block prints. Your IT runs in CI
for free.

> **There's another way, and you should be able to say why you didn't use it.** GitHub Actions
> can start a MySQL *service container* alongside the job (a `services:` block). Both work. We use
> Testcontainers because the **test owns its database**, so the *same* `./gradlew build` behaves
> identically on your laptop and on CI — one source of truth, instead of a CI-only MySQL block you
> have to keep in sync with your local setup.

**3. Cover the second app — with a matrix, not a copy-paste.** `fx-orchestrator` is a Gradle
build too, and right now CI ignores it entirely: you could break it and merge. The wrong fix is a
duplicated job. The right one is a **matrix**, which runs the same steps once per value:

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      # Two independent Gradle builds, one per Java app. They fail and cache separately.
      fail-fast: false
      matrix:
        app: [fx-app-spring, fx-orchestrator]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
        with:
          build-root-directory: ${{ matrix.app }}
      - name: Build and test
        run: ./gradlew build
        working-directory: ${{ matrix.app }}
```

Push. Refresh Actions and you'll see **two boxes side by side** — `build (fx-app-spring)` and
`build (fx-orchestrator)` — because matrix legs run in parallel by default. (If one needed the
other to finish first it would say `needs:`. Neither does, so they race, and your PR is ready
sooner.)

> `fail-fast: false` is deliberate. The default cancels every other leg the moment one fails,
> which hides a second, unrelated breakage behind the first. You want both answers on one run.

**4. Now earn the pipeline's keep — go red on purpose.** From a scratch branch, break one
assertion in the **api** unit test (the one you wrote in Step 6, `com.fx.api.ConversionServiceTest`
— not the older `com.fx.core` one):

```bash
git switch -c break-a-test
```

Change the converted-amount assertion so it *must* fail — expect `999.99` instead of `133.55`:

```java
assertEquals(999.99, result.converted(), 1e-9);   // deliberately wrong
```

Push, open a PR. Within a minute a red X lands on it. Click through: expand the failing step,
find the first failure line, and confirm it names the wrong-assertion test with
`expected: <999.99> but was: <133.55>`. **That is the safety net catching a fault you invented.**

**5. Make red mean "you cannot merge".** A red check you can click past is decoration. In the repo:
**Settings → Branches → Add branch ruleset (or rule) for `main`** → require a pull request, and
require the status check **`build`** to pass before merging. Save.

Now go back to the red PR: the **Merge** button is greyed out. "Please don't merge red" just
became physics, not a plea. Fix the assertion back to `133.55`, push, watch the check flip green,
and confirm Merge lights up. You don't have to merge this throwaway branch — delete it — but you
have proved the gate holds.

**Checkpoint.** Your repo's Actions tab shows a **red run followed by a green run**. Branch
protection on `main` requires `build`, and a red PR cannot be merged. Your real `step-07`
PR — the one that adds `ci.yml` — is green and mergeable.

**6. Ship it.** Merge the real `step-07` PR (not the throwaway) into `main`, then:

```bash
git switch main
git pull
```

> `gradle/actions/setup-gradle` restores Gradle's dependency and build caches between runs,
> turning a three-minute dependency download into a forty-second cache hit, and it annotates the
> PR with a build summary. Be able to say what it earns you in the PR body.

<details>
<summary>Stuck?</summary>

**"./gradlew: no such file or directory".** The step is running at the repo root. Add
`working-directory:` to the `run:` step, or the workflow file is inside `fx-app-spring/` instead
of at the root — it must be at `fx-exchange/.github/workflows/`.

**"Permission denied" on `./gradlew`.** The wrapper script lost its executable bit in git. Fix it
with `git update-index --chmod=+x fx-app-spring/gradlew` and commit.

**The cache never hits.** `build-root-directory` is missing or points at the repo root, where
there is no build file.

**"Java not found."** `setup-java` must run *before* the Gradle steps — order matters in `steps:`.

**The red PR still shows a green "Merge" button.** The branch-protection rule didn't save, the
required check name doesn't match the job (`build`), or you added the rule after opening the PR —
push a new commit to re-evaluate.

**IT is skipped in CI.** Unlikely — `ubuntu-latest` has Docker — but check the log for
`integrationTest: 0 tests`. If so, your `testcontainers.version` pin from Step 6 didn't get
committed.
</details>
