# Step 8 — Supply chain & the Definition of Done (~45 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-06-09.html) · [overview & cheatsheet](../decks/overview-steps-06-09.html) · [answer](../solutions/step-08/) — struggle first*

### Why we're doing this

Your `build.gradle` names about half a dozen dependencies. Your built jar contains dozens. Every one
of those "transitive" dependencies is code you ship, execute with full trust, and never wrote —
written by people you'll never meet. When one of them turns out to have a remote-code-execution
bug — and one eventually will — you need to know three things fast: that you ship it, which of
*your* direct dependencies pulled it in, and whether a patched version exists. That inventory is
your software supply chain. Log4Shell in 2021 was the morning every engineering org discovered
they didn't have one.

Then the honesty half. Automation and green ticks are only worth anything if someone reads them.
This afternoon you'll wire up two safety tools **and** deliberately turn off MySQL and Docker to
watch which of your green ticks are telling the truth — because a suite that quietly needs a
database will lie to you, and a build that silently skips its best test will lie to you louder.
Naming exactly what your green means is the whole point of a **Definition of Done**, which you'll
write down once and live by from then on.

**Skills you're building**
- Reading a dependency tree: indentation is who-pulled-whom
- Looking a dependency up in the NVD / GitHub advisories by artifact + version
- Turning on Dependabot, in the settings *and* as a committed config file
- The two honesty checks — MySQL off (the fast tier) and Docker off (the silent skip)
- Writing a five-point Definition of Done and running your own PR through it

### What you're producing

`docs/status.md` — a short status doc that names what's tested at which altitude, what CI
guards and doesn't, your Dependabot rule, and the **five-point Definition of Done**. Plus a
`.github/dependabot.yml` committed, Dependabot enabled, and a demonstrated honesty check.

---

### Step-by-step

**0. Branch first.**

```bash
cd fx-exchange
git switch main && git pull
git switch -c step-08
cd fx-app-spring
```

**1. Inventory what you ship.**

```bash
./gradlew dependencies --configuration runtimeClasspath
```

`runtimeClasspath` is the configuration that decides what ends up in the jar. Ask for
`testRuntimeClasspath` instead and you will see Testcontainers and JUnit too — real dependencies,
but ones that never ship.

Skim it. Count the dependencies — every entry, direct and indented. That number is your attack
surface: it's what actually ends up in the jar. Note it down; it goes in the status doc.

