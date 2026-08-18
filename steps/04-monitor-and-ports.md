# Step 4 — A second app, and the port fight (~45 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-04/) — struggle first*

### Why we're doing this

Everything you've containerised so far you also wrote. That is not what the job looks like. Most
of the containers you will ever run were built by somebody else, and your work is to make them
*fit*: give them a port, tell them where their dependencies live, and put them in the stack.

`fx-monitor/` is that app. It's a small web page — someone else's — that watches your API. You
will not write a line of it. You will containerise it, plug it into your stack, and hit the
first genuinely new problem of the day: **two apps, one laptop, one port 8080.**

**Skills you're building**
- Containerising an app you did not write, from its README and its files
- Publishing a second service, and diagnosing a real port collision from the error text
- Serving a browser app from nginx and proxying its API calls to another container
- Why "same origin" makes CORS a non-issue here

### What you're producing

`fx-monitor/` living in your repo, a third service in `docker-compose.yml`, and a working web
page at `http://localhost:3000` showing your rates.

---

### Step-by-step

> **Branch first.** Every step gets its own branch, merged to `main` through a pull request
> at the end. Start on a clean `main`:
>
> ```bash
> git switch main && git pull
> git switch -c step-04
> ```

**1. Take delivery of the app.** Copy `fx-monitor/` into your **workspace root** — beside
`fx-app-spring/`, not inside it:

```bash
cp -R ../given/fx-monitor .
ls
# docker-compose.yml   fx-app-spring/   fx-monitor/   README.md
```

Read `fx-monitor/README.md` and the top of `fx-monitor/app.js`. Note what it calls: `/health`,
`/api/rates`, and — not yet built — `/api/admin/accepting`. That last one is Step 5's problem.

**2. Read its Dockerfile before you build it.** Open `fx-monitor/Dockerfile`. It is five lines
and there is **no build stage** — because there is nothing to compile. Static files go straight
into an nginx image.

Compare that with your API's Dockerfile. Same tool, wildly different shape, because the two apps
need different things. A Dockerfile describes *this* app; there is no universal template.

**3. Notice what you did *not* have to do.** You just added a whole second application to the
repo, and your API's build context did not grow by a single byte — because `build:` points at
`./fx-app-spring`, and `fx-monitor` is its **sibling**, not its child.

Prove it:

```bash
docker compose build fx-app-spring    # watch the "transferring context" line
```

It reads about **28 kB** — your pom, your `src`, your `ops`. That is the whole upload.

Had you nested the apps inside `fx-app-spring/`, every API build from here on would upload
`fx-monitor` — and in Step 5, a whole second Gradle project with its own `build/` — to the daemon
for nothing. **Layout is a build-performance decision, not just a tidiness one.**

**4. Build and run it on its own, and let it be useless.**

```bash
docker build -t fx-monitor:1.0 ./fx-monitor
docker images | grep fx-monitor
docker run -p 3000:80 fx-monitor:1.0
```

Note the shape of that build: `-t fx-monitor:1.0` names it, and `./fx-monitor` is the context.
You are standing at the workspace root and pointing at one app — exactly what compose does for you
in step 5.

Open `http://localhost:3000`. The page renders and the health dot is red — every API call fails.
Of course: nginx is trying to reach a host called `fx-app-spring`, and this container is not on
the compose network, so that name means nothing. Stop it. It belongs in the stack.

**5. Add it to compose — and get the port wrong first.** Add a third service, publishing on
8080 exactly as you did for the API:

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

It fails, with something close to:

```
Error response from daemon: failed to set up container networking:
driver failed programming external connectivity on endpoint fx-stack-fx-monitor-1:
Bind for 0.0.0.0:8080 failed: port is already allocated
```

Read it carefully — it is not about compose or nginx. **`fx-app-spring` already published host
port 8080, and a host port can only belong to one thing at a time.** Two processes cannot both
listen on the same port on the same machine; that has been true since long before containers.

Find out who has it:

```bash
docker compose ps
lsof -nP -iTCP:8080 -sTCP:LISTEN     # macOS / Linux
```

**6. Fix it, and notice what you did *not* have to change:**

```yaml
    ports:
      - "${MONITOR_PORT:-3000}:80"
```

```bash
docker compose up --build -d
docker compose ps
```

Look at the `PORTS` column now. You have **three containers, and two of them are listening on
port 80 or 8080 internally** — nginx on 80, Spring on 8080 — and nobody is fighting.

> **Container ports are private; published host ports are shared.** Every container gets its own
> network namespace, so port 80 inside `fx-monitor` has nothing to do with port 80 inside any
> other container. Only the **left-hand** number is a scarce, machine-wide resource. When you hit
> "port is already allocated", it is *always* the left-hand number.
>
> This is why you never need to reconfigure an app to change its port — you re-publish it. The
> image is unchanged; the door is different.

**7. See it work.** Open `http://localhost:3000`.

The rates table fills in, the health dot goes green, and the poll counter climbs. The values
don't change yet — nothing is feeding them — but the plumbing is complete.

Now check where the browser is actually sending those calls: open DevTools → Network. Every
request goes to **`localhost:3000`**. Not one goes to 8080.

**8. Understand the proxy, because it explains why CORS never came up.** Open
`fx-monitor/nginx.conf.template`:

```nginx
location /api/  { proxy_pass http://${API_HOST}:8080; }
location /health { proxy_pass http://${API_HOST}:8080; }
```

nginx serves the page **and** forwards its API calls onward to `fx-app-spring:8080` — by compose
service name, over the internal network, on the container port. As far as the browser is
concerned there is exactly one origin, `localhost:3000`, so the browser's cross-origin rules
never engage. No CORS headers, no preflight, nothing to configure.

`${API_HOST}` is filled in at container start from the environment (nginx runs `envsubst` over
`/etc/nginx/templates/*`), which is why the same image works in your stack and in someone else's
with a different service name.

Prove the proxy is real:

```bash
curl -s localhost:3000/health          # answered by Spring, served through nginx
curl -s localhost:3000/api/rates | head -c 120
```

**Checkpoint.** `docker compose ps` shows **three** services up. `http://localhost:3000` renders
the rates table with 10 pairs and a green health dot. `curl localhost:3000/api/rates` returns
JSON. The ACCEPTING toggle is greyed out with a banner about a 404 — expected, you build it next.

**9. Ship it.** The work is done and proven — now land it on `main`, the same way every
change lands on `main` for the rest of this course.

```bash
git add -A
git commit -m "feat: add fx-monitor to the stack"
git push -u origin step-04
```

Open a pull request from `step-04` into `main` on GitHub, and merge it. Then bring your local
`main` back in line before you start the next task:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR. Nobody commits straight to `main` —
> not while you are alone in the repo, and least of all when you are not.

<details>
<summary>Stuck?</summary>

**Page loads but the health dot stays red.** nginx can't reach the API. Check `API_HOST` matches
your API's **service name** exactly, and that the API is actually up (`docker compose ps`).

**`host not found in upstream "fx-app-spring"`** in the fx-monitor logs. Service name typo, or
you're running the container outside compose (step 4) where that name doesn't resolve.

**Blank page / old content.** Browser cache. Hard-reload (Cmd-Shift-R / Ctrl-Shift-R).

**Port 3000 also taken.** Set `MONITOR_PORT=3100` in `.env`. That's exactly what the variable is
for.

**Edited the HTML and nothing changed.** The files are baked into the image at build time —
`docker compose up --build`.
</details>
