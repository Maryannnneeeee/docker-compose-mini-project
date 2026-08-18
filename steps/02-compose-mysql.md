# Step 2 — The whole stack in one file (~60 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-02/) — struggle first*

### Why we're doing this

Step 1 ended with a container that runs and cannot reach a database — and we left it that way on
purpose. Every cheap fix from here points the app back at something installed by hand on one
laptop, which is the exact problem the day exists to remove.

So the database joins the app in the box. By the end of this task the whole exchange comes up
from one command, `/api/rates` returns real data, and there is still no MySQL installed on your
machine — permanently, if you like.

**Skills you're building**
- Describing a multi-container system declaratively instead of remembering `docker run` flags
- Container-to-container networking by **service name**, and why `localhost` is still wrong
- Named volumes vs bind mounts — what survives a restart, and what doesn't
- Healthchecks, and why "the container started" is not "the database is ready"

### What you're producing

`docker-compose.yml` and `.env.example` at your repo root, and a stack that comes up with one
command on a machine with no MySQL installed.

---

### Step-by-step

> **Branch first.** Every step gets its own branch, merged to `main` through a pull request
> at the end. Start on a clean `main`:
>
> ```bash
> cd fx-exchange
> git switch main && git pull
> git switch -c step-02
> ```

**1. Write the database service first.** Create `docker-compose.yml` at your **workspace root** —
beside `fx-app-spring/`, not inside it. The compose file describes the *system*; each app
describes only itself:

```
fx-exchange/
├── docker-compose.yml     ← here
└── fx-app-spring/
    └── Dockerfile         ← and there
```


```yaml
name: fx-stack

services:
  fx-db:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: rootpass
      MYSQL_DATABASE: fxdb
      MYSQL_USER: appuser
      MYSQL_PASSWORD: apppass
    ports:
      - "3307:3306"
    volumes:
      - fx-db-data:/var/lib/mysql
      - ./fx-app-spring/ops/fxdb-seed.sql:/docker-entrypoint-initdb.d/01-seed.sql:ro

volumes:
  fx-db-data:
```

```bash
docker compose up
```

Watch it initialise, then in another terminal:

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM fx_rate;"
```

**30.** Your Week-1 seed just ran itself. Three things in that file deserve a hard look:

- **`"3307:3306"`** — left is your laptop, right is inside the container. 3307 on the left
  *deliberately*, so this never fights a MySQL already installed on the host default 3306. Nothing about
  the container changed; only the door you reach it through.
- **`fx-db-data:/var/lib/mysql`** — a **named volume**. Docker owns it. This is where the actual
  data lives, and it outlives the container.
- **`./fx-app-spring/ops/fxdb-seed.sql:…:ro`** — a **bind mount** of one file from your repo,
  read-only. Paths in a compose file are relative to **the compose file**, which is why this one
  reaches down into `fx-app-spring/`. The MySQL image runs everything in
  `/docker-entrypoint-initdb.d/` **once, and only when the data directory is empty.**

**2. Add the app, and get the connection wrong on purpose.** Add a second service:

```yaml
  fx-app-spring:
    build: ./fx-app-spring
    image: fx-app-spring:1.0
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://localhost:3306/fxdb
      SPRING_DATASOURCE_USERNAME: appuser
      SPRING_DATASOURCE_PASSWORD: apppass
```

Two lines there are worth stopping on:

- **`build: ./fx-app-spring`** — this is the build *context*: the directory Docker uploads and
  runs the Dockerfile against. Because the three apps are siblings rather than nested, this
  context contains your API and **nothing else**. No `.dockerignore` gymnastics needed.
- **`image: fx-app-spring:1.0`** — the name and tag the built image gets locally. Leave it out and
  compose invents one (`fx-stack-fx-app-spring`). Name it yourself and `docker images` shows
  something you chose, and `docker run`/`docker push` can refer to it later. `build:` and
  `image:` together mean "build it, and call it this".

```bash
docker compose up --build
docker images | grep fx-app-spring     # fx-app-spring:1.0 — your name, not a generated one
```

`Connection refused` again — same lesson as Step 1, new setting. Inside `fx-app-spring`'s
container, `localhost` is `fx-app-spring`. The database is a *different container*.

Fix it:

```yaml
      SPRING_DATASOURCE_URL: jdbc:mysql://fx-db:3306/fxdb
```

> **Compose gives every service a DNS name equal to its service name.** `fx-db` resolves to
> whatever IP that container happens to have. You never hardcode an IP, and you never need to know one.
>
> And note the port: **3306**, not 3307. 3307 is the door from *your laptop*. Container to
> container, they use the real port. Getting these two confused is the second most common
> mistake of the day.

**3. Make `depends_on` mean something.** Restart from scratch:

```bash
docker compose down -v
docker compose up --build
```

Depending on timing, the app may still lose the race — MySQL takes seconds to initialise on a
fresh volume, and the app tries to connect immediately. Add a healthcheck to `fx-db`:

```yaml
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u$${MYSQL_USER} -p$${MYSQL_PASSWORD}"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 20s
```

and to `fx-app-spring`:

```yaml
    depends_on:
      fx-db:
        condition: service_healthy
```

And one more line on `fx-app-spring`, beside its `image:`:

```yaml
    restart: on-failure