**2. Trace two strangers.** Pick two dependencies you've never heard of. In the tree, an
*indented* line is a transitive dependency and the line one level *less* indented is what pulled
it in. Write both down as "X → pulled in by Y". (Your validation starter alone drags in a
Jakarta EL implementation you never asked for — that's a fine one to trace.)

**3. Look one up.** Take one dependency and its exact version from the tree and search
[the NVD](https://nvd.nist.gov/vuln/search) or [GitHub Advisories](https://github.com/advisories)
for that artifact + version. Note any advisories. **"No known vulnerabilities" is a valid
result** — write it down; the skill is doing the lookup, not finding a scandal.

**4. Turn on Dependabot.** Repo → **Settings → Code security** → enable **Dependabot alerts** and
**Dependabot security updates**. From now on, when a patched version of anything you ship lands,
GitHub opens a PR for it automatically. (If corporate GitHub policy blocks Dependabot, note that
in the status doc and keep the manual lookup from step 3 — the *rule* is what matters, not the
button.)

**5. Commit the Dependabot config.** The switch in step 4 turns on *alerts*. A committed
`.github/dependabot.yml` is what tells Dependabot where your builds actually are — and with two
Gradle apps in subfolders, it will find nothing without it. Create it at the repo root:

```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /fx-app-spring
    schedule:
      interval: weekly
    open-pull-requests-limit: 5

  - package-ecosystem: gradle
    directory: /fx-orchestrator
    schedule:
      interval: weekly
    open-pull-requests-limit: 5

  # The workflows pin actions by major version (@v4). This keeps those moving too.
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly

  - package-ecosystem: docker
    directory: /fx-app-spring
    schedule:
      interval: weekly
  - package-ecosystem: docker
    directory: /fx-orchestrator
    schedule:
      interval: weekly
  - package-ecosystem: docker
    directory: /fx-monitor
    schedule:
      interval: weekly
```

Note what it covers beyond your Java dependencies: the **actions** your workflows pin and the
**base images** your Dockerfiles pull. `FROM eclipse-temurin:21-jre` is a supply-chain dependency
exactly like `liquibase-core` is, and it is the one people forget.

> **Why no vulnerability scanner job?** The OWASP dependency-check plugin is the obvious
> candidate, and since the NVD API rate-limit changes it needs an API key to be usable in CI —
> without one it either takes tens of minutes or fails outright. That is a real decision with a
> real trade-off: if you want it, get a free NVD API key, store it as a repository secret, add
> the `org.owasp.dependencycheck` plugin to `build.gradle`, and run it as a
> `continue-on-error: true` job on a **nightly schedule** against `main` — not on every PR, where
> a slow scan sits in the way of a one-line fix. Dependabot plus the manual lookup in step 3
> covers the same ground for now. Write your choice down in the status doc.

**6. Now the honesty checks — turn things off and read the tea leaves.** Two of them, and they
teach opposite lessons.

**6a. MySQL off — is the fast tier honest?** Stop your local MySQL (`brew services stop mysql`,
or however you started it). Leave Docker running. Then:

```bash
./gradlew test --rerun-tasks
```

Every test should still be green — `29 tests, 28 passed, 0 failed, 1 skipped`, exactly as in
Step 6. (`--rerun-tasks` because Gradle would otherwise report `UP-TO-DATE` and run nothing.) Units
and slices mock everything below them, so your local database being down is *irrelevant* to them.
If one goes red with a "Communications link failure", you've found a test that secretly reaches a
real database it shouldn't: mock the missing collaborator, or — if it genuinely needs a database
— rename it `*IT` so it belongs to `integrationTest`, not `test`.

**6b. Docker off — the silent skip, for real this time.** Now quit Docker Desktop entirely and
run the *full* command:

```bash
./gradlew build --rerun-tasks
```

Read it carefully:

```
integrationTest: 3 tests, 0 passed, 0 failed, 3 skipped
...
BUILD SUCCESSFUL
```

**BUILD SUCCESSFUL — with your best test skipped.** This is the Step-6 trap sprung in the wild:
`disabledWithoutDocker = true` skips the IT when Docker is gone, and the build stays green over
zero database coverage. Nobody warns you. The only defence is a human who reads the summary line
— which is precisely why the Definition of Done you're about to write says *"nothing wrongly
skipped"* in point 1. Restart Docker, run `build` again, confirm `0 skipped`.

> Bank the pairing: **MySQL off proves the fast tier doesn't lie about needing a database;
> Docker off proves a green `build` can still be hiding a skipped test.** Same command, two
> failure modes, both invisible unless you look.

**7. Write `docs/status.md`.** Short — three or four bullets a section. Create it with:

- **Tested at which altitude.** Unit: rounding, fees, unknown-pair throw. Slice: list, 404, valid
  POST, negative-amount → 400. Integration: real SQL on a real MySQL — `findLatest()` = 10,
  EUR/USD = 1.0818, unknown pair empty.
- **What CI guards.** `./gradlew build` on every push and PR, for both Gradle apps — all three altitudes, a real database
  included; branch protection blocks merging red.
- **What CI does *not* guard.** No full end-to-end run in CI: nothing there starts the app *and*
  a database *and* curls it as a user would — that's what `docker compose up` gives you locally,
  and it's in the Definition of Done below. No front end.
- **Dependabot rule.** One sentence your team will keep, e.g. *"Dependabot PRs reviewed within
  one working day; security PRs merged same day if CI is green."*

Then paste the **Definition of Done** into the same file. From here it is the checklist a
reviewer runs on your PR, and it is the bar for the project:

> A change is **done** only when **all** of these hold:
>
> 1. From `fx-app-spring/`, **`./gradlew build` is green** — unit, slice **and** integration, with
>    **nothing wrongly skipped** (read the summary line) — **and** `docker compose up` on a clean
>    machine serves `/api/rates` returning **10** rates.
> 2. **The new behaviour has tests at the right altitude**: a new endpoint → a slice test, happy
>    *and* failure path; a new calculation → a unit test with boundaries; new or changed SQL → an
>    `*IT`.
> 3. **A teammate reviewed the PR** — checked out the branch, ran `./gradlew build` themselves, and
>    approved.
> 4. **Merged to `main` only through that PR** — never a direct push.
> 5. **`main` is still green after the merge**, and a **fresh clone** builds and verifies.
>
> Not done: a failing or wrongly-skipped test · "works on my laptop" but not on a fresh clone ·
> code merged without review.

**Checkpoint.** `docs/status.md` holds the status **and** the five-point Definition of Done;
`.github/dependabot.yml` is committed and Dependabot alerts are on; and you have seen with your
own eyes that `./gradlew test` stays `29 tests / 0 failed / 1 skipped` with MySQL off, while
`./gradlew build` reports `3 skipped` — still "BUILD SUCCESSFUL" — with Docker off.

**8. Ship it.**

```bash
git add -A
git commit -m "chore: dependabot config; docs: status and Definition of Done"
git push -u origin step-08
```

Open the PR, wait for green, merge, then:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR — and from now on, every PR judged
> against the Definition of Done you just wrote. It's your rule; hold your own work to it first.

<details>
<summary>Stuck?</summary>

**`./gradlew test` fails with "Communications link failure" (MySQL off).** A test is pulling the
real `DataSource`. A `@WebMvcTest` slice shouldn't; if it does, something in the web layer grabs
the repo directly — add `@MockBean RateRepository` to the slice. Last resort:
`spring.autoconfigure.exclude=…DataSourceAutoConfiguration` in a test properties file.

**`./gradlew build` shows `3 skipped` and I didn't turn Docker off.** Then Docker isn't reachable, or
your `testcontainers.version` pin from Step 6 isn't in this branch. That's the whole lesson of
step 6b — a green build you didn't read.

**`./gradlew dependencies` prints a wall of text.** That's normal — dozens of lines is the point.
Indentation is a strict hierarchy: the immediate parent is one level less indented.

**Org policy blocks Dependabot.** Some corporate GitHub Enterprise setups do. Do the manual CVE
lookup, note the block in the status doc, and write the rule anyway.
</details>
