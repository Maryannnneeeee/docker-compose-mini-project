# Step 1 — Put it in a box (~65 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-01/) — struggle first*

### Why we're doing this

Your API works. It works on *your* laptop, with *your* JDK 21, *your* Gradle, *your* MySQL on
*your* port 3306 with the seed loaded exactly right. None of that is true of the machine next
to you, and none of it is true of production.

Here you stop shipping instructions ("install Java 21, then MySQL, then…") and start shipping
the machine. By the end the whole exchange — API, database, a web front end and an upstream
feed — comes up with one command on a laptop that has none of those things installed.

**Skills you're building**
- Reading and writing a `Dockerfile` instruction by instruction, not pasting one
- Knowing what a *layer* is, and why the order of instructions decides your rebuild time
- Publishing ports, injecting configuration, and mounting storage from outside the container
- Diagnosing the single most common container mistake: *whose* `localhost`?

### What you're producing

A `fx-exchange/` **workspace folder** in your fork, with `fx-app-spring/` inside it, a `Dockerfile`
at that project's root, and an image you can run without a JDK or Gradle anywhere in sight.

---

### Step-by-step

> **You do not need MySQL for this task.** Stop it if it's running — this step is about packaging,
> and the database joins the stack in Step 2. Everything below is checked without one.

**0. First order of business: your own workspace.**

You are not adding to one project — you are assembling a **system** of three, which by
the end will be your API, a web front end and an upstream feed, all running together. Three
applications means three folders, side by side. None of them lives inside another.

**Every student works in their own fork.** If you haven't already, fork this repository on
GitHub into your own account, then open it — in a Codespace, or cloned locally. You already have
git, a remote called `origin` pointing at your fork, and a `main` branch holding this course's
`README.md`, `given/`, `start/` and `steps/`. You are not creating a repository here — you are
adding a folder to the one you already have.

From the root of your fork:

```bash
mkdir fx-exchange
cp -R start/fx-app-spring fx-exchange/
cp start/README.md fx-exchange/README.md
cd fx-exchange
```

Open the `README.md` you just copied in and put your **name in it** — it already has a
`**Name:**` line waiting. This is your workspace, and for the rest of the week your name on it is
how anyone knows whose stack they are looking at.

Commit and push that starting point to `main` **before you change anything**:

```bash
git add fx-exchange
git commit -m "chore: fx-app-spring and README"
git push
```

Check GitHub: `main` on your fork now also holds `fx-exchange/`, with `fx-app-spring/` and your
README inside it. That is your baseline — the known-good state you can always come back to.

**Now branch.** Everything you build rides a branch and lands on `main` through a pull
request, one branch per exercise:

```bash
git switch -c step-01
```

> Working directly on `main` is a habit that costs you nothing while you are alone in
> the repo, and costs you a great deal once you are not. Build the habit while
> it is free.

Where you are heading, so the shape makes sense from the start:

```
your-fork/                     ← repo root — given/, start/, steps/, decks/ live here, untouched
└── fx-exchange/                ← your workspace — everything below lives here
    ├── README.md
    ├── docker-compose.yml       ← Step 2 puts this here
    ├── fx-app-spring/           ← your API. Its Dockerfile goes at ITS root.
    ├── fx-monitor/              ← Step 4 adds this, beside — not inside
    └── fx-orchestrator/         ← Step 5 adds this, also beside
```

> `fx-app-spring/` is the application as it stands — nothing is lost by working in a fresh
> workspace folder. Everything below happens **inside `fx-exchange/`** unless a step says
> otherwise — and from Step 7 on, a couple of things happen one level up, at the root of your
> fork. Those steps will tell you when.

**1. Build the jar the old way, once, so you know what goes in the box.**

```bash
cd fx-app-spring
./gradlew clean bootJar
ls -lh build/libs/*.jar
java -jar build/libs/fx-app-spring-0.1.0-SNAPSHOT.jar
```

**It starts.** With no database anywhere. Check it in a second terminal:

```bash
curl localhost:8080/health          # {"status":"UP"}
curl localhost:8080/api/health/db   # {"status":"DOWN","hint":"Is MySQL running..."}
```

Two different answers about the same app, and both are true. The connection pool is **lazy** —
Spring doesn't dial the database until something actually asks for data, so the app starts
perfectly happily without one. The app's own `/api/health/db` probe is the thing that tells you the truth.

> Bank that: **"the process is up" and "the app works" are different claims.** It will cost
> somebody a confused hour later, when a container is green and broken at the same time.

Stop it with Ctrl-C. That jar already contains Tomcat — Boot's plugin put it there.
**It is the only thing the container actually needs to run.**

**2. The naive Dockerfile — write it, run it, and feel what's wrong with it.**