```

```bash
docker compose down -v && docker compose up --build
```

Now watch the order: `fx-db` starts → goes `healthy` → *only then* `fx-app-spring` starts.

> **Why both?** The healthcheck stops the app being started *too early*; `restart: on-failure`
> catches everything else. The database can go away *after* a clean start — Docker Desktop
> restarting, a laptop sleeping, `docker compose restart fx-db` — and this app exits on a lost
> connection rather than reconnecting. Without a restart policy it then stays dead until someone
> notices. Ordering and recovery are different problems; `depends_on` only solves the first.

> `depends_on` **without** a `condition` waits only for the container to *start*, which is
> almost never what you mean. "The process exists" and "the service is ready to answer" are
> different moments, and everything between them is a flaky startup you'll blame on something
> else. The `$$` is not a typo — it escapes the `$` so compose passes it through to the shell
> instead of interpolating it itself.

> **`127.0.0.1`, not `localhost` — and this one is worth the detour.** To the `mysql` client
> those are not synonyms: `localhost` means *connect over the UNIX socket*, `127.0.0.1` means
> *connect over TCP*. On a fresh volume MySQL's entrypoint initialises the database using a
> temporary server started with `--skip-networking`, reachable **only** over that socket. Ping
> it with `-h localhost` and it answers — so the container is marked healthy, `depends_on` lets
> go, the app starts, Liquibase dials port 3306, and gets `Communications link failure`, because
> the only server running is deliberately not listening on TCP yet. A healthcheck that passes
> before the service can serve is worse than none: it makes the race look like your bug.
> Ask the question the way the app will ask it.

**4. The honest test.** Your local MySQL should still be stopped from Step 1 — confirm it, and
actually stop it if not (closing Workbench is not stopping it).

```bash
docker compose down -v
docker compose up --build -d
docker compose ps
curl -s localhost:8080/api/rates | python3 -m json.tool | head -20
curl -s localhost:8080/api/rates/EUR/USD
```

**Checkpoint:** 10 rates, EUR/USD **1.0818**, with no MySQL installed on the host and no
`host.docker.internal` anywhere. That is the whole point of the morning.

**5. Learn what `down` does and doesn't destroy.** This trips everyone once, so do it
deliberately:

```bash
# add a row so you can tell "kept" from "recreated"
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "INSERT INTO currency VALUES ('NZD','NZ Dollar','NZ$');"
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM currency;"   # 9

docker compose down          # containers destroyed, VOLUME KEPT
docker compose up -d
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM currency;"   # still 9

docker compose down -v       # -v also destroys the volume
docker compose up -d
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM currency;"   # back to 8
```

> The seed script did **not** re-run in the middle case, and it **did** in the last one.
> `/docker-entrypoint-initdb.d` only executes against an **empty data directory**. With the
> volume intact there was nothing to initialise. Remember this when someone says "I changed the
> seed file and nothing happened."

**6. Lift the values out into `.env`.** Hardcoded ports are fine until two people on the same
machine both want 8080. Replace the literals with defaults:

```yaml
      - "${DB_PORT:-3307}:3306"
      - "${API_PORT:-8080}:8080"
```

`${VAR:-default}` means "use `VAR` if it's set, otherwise this". Compose reads a `.env` file in
the same directory automatically. Ship a **`.env.example`** listing every knob:

```
DB_PORT=3307
API_PORT=8080
DB_NAME=fxdb
DB_USERNAME=appuser
DB_PASSWORD=apppass
MYSQL_ROOT_PASSWORD=rootpass
```

and add `.env` to a `.gitignore` **at the workspace root** — the example goes in the repo, the
real one never does. Note *which* `.gitignore`: `fx-app-spring/.gitignore` covers that app's
`build/`, but `.env` sits a level above it and is invisible to it. Each app ignores its own build
output; the root ignores what belongs to the stack. Verify
your file still parses, and see what compose actually resolved:

```bash
docker compose config
```

**7. The commands you'll use for the rest of the course:**

```bash
docker compose up -d              # start detached
docker compose ps                 # what's up, and on which ports
docker compose logs -f fx-app-spring   # follow ONE service's logs
docker compose exec fx-db bash    # a shell in the db container
docker compose restart fx-app-spring
docker compose down               # stop and remove containers
docker compose down -v            # ...and the volumes
```

**Checkpoint.** `docker compose down -v && docker compose up --build -d`, wait for healthy,
then `curl localhost:8080/api/rates` returns the 10 seeded pairs — on a machine with no local
MySQL running. `docker compose config` parses clean.

**8. Ship it.** The work is done and proven — now land it on `main`, the same way every
change lands on `main` for the rest of this course.

```bash
git add -A
git commit -m "feat: compose the app with a seeded MySQL"
git push -u origin step-02
```

Open a pull request from `step-02` into `main` on GitHub, and merge it. Then bring your local
`main` back in line before you start the next task:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR. Nobody commits straight to `main` —
> not while you are alone in the repo, and least of all when you are not.

<details>
<summary>Stuck?</summary>

**`port is already allocated`.** Something on your host already has 3307 or 8080 — very likely a
container from Step 1. `docker ps`, stop it. Or set `DB_PORT=13307` in `.env` and move on.

**App starts before the DB is ready even with the healthcheck.** Check the healthcheck is on
`fx-db` (not on the app) and that `depends_on` uses the `condition:` form, not the list form.
`docker compose ps` shows the health state.

**"Unknown database 'fxdb'".** `MYSQL_DATABASE` is only honoured when the data directory is
created. If you changed it after first boot, `docker compose down -v` and up again.

**Seed changes aren't showing up.** Expected — see step 5. `down -v`.

**`Access denied for user 'appuser'`.** The volume was created with different credentials than
the ones you're passing now. Same fix: `down -v`.
</details>
