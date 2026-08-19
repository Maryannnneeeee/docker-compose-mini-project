# Step 3 — A second app, and the port fight (~45 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-03/) — struggle first*

`fx-monitor/` is a small web app someone else wrote — you'll containerise it, plug it into your
stack, and hit the first genuinely new problem of the day: two apps, one laptop, one port 8080.

**New concepts:** containerising someone else's app, nginx as a reverse proxy, why same-origin
avoids CORS.

---

Branch first:
```bash
cd fx-exchange
git switch main && git pull
git switch -c step-03
```

### 1. Take delivery of the app

Copy it into your **workspace root**, beside `fx-app-spring/`:
```bash
cp -R ../given/fx-monitor .
```

Read `fx-monitor/README.md` and the top of `fx-monitor/app.js`. It calls `/health`, `/api/rates`,
and `/api/admin/accepting` — `fx-app-spring` ticks its own live rates on a schedule and exposes
that toggle itself, no second app or database needed.

### 2. Look at its Dockerfile

`fx-monitor/Dockerfile` is five lines with **no build stage** — nothing to compile, static files
go straight into an nginx image. Compare it with your API's Dockerfile: same tool, different
shape, because each app's Dockerfile only has to describe *that* app.

### 3. Notice your API's build didn't grow

`fx-monitor` is a **sibling** folder, not nested inside `fx-app-spring/` — so its build context is
unaffected:
```bash
docker compose build fx-app-spring    # watch "transferring context" — still ~28 kB
```
Nesting apps inside one another means every build uploads folders it doesn't need. Layout is a
build-performance decision, not just tidiness.

### 4. Run it alone, and watch it fail

```bash
docker build -t fx-monitor:1.0 ./fx-monitor
docker run -p 3000:80 fx-monitor:1.0
```

Open `http://localhost:3000` — the page renders but the health dot is red. nginx is trying to
reach a host called `fx-app-spring`, which means nothing outside the compose network. Stop it —
it belongs in the stack.

### 5. Add it to compose — with the wrong port first

```yaml
  fx-monitor:
    build: ./fx-monitor
    image: fx-monitor:1.0
    ports:
      - "8080:80"
    environment:
      API_HOST: fx-app-spring
    depends_on:
      - fx-app-spring
```

```bash
docker compose up --build
```

```
Bind for 0.0.0.0:8080 failed: port is already allocated
```

`fx-app-spring` already publishes host port 8080, and a host port can only belong to one process.
Find out who has it:
```bash
docker compose ps
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

### 6. Fix it

```yaml
    ports:
      - "${MONITOR_PORT:-3000}:80"
```

```bash
docker compose up --build -d
docker compose ps
```

**Container ports are private; published host ports are shared** — nginx's port 80 inside
`fx-monitor` has nothing to do with any other container's port 80. Only the host-side (left)
number is scarce. That's why you re-publish a port to change it rather than reconfiguring the app.

### 7. See it work

Open `http://localhost:3000` — the rates table fills in, live, health dot green. Check DevTools →
Network: every request goes to `localhost:3000`, none to 8080.

### 8. Understand the proxy

`fx-monitor/nginx.conf.template`:
```nginx
location /api/  { proxy_pass http://${API_HOST}:8080; }
location /health { proxy_pass http://${API_HOST}:8080; }
```

nginx serves the page **and** forwards API calls to `fx-app-spring:8080` by service name, over
the internal network. The browser only ever sees one origin, `localhost:3000` — so cross-origin
rules never engage. No CORS headers, no preflight. `${API_HOST}` is filled in at container start
via `envsubst`, so the same image works with any service name.

```bash
curl -s localhost:3000/health
curl -s localhost:3000/api/rates | head -c 120
```

**Checkpoint.** `docker compose ps` shows **three** services up. `http://localhost:3000` renders
the rates table with 10 pairs and a green health dot. The ACCEPTING toggle reads **ON**, and
rates tick every couple of seconds — `fx-app-spring` is feeding itself.

### 9. Ship it

```bash
git add -A
git commit -m "feat: add fx-monitor to the stack"
git push -u origin step-03
```

Open a PR from `step-03` into `main`, merge it, then:
```bash
git switch main
git pull
```

<details>
<summary>Stuck?</summary>

- **Health dot stays red** — check `API_HOST` matches the API's service name exactly, and the API
  is actually up (`docker compose ps`).
- **`host not found in upstream "fx-app-spring"`** — service name typo, or you ran the container
  outside compose (step 4), where that name doesn't resolve.
- **Blank page / old content** — browser cache; hard-reload.
- **Port 3000 also taken** — set `MONITOR_PORT=3100` in `.env`.
- **Edited the HTML, nothing changed** — files are baked into the image; `docker compose up --build`.
</details>
