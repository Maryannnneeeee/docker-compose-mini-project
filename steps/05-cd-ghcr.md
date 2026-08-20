# Step 5 — From green build to blessed deploy (CD) (~45 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-05/) — struggle first*

CI (Step 4) proves your code isn't broken. **CD** — continuous *delivery* — takes that proven
build one step further: it uploads the image somewhere other machines can get it, and stops just
short of putting it live until a person says go. You're not building anything new — the image
already exists from Step 1; this step publishes it and adds that approval step.

**A few terms you'll meet below:**
- **Registry** — a place to store and share built images, the way GitHub stores code. You'll use
  **GHCR** (GitHub Container Registry) — one comes free with every GitHub account.
- **Tag** — a name that points at one specific image, like a label on a box. One image can carry
  several tags at once.
- **`needs:`** — by default, jobs in a workflow run independently of each other. `needs: <job>`
  makes one job wait for another, and skip entirely if that one failed.
- **Matrix** — one job definition, run several times with different inputs. Here: once per app,
  instead of writing the job out twice.
- **Environment** (a GitHub Actions feature, unrelated to `.env` files) — a named deployment
  target you set up in your repo's settings. You can attach rules to it — like requiring a
  specific person to click *Approve* before any job targeting it is allowed to continue.

---

Stay at the root of your fork:
```bash
git switch main && git pull
git switch -c step-05
```

### 1. Stop building twice

Once `cd.yml` exists, every merge to `main` would trigger **both** `ci.yml` and `cd.yml` — the
same build running twice. Split the work by trigger: **CI checks the pull request, CD ships
`main`.** Narrow the top of `ci.yml`:

```yaml
name: CI
on:
  pull_request:
```

Delete the `push:` lines. Leave the rest of `ci.yml` as-is.

### 1b. Have CI build the images too

CD is about to build and push two images from `main`. If a Dockerfile is actually broken, you
want to find that out on the pull request — not after the merge, once CD is the one falling over.
Add a build-only job to `ci.yml`. It's a **matrix** job: one job definition, run once per app:

```yaml
  images:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        app: [fx-app-spring, fx-monitor]
    steps:
      - uses: actions/checkout@v4
      - name: Build image
        run: docker build -t ${{ matrix.app }}:ci .
        working-directory: fx-exchange/${{ matrix.app }}
```

Notice what's missing: no registry login, no push. A pull request from a fork should never be
able to publish anything — this job only proves the image *builds*.

### 2. Create `.github/workflows/cd.yml`

A separate workflow file, triggered only by a push to `main`:

```yaml
name: CD
on:
  push:
    branches: [main]

# Every workflow run gets a temporary credential, GITHUB_TOKEN, created automatically.
# By default it can only read your repo — publishing a package needs this extra line.
permissions:
  contents: read
  packages: write
```

### 3. Job 1 — `build`

`cd.yml` is a separate workflow from `ci.yml` — it doesn't know the pull request's CI run
happened, so it re-runs the same build and test itself before publishing anything from this
commit:

```yaml
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

### 4. Job 2 — `publish`

`needs: build` — if the build above fails, this job never runs. It logs in to the registry using
the `GITHUB_TOKEN` from step 2 (the same idea as running `docker login` yourself), then builds
and pushes both images:

```yaml
  publish:
    needs: build
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        app: [fx-app-spring, fx-monitor]
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build and push
        working-directory: fx-exchange/${{ matrix.app }}
        run: |
          # GHCR rejects uppercase in the owner segment — lowercase it rather than trusting the org name.
          OWNER=$(echo "${{ github.repository_owner }}" | tr '[:upper:]' '[:lower:]')
          IMAGE="ghcr.io/$OWNER/${{ matrix.app }}"
          SHORT="${GITHUB_SHA::7}"
          docker build -t "$IMAGE:sha-$SHORT" -t "$IMAGE:latest" .
          docker push "$IMAGE:sha-$SHORT"
          docker push "$IMAGE:latest"
```

One image, two **tags**: `sha-<short>` names this exact commit and never gets reassigned — useful
for an unambiguous rollback later. `latest` gets reassigned to whatever was just pushed — handy,
but it means "latest" is a moving target, not a promise about *this* particular build.

### 5. Job 3 — `deploy`

`needs: publish`, and gated by `environment: staging` — the **environment** you'll set up in
step 6. The step itself is only a placeholder for a real deploy (which might SSH somewhere and
run `docker compose pull`, or call a hosting platform's API) — what matters here is the gate in
front of it:

```yaml
  deploy:
    needs: publish
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4
      - name: Deploy
        run: |
          OWNER=$(echo "${{ github.repository_owner }}" | tr '[:upper:]' '[:lower:]')
          for app in fx-app-spring fx-monitor; do
            echo "Would pull ghcr.io/$OWNER/$app:latest on the staging host"
          done
```

### 6. Configure the gate

**Settings → Environments → New environment**, name it `staging`, and add **yourself as a
required reviewer**. The YAML above only *names* `staging` — the actual rule ("someone must
approve") lives in these repo settings, and that rule is what makes the `deploy` job pause.

### 7. Push and watch it stop

Merge your `step-05` PR. On the resulting push to `main`, open the **Actions** tab: `build` runs,
`publish` builds and pushes both images, then `deploy` sits in **Waiting**, with a "Review pending
deployments" prompt. That pause is the entire point of the step.

### 8. Approve, then verify

Click **Approve**. Then open the repo's **Packages** tab (GitHub's name for a published image)
and confirm **two** packages appeared — `fx-app-spring` and `fx-monitor` — each carrying **both**
tags.

**Checkpoint.** A pull-request merge now runs CI only (no double build). A push to `main` runs
`build → publish → (paused) deploy`, stopping at the `staging` gate until you approve it. GHCR
shows both packages, each with two tags.

### 9. Ship it

```bash
git switch main
git pull
```

### Sharing the image with your team

You published to *your own* namespace, `ghcr.io/<owner>/fx-app-spring`. For someone else to pull
it:

- **Public (simplest).** **Packages → fx-app-spring → Package settings → Change visibility →
  Public.** Anyone can now `docker pull ghcr.io/<owner>/fx-app-spring:latest` with no login.
- **Private.** Leave it private — anyone you add as a **repo collaborator** automatically gets
  read access, since the package is tied to this repo. Each of them runs `docker login ghcr.io`
  once, with their own personal access token.

In a real company the repo (and the image) would usually live under a shared **organisation**
account rather than one person's — `ghcr.io/<org>/fx-app-spring` — so team membership, not
individual invites, controls who can pull it.

<details>
<summary>Stuck?</summary>

- **`docker push` returns `403`** — missing `permissions: packages: write` at the top of `cd.yml`.
- **`docker push` returns `400 / invalid reference format`** — uppercase somewhere in the owner
  segment; lowercase it and retry.
- **`deploy` runs without pausing** — the `staging` environment has no required reviewer, or was
  never created. That's a repo-settings thing, not something the YAML controls.
- **Every merge still builds twice** — `ci.yml` still has `push: [main]`; remove it.
- **`publish` runs even though `build` failed** — `needs: build` is missing from the `publish` job.
- **Teammate's `docker pull` fails** — the package is still private and they aren't a collaborator.
</details>