Create `Dockerfile` **inside `fx-app-spring/`** — each app owns its own, at the root of its own
folder. Run every `docker build` in this task from inside `fx-app-spring/` too, so the `.` context
is that project and nothing else:

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/fx-app-spring-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t fx-app-spring:0.1 .
docker images fx-app-spring
docker run fx-app-spring:0.1
```

It boots. Now open a second terminal and try to reach it:

```bash
curl localhost:8080/health
```

**Nothing.** The app is listening on port 8080 *inside its own network namespace*. Nothing on
your laptop is. A container does not get your machine's ports by existing — you have to say so.

Stop it (Ctrl-C) and run it again, publishing the port:

```bash
docker run -p 8080:8080 fx-app-spring:0.1
curl localhost:8080/health          # {"status":"UP"}
curl -i localhost:8080/             # 404 — alive, just nothing mapped there
```

`-p 8080:8080` — **left is your laptop, right is inside the container.** They are two different
numbers that happen to match here. They will not match later.

**3. Now the failure worth having. Ask it for data:**

```bash
curl -i localhost:8080/api/rates
```

A `500`, and a deliberately uninformative body — that's the error hardening already in the app
doing its job. The real answer is in the *container's* log:

```bash
docker logs <container-id> | grep -iE "connection|jdbc"
```

`CannotGetJdbcConnectionException` … `Connection refused`. And your own probe agrees:

```bash
curl localhost:8080/api/health/db
```

So far, so unsurprising — there is no MySQL running. But look at **where** it went looking. The
app's `application.properties` says `jdbc:mysql://localhost:3306/fxdb`, and this is the part that
catches everybody:

> **`localhost` inside a container is the container.** Not your laptop. Even if you started MySQL
> right now, on this machine, on 3306, this container still would not find it — it is searching
> its own four walls. The container has its own network namespace, and `localhost` means "me".

Sit with that, because it is the single most common container mistake and you will make it again
in a different costume in about an hour.

**We are not going to fix it here.** The cheap fix is to point the app back out at your laptop,
which just re-creates the problem the whole day is about: an app that only runs next to something
you installed by hand. Step 2 does it properly — the database gets its own container and the two
talk over a network they share.

*(Optional, if you happen to have a local MySQL running and want to prove the diagnosis:
`-e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/fxdb` and the 500 becomes data.
`host.docker.internal` is Docker Desktop's name for "the machine hosting me"; on plain Linux you
need `--add-host=host.docker.internal:host-gateway`. Skip it without guilt — Step 2 supersedes it.)*

**4. Go multi-stage.** The image you just built has a problem you can't see: it only works
because *you* ran `./gradlew bootJar` first. Someone cloning your repo gets a build failure. And a
`.jar` filename is pinned in the Dockerfile, so the version bump breaks it.

Replace the whole file:

```dockerfile
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
RUN gradle --no-daemon dependencies --configuration runtimeClasspath > /dev/null
COPY src ./src
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t fx-app-spring:1.0 .
docker images fx-app-spring
```

Two stages, one image out. The `build` stage carries Gradle, a full JDK, your source and the
whole dependency cache — **and none of it ships.** Only `app.jar` crosses the `COPY --from=build`
line. Note the `*.jar` glob: no version pinned any more.

> The glob is only unambiguous because `build.gradle` says `tasks.named('jar') { enabled = false }`.
> Left on, Gradle writes a second, non-executable `-plain.jar` next to the real one and `*.jar`
> matches two files.

`EXPOSE 8080` documents the port. It does **not** publish it — `-p` still does that.

**5. Prove the layer cache, because it is the reason those two `COPY` lines are in that order.**

Rebuild with nothing changed:

```bash
docker build -t fx-app-spring:1.0 .
```

Every step says `CACHED`. Now touch a source file and rebuild:

```bash
touch src/main/java/com/fx/api/web/RateController.java
docker build -t fx-app-spring:1.0 .
```

Watch which steps re-run. `COPY build.gradle settings.gradle` and the `dependencies` step stay
**CACHED**; everything from `COPY src` down re-runs.

Now change `build.gradle` (bump `description`), rebuild, and watch the `dependencies` step re-run
too — it sits above a layer whose input changed, so its cache is invalid.

> **Docker re-runs a layer when the files it copies have changed, and every layer after it.**
> Dependencies change rarely; source changes constantly. That is the entire reason `build.gradle`
> is copied on its own, before `src`. Get this order wrong and every one-character fix
> re-downloads the internet.

**6. Add a `.dockerignore`.** Before Docker runs a single instruction it uploads the *build
context* — everything in the folder — to the daemon. Check what you're sending:

```bash
du -sh .
```

Most of that is `build/`, `.gradle/` and `.git/`. Create `.dockerignore`:

