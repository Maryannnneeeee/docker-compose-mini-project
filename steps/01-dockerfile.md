# Step 1 — Put it in a box (~65 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-01/) — struggle first*

You'll package `fx-app-spring` into a Docker image that runs without a JDK, Gradle, or MySQL
installed on the host.

**New concepts:** Dockerfile, image layers, multi-stage builds, port publishing, volumes vs.
bind mounts.

---

### 0. Set up your workspace

Fork this repo, then open it (Codespace or local clone). From the fork's root:

```bash
mkdir fx-exchange
cp -R start/fx-app-spring fx-exchange/
cp start/README.md fx-exchange/README.md
cd fx-exchange
```

Add your name to `fx-exchange/README.md`, then commit this baseline:

```bash
git add fx-exchange
git commit -m "chore: fx-app-spring and README"
git push
```

Branch for this step — every exercise gets its own branch, merged via PR:

```bash
git switch -c step-01
```

You'll end up with:
```
fx-exchange/
├── fx-app-spring/       ← your API — Dockerfile goes at its root
├── docker-compose.yml   (Step 2)
└── fx-monitor/          (Step 3)
```

### 1. Build the jar the old way

```bash
cd fx-app-spring
./gradlew clean bootJar
java -jar build/libs/fx-app-spring-0.1.0-SNAPSHOT.jar
```

In another terminal:
```bash
curl localhost:8080/health          # {"status":"UP"}
curl localhost:8080/api/health/db   # {"status":"DOWN", ...}
```

The app starts without a database — Spring's connection pool is **lazy**, it only connects when
something asks for data. "The process is up" and "the app works" are different claims.

Stop it (Ctrl-C). That jar already contains Tomcat — it's the only thing the container needs.

### 2. Write a naive Dockerfile

Create `Dockerfile` inside `fx-app-spring/` (each app owns one, at its own root):

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/fx-app-spring-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t fx-app-spring:0.1 .
docker run fx-app-spring:0.1
```

It boots, but `curl localhost:8080/health` from another terminal finds nothing — the app is
listening *inside the container's own network namespace*, not on your laptop.

Stop it, run it again publishing the port:
```bash
docker run -p 8080:8080 fx-app-spring:0.1
curl localhost:8080/health          # {"status":"UP"}
```

**`-p 8080:8080`** — left is your laptop's port, right is the container's. `EXPOSE` in a
Dockerfile only documents a port; `-p` is what actually publishes it.

### 3. See the database gap

```bash
curl -i localhost:8080/api/rates                          # 500
docker logs <container-id> | grep -iE "connection|jdbc"   # Connection refused
```

The app looks for `jdbc:mysql://localhost:3306/fxdb` — but **`localhost` inside a container means
the container itself**, not your laptop. There's no MySQL to find, even if one were running on
your machine. Step 2 fixes this by giving the database its own container on a shared network.

Stop the container.

### 4. Go multi-stage

A Dockerfile that only works because you happened to run `./gradlew bootJar` first is broken for
anyone else. Replace the file:

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
```

Two **stages**: `build` carries Gradle, a JDK and your source; only `app.jar` crosses into the
final image via `COPY --from=build`. The image now builds itself from source — nobody runs
`bootJar` by hand.

### 5. See the layer cache work

Rebuild with nothing changed — every step says `CACHED`. Now:

```bash
touch src/main/java/com/fx/api/web/RateController.java
docker build -t fx-app-spring:1.0 .
```

`COPY build.gradle settings.gradle` and the `dependencies` step stay cached; everything from
`COPY src` down re-runs. **Docker re-runs a layer when its inputs change, and every layer after
it** — that's why dependencies are resolved *before* `src` is copied: they change rarely, source
changes constantly. Get the order backwards and every one-line fix re-downloads the internet.

### 6. Add `.dockerignore`

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

Without it, Docker uploads your whole folder — `build/`, `.git/`, everything — as the **build
context** before it even starts building. Rebuild and watch the `transferring context` line
shrink. Anything in the context can end up in a layer, so this is a security habit too.

### 7. Configure without rebuilding

```bash
docker run -p 8080:9090 -e SERVER_PORT=9090 fx-app-spring:1.0
curl localhost:8080/health
```

Same image, different port — Spring turns the `SERVER_PORT` env var into `server.port`, and
environment variables beat `application.properties`. This is how Step 2 hands the app its
database address without rebuilding the image.

### 8. Mount storage from outside

Containers are disposable — anything written inside one is gone when it is.

```bash
docker run -p 8080:8080 \
  -v fx-logs:/app/logs \
  -v "$PWD/ops:/app/ops:ro" \
  fx-app-spring:1.0
```

- `fx-logs:/app/logs` — a **named volume**: Docker-managed storage that outlives the container.
- `"$PWD/ops:/app/ops:ro"` — a **bind mount**: a real folder on your laptop, mounted read-only —
  try writing to it inside the container (`docker exec ... touch /app/ops/nope`) and watch it
  get refused.

### 9. Everyday commands

```bash
docker ps                     # running containers
docker ps -a                  # ...and stopped ones
docker logs <id>               # its stdout
docker exec -it <id> sh        # a shell inside the container
docker stop <id> && docker rm <id>
docker images
```

**Checkpoint** — from a clean `docker run -p 8080:8080`:
```bash
curl -s localhost:8080/health                                      # UP
curl -s localhost:8080/api/health/db                                # DOWN (expected)
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/rates   # 500 (expected)
```
No JDK, Gradle, or MySQL involved in running it. The 500 goes away in Step 2.

### 10. Ship it

```bash
git add -A
git commit -m "feat: containerise fx-app-spring"
git push -u origin step-01
```

Open a PR from `step-01` into `main` and merge it, then:
```bash
git switch main
git pull
```

<details>
<summary>Stuck?</summary>

- **`COPY build/libs/...` not found** — run `./gradlew bootJar` first, or you're already on the
  multi-stage Dockerfile (step 4 removes this need).
- **`port is already allocated`** — something else holds 8080; stop it, or use `-p 8090:8080`.
- **Everything says `CACHED` after a change** — the file you changed is excluded by
  `.dockerignore`.
- **Ctrl-C doesn't stop it** — you ran it with `-d`; use `docker ps` then `docker stop <id>`.
</details>
