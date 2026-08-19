# Step 2 — The whole stack in one file (~60 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-02/) — struggle first*

The database joins the app in a container of its own. By the end, the whole exchange comes up
from one command and `/api/rates` returns real data — with no MySQL installed on your machine.

**New concepts:** Docker Compose, service-name networking, healthchecks, `.env` files.

---

Branch first:
```bash
cd fx-exchange
git switch main && git pull
git switch -c step-02
```

### 1. Write the database service

Create `docker-compose.yml` at your **workspace root**, beside `fx-app-spring/` (not inside it —
the compose file describes the *system*, each app describes only itself):

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

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM fx_rate;"
```

Three things worth knowing:
- **`"3307:3306"`** — 3307 on the host so this never fights a MySQL already installed on your
  laptop's default 3306. Container-side, it's still 3306.
- **`fx-db-data:/var/lib/mysql`** — a **named volume**. Docker owns it; it outlives the container.
- **`./fx-app-spring/ops/fxdb-seed.sql:…:ro`** — a **bind mount** of one file, read-only. Compose
  paths are relative to the compose file. MySQL runs everything in
  `/docker-entrypoint-initdb.d/` once, only when the data directory is empty.

### 2. Add the app — and get the connection wrong on purpose

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

`build: ./fx-app-spring` is the build **context** — the folder Docker builds the Dockerfile
against. `image: fx-app-spring:1.0` names the result yourself, instead of a generated one.

```bash
docker compose up --build
```

`Connection refused` — same lesson as Step 1. Inside its container, `fx-app-spring`'s `localhost`
is itself, not the database. Fix it:

```yaml
      SPRING_DATASOURCE_URL: jdbc:mysql://fx-db:3306/fxdb
```

**Compose gives every service a DNS name equal to its service name** — `fx-db` resolves to
whatever IP that container has, no hardcoding needed. And note the port: **3306**, not 3307 —
3307 is the door from your laptop; containers talk to each other on the real port.

### 3. Make `depends_on` mean something

```bash
docker compose down -v
docker compose up --build
```

MySQL takes a few seconds to initialise on a fresh volume; the app may try to connect before it's
ready. Add a healthcheck to `fx-db`:

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
    restart: on-failure
```

```bash
docker compose down -v && docker compose up --build
```

Now `fx-db` starts → goes `healthy` → *then* `fx-app-spring` starts.

- `depends_on` **without** `condition:` only waits for the container to *start*, not to be ready
  — almost never what you want.
- `restart: on-failure` covers what a healthcheck can't: the database can go away *after* a clean
  start (a laptop sleeping, a restart), and without this the app just stays dead.
- The healthcheck pings `127.0.0.1`, not `localhost`, on purpose — MySQL's first-boot init server
  only listens on a local socket (`localhost`), not TCP (`127.0.0.1`). Pinging `localhost` would
  report healthy before the app can actually connect.

### 4. The honest test

Local MySQL should still be stopped from Step 1.

```bash
docker compose down -v
docker compose up --build -d
docker compose ps
curl -s localhost:8080/api/rates | python3 -m json.tool | head -20
```

**Checkpoint:** 10 rates, EUR/USD **1.0818** — no MySQL installed on the host.

### 5. `down` vs `down -v`

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "INSERT INTO currency VALUES ('NZD','NZ Dollar','NZ$');"

docker compose down          # containers destroyed, volume KEPT
docker compose up -d
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM currency;"  # 9

docker compose down -v       # -v also destroys the volume
docker compose up -d
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM currency;"  # back to 8
```

The seed script only reruns against an **empty** data directory — `down` alone leaves your data
(and any changes) intact; `down -v` wipes it back to the seed.

### 6. Lift values into `.env`

```yaml
      - "${DB_PORT:-3307}:3306"
      - "${API_PORT:-8080}:8080"
```

`${VAR:-default}` uses `VAR` if set, else the default. Compose reads a `.env` file automatically.
Ship a **`.env.example`**:

```
DB_PORT=3307
API_PORT=8080
DB_NAME=fxdb
DB_USERNAME=appuser
DB_PASSWORD=apppass
MYSQL_ROOT_PASSWORD=rootpass
```

Add `.env` to a `.gitignore` **at the workspace root** — the example is committed, the real file
never is.

```bash
docker compose config   # verify it parses, see what compose resolved
```

### 7. Commands you'll use for the rest of the course

```bash
docker compose up -d                    # start detached
docker compose ps                       # what's up, and on which ports
docker compose logs -f fx-app-spring    # follow one service's logs
docker compose exec fx-db bash          # a shell in the db container
docker compose restart fx-app-spring
docker compose down                     # stop and remove containers
docker compose down -v                  # ...and the volumes
```

**Checkpoint.** `docker compose down -v && docker compose up --build -d`, wait for healthy, then
`curl localhost:8080/api/rates` returns the 10 seeded pairs. `docker compose config` parses clean.

### 8. Ship it

```bash
git add -A
git commit -m "feat: compose the app with a seeded MySQL"
git push -u origin step-02
```

Open a PR from `step-02` into `main`, merge it, then:
```bash
git switch main
git pull
```

<details>
<summary>Stuck?</summary>

- **`port is already allocated`** — something (likely a Step 1 container) already holds 3307 or
  8080. Stop it, or set `DB_PORT=13307` in `.env`.
- **App starts before the DB is ready anyway** — check the healthcheck is on `fx-db`, and
  `depends_on` uses the `condition:` form.
- **"Unknown database 'fxdb'"** — `MYSQL_DATABASE` only applies on first boot. `docker compose
  down -v` and try again.
- **Seed changes aren't showing up** — expected, see step 5. `down -v`.
- **`Access denied for user 'appuser'`** — the volume was created with different credentials.
  `down -v`.
</details>