```
build/
.gradle/
.git/
.github/
.idea/
*.iml
docs/
README.md
```

Rebuild and watch the `transferring context` line shrink. This is not only speed: whatever lands
in the build context can end up in an image layer, so keeping secrets and junk out of it is a
security habit, not a tidiness one.

**7. Change the app's behaviour without rebuilding it.** The image is fixed; its *configuration*
is not. Move the app to a different port inside the container:

```bash
docker run -p 8080:9090 -e SERVER_PORT=9090 fx-app-spring:1.0
curl localhost:8080/health
```

Still answers on 8080 — because you published `8080:9090`. Check the log: `Tomcat started on
port 9090`. **Same image, different behaviour, nothing rebuilt.** Spring's relaxed binding turns
`SERVER_PORT` into `server.port`, and the environment beats the properties file. That is
config-not-code, one layer further out than `application.properties` — and it is exactly the
mechanism Step 2 uses to hand the app a database address.

**8. Mount storage from outside.** Containers are disposable — anything written inside one dies
with it. Run with a **named volume** for logs and a **read-only bind mount** of `ops/`:

```bash
docker run -p 8080:8080 \
  -v fx-logs:/app/logs \
  -v "$PWD/ops:/app/ops:ro" \
  fx-app-spring:1.0
```

Two different things, deliberately in one command:
- `fx-logs:/app/logs` — a **named volume**. Docker owns it, it lives outside the container, it
  survives `docker rm`. Confirm with `docker volume ls`.
- `"$PWD/ops:/app/ops:ro"` — a **bind mount**. A real directory on your laptop, appearing inside
  the container, **read-only**. Change a file on the host and the container sees it immediately.

Prove both:

```bash
docker exec $(docker ps -q --filter ancestor=fx-app-spring:1.0) ls /app/ops
docker exec $(docker ps -q --filter ancestor=fx-app-spring:1.0) touch /app/ops/nope
docker volume ls | grep fx-logs
```

The `touch` is refused: `Read-only file system`. That `:ro` is doing real work — and mounting
your seed data read-only is how you make sure a container can never corrupt the thing it was
given.

**9. Learn the everyday commands.** With the container running, in another terminal:

```bash
docker ps                     # what's running
docker ps -a                  # ...including what died
docker logs <container-id>    # its stdout, after the fact
docker exec -it <id> sh       # a shell INSIDE it — try `ls /app`, `ps`, then `exit`
docker stop <id>
docker rm <id>
docker images                 # what you've built
docker rmi fx-app-spring:0.1  # bin the naive one
```

Inside that `exec` shell, run `ps`. You will see about two processes. **That is the container:**
not a machine, just your process with its own view of the world.

**Checkpoint.** No database anywhere. From a clean `docker run -p 8080:8080`:

```bash
curl -s localhost:8080/health          # {"status":"UP"}
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/       # 404 — alive, nothing mapped
curl -s localhost:8080/api/health/db   # {"status":"DOWN", ...}
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/rates   # 500
```

That combination **is** the pass mark: your app is running inside a container, on a machine with
no JDK, no Gradle and no MySQL involved in running it — and it is honest about the one thing it
still can't reach. `docker images` shows the multi-stage image is markedly smaller than the
single-stage one, and a rebuild after touching a source file leaves `dependency:go-offline`
`CACHED`.

The 10 rates and EUR/USD 1.0818 arrive in **Step 2**, when the database joins the stack.

**10. Ship it.** The work is done and proven — now land it on `main`, the same way every
change lands on `main` for the rest of this course.

```bash
git add -A
git commit -m "feat: containerise fx-app-spring"
git push -u origin step-01
```

Open a pull request from `step-01` into `main` on GitHub, and merge it. Then bring your local
`main` back in line before you start the next task:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR. Nobody commits straight to `main` —
> not while you are alone in the repo, and least of all when you are not.

<details>
<summary>Stuck?</summary>

**`COPY build/libs/...` says "not found".** You're on the naive Dockerfile and haven't run
`./gradlew bootJar`, or `build/` is in `.dockerignore` (it should be — that's why step 4 stops
needing it).

**`/api/rates` returns 500.** Correct — there is no database yet, and step 3 explains why the
container couldn't reach one even if you started it. Step 2 fixes it. `/health` and
`/api/health/db` are your checkpoints here.

**`port is already allocated`.** Something already holds 8080 — an old container
(`docker ps`), or your app still running in IntelliJ. Stop it, or publish on another host port:
`-p 8090:8080`.

**Everything says CACHED even though I changed a file.** You changed a file that `.dockerignore`
excludes, so the context Docker sees is identical.

**Ctrl-C doesn't stop it.** You started it detached (`-d`). `docker ps` then `docker stop <id>`.
</details>
