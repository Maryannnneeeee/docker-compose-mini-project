# Step 5 — The orchestrator loop (~80 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-05/) — struggle first*

### Why we're doing this

Everything so far has been *your* stack talking to itself. Now a service you did not write, do
not control, and cannot change starts pushing data at you — and expects an answer.

`fx-orchestrator` is an upstream rate feed. Every couple of seconds it invents fresh rates and
POSTs them to your API. Then it waits for you to call it back and say what you did with them:

- **`ACCEPTED`** — you stored them → it sends the next batch in **2 seconds**
- **`DECLINED`** — you didn't → it backs off and waits **10 seconds**

That is the entire contract, and it is the shape of an enormous amount of real integration work:
someone else's service, a documented request, a callback, and a consequence for your answer. You
will implement your half of it, and `fx-monitor`'s toggle will let you flip the answer live and
watch the upstream feed change its behaviour because of what you said.

**Skills you're building**
- Implementing an inbound contract someone else defined, from their request shape
- Making an **outbound** HTTP call from Spring, with a URL that is configuration not a constant
- Evolving a live schema safely — the payoff for Step 3's rule
- Reading another service's logs to debug your own

### What you're producing

A closed loop: five containers, rates changing every 2 seconds, a toggle that stops them, and
every tick stored in `fxdb`.

---

### Step-by-step

> **Branch first.** Every step gets its own branch, merged to `main` through a pull request
> at the end. Start on a clean `main`:
>
> ```bash
> cd fx-exchange
> git switch main && git pull
> git switch -c step-05
> ```

**1. Put the feed in the stack, and let it shout at you.**

Copy `fx-orchestrator/` into your **workspace root** — the third sibling:

```bash
cp -R ../given/fx-orchestrator .
ls
# docker-compose.yml  fx-app-spring/  fx-monitor/  fx-orchestrator/  README.md
```

It is a whole second Gradle project, with its own `build.gradle`, its own `Dockerfile` and its own
Liquibase changelog. Because it sits *beside* `fx-app-spring` rather than inside it, none of that
touches your API's build context.

It needs its **own database** — a second service does not share your tables:

```yaml
  orchestrator-db:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: rootpass
      MYSQL_DATABASE: orchestratordb
      MYSQL_USER: appuser
      MYSQL_PASSWORD: apppass
    ports:
      - "${ORCH_DB_PORT:-3308}:3306"
    volumes:
      - orchestrator-db-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u$${MYSQL_USER} -p$${MYSQL_PASSWORD}"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 20s

  fx-orchestrator:
    build: ./fx-orchestrator
    image: fx-orchestrator:1.0
    restart: on-failure
    ports:
      - "${ORCH_PORT:-8081}:8080"
    environment:
      DB_URL: jdbc:mysql://orchestrator-db:3306/orchestratordb
      DB_USERNAME: appuser
      DB_PASSWORD: apppass
      FX_TARGET_URL: http://fx-app-spring:8080
      FX_SELF_URL: http://fx-orchestrator:8080
    depends_on:
      orchestrator-db:
        condition: service_healthy
      fx-app-spring:
        condition: service_started
```

and remember the second named volume:

```yaml
volumes:
  fx-db-data:
  orchestrator-db-data:
```

> **A third host port (3308), and two MySQL containers both on 3306 internally.** Exactly the
> Step 4 lesson, now with databases. Also worth noticing: `fx-orchestrator` publishes `8081:8080`
> — *inside* its container it is on 8080, same as your API, and they coexist happily.

Now bring it up and **read the orchestrator's log** — it is written to be read:

```bash
docker compose up --build -d
docker compose logs -f fx-orchestrator
```

It prints the contract, sends batch #1, and then tells you exactly what went wrong:

```
│ !! fx-app-spring answered HTTP 404 — it did not take the batch.
│    Nothing is mapped at POST /api/feed/rates.
│    It must accept the batch and answer 202.
```

Good. Leave it running in a terminal for the rest of the task — it narrates every change you
make.

**2. Make room in the schema — *forward*, never sideways.**

`fx_rate` has `rate_date DATE`. The feed writes many rows per pair **per day**, so a date can no
longer answer "which row is newest?" — and `RateRepository.findLatest()` currently means
"everything on the newest date", which would return an ever-growing pile.

You need a timestamp. Step 3 told you how to add one: **not** by editing `003-create-fx-rate.yaml`
(it has already run on every machine in the room) but with a new change set:

```yaml
databaseChangeLog:

  - changeSet:
      id: 009-add-fx-rate-captured-at
      author: fx
      changes:
        - addColumn:
            tableName: fx_rate
            columns:
              - column: { name: captured_at, type: DATETIME(3) }
      rollback:
        - dropColumn: { tableName: fx_rate, columnName: captured_at }

  # Schema change and DATA migration are two different change sets.
  - changeSet:
      id: 009b-backfill-captured-at
      author: fx
      changes:
        - sql:
            sql: UPDATE fx_rate SET captured_at = TIMESTAMP(rate_date) WHERE captured_at IS NULL

  - changeSet:
      id: 009c-index-fx-rate-lookup
      author: fx
      changes:
        - createIndex:
            tableName: fx_rate
            indexName: idx_fx_rate_pair_captured
            columns:
              - column: { name: base_code }
              - column: { name: quote_code }
              - column: { name: captured_at }
```

The backfill matters: the 30 seeded rows predate the column, and `NULL` would make them sort
unpredictably. The index matters because this table is about to grow by 10 rows every 2 seconds
and you're about to query it once a second.

Add all three to the master. Restart the app **without** `down -v` — that's the point:

```bash
docker compose restart fx-app-spring
docker compose logs fx-app-spring | grep -i liquibase
```

Three new change sets applied to a **live database with data in it**, no rebuild, no data loss.

**3. Teach the repository the new question.** Add `capturedAt` to the `Rate` record:

```java
public record Rate(int id, String baseCode, String quoteCode, double rate,
                   LocalDate rateDate, LocalDateTime capturedAt) {
    public String pair() { return baseCode + "/" + quoteCode; }
}
```

Update the `RowMapper` to read it (`rs.getTimestamp("captured_at")` → handle `null`), then change
what `findLatest()` *asks*. Not "every row on the newest date" but **"the newest row for each
pair"**:

```java
public List<Rate> findLatest() {
    return jdbc.query("""
        SELECT r.* FROM fx_rate r
        WHERE r.id = (SELECT r2.id FROM fx_rate r2
                      WHERE r2.base_code = r.base_code AND r2.quote_code = r.quote_code
                      ORDER BY r2.captured_at DESC, r2.id DESC
                      LIMIT 1)
        ORDER BY r.base_code, r.quote_code""", MAPPER);
}
```

Do the same to `findLatestForPair` (`ORDER BY captured_at DESC, id DESC LIMIT 1`) and make
`insert` write `captured_at = CURRENT_TIMESTAMP(3)`.

> Check yourself: on the *seeded* data this returns the identical answer as before — 10 rows,
> EUR/USD 1.0818 — because the seed has one row per pair per day. The question changed; the
> answer to the old data didn't. That is how you know it's a safe change.

**4. Receive the batch.** The orchestrator's log tells you the exact shape it sends. Model it:

```java
public record IncomingRate(String base, String quote, double rate) {}

public record IncomingBatch(long batchId, Instant generatedAt, List<IncomingRate> rates) {}
```

Field names must match the JSON exactly — that is the contract.

Now a controller. **202, not 201**: you are acknowledging receipt, and the real answer travels
back separately.

```java
@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private final FeedService feed;

    public FeedController(FeedService feed) { this.feed = feed; }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody IncomingBatch batch) {
        feed.handle(batch);
    }
}
```

and a `@Service` that stores every tick via `rates.insert(...)` and logs how many.

Rebuild and watch the orchestrator's log:

```bash
docker compose up --build -d fx-app-spring
docker compose logs -f fx-orchestrator
```

The 404 is gone. Now you get:

```
│ POST accepted by fx-app-spring. Over to you.
│ !! NO ACK for batch #7 within 5s.
│    Did you implement POST /api/feed/ack calling back to http://fx-orchestrator:8080?
```

**It is taking your data and getting no answer**, so it assumes the worst and crawls at 10s.
Check the rows are landing:

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM fx_rate;"
```

Above 30 and climbing.

**5. Close the loop — call them back.** This is the first time your app is an HTTP *client*.

A `RestTemplate` bean, built once, injected like anything else — with timeouts, because a slow
remote service must not become a slow *your* service:

```java
@Configuration
public class HttpConfig {
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofSeconds(2))
                      .setReadTimeout(Duration.ofSeconds(3))
                      .build();
    }
}
```

The URL is **configuration, not a constant** — `localhost:8081` from your IDE, a service name in
compose. In `application.properties`:

```properties
fx.orchestrator.url=${FX_ORCHESTRATOR_URL:http://localhost:8081}
```

and in compose, on the `fx-app-spring` service:

```yaml
      FX_ORCHESTRATOR_URL: http://fx-orchestrator:8080
```

Then a small client that POSTs `{"batchId":N,"status":"ACCEPTED"}` to
`{base}/api/feed/ack`, and **catches its own failures** — a callback that throws must never break
the request you're serving. Call it at the end of your `FeedService.handle(...)`.

Rebuild, and watch the log change gear:

```
│ ✓ batch #12  ACCEPTED  (43 ms) — you stored them
│ → next batch in 2s
```

**Two seconds.** You just changed another service's behaviour by answering it. Open
`http://localhost:3000` — the numbers are moving.

**6. Build the switch.** Right now you always say ACCEPTED. The toggle needs a real answer.

An in-memory flag — it's an operational switch, not business data, and resetting to ON on restart
is the correct behaviour:

```java
@Component
public class AcceptingState {
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    public boolean isAccepting() { return accepting.get(); }
    public boolean set(boolean value) { accepting.set(value); return value; }
}
```

`AtomicBoolean` because the feed thread reads it while an HTTP thread may be flipping it — Week
1's threading kata, quietly earning its keep.

Two endpoints, which is exactly what `fx-monitor` has been 404ing on all afternoon:

- `GET /api/admin/accepting` → `{"accepting":true}`
- `POST /api/admin/accepting` with `{"accepting":false}` → returns the new state

Then make `FeedService` consult it: **if not accepting, store nothing and ACK `DECLINED`.**

**7. Watch the whole thing work.** Rebuild, open `http://localhost:3000` next to a terminal
running `docker compose logs -f fx-orchestrator`.

The toggle is live now. Click it **OFF**:

- the table freezes — values stop moving
- the orchestrator log flips to `✗ batch #N DECLINED … → next batch in 10s`
- the pace visibly drops to one batch every ten seconds
- `SELECT COUNT(*) FROM fx_rate` stops climbing

Click it **ON**: back to 2s, values moving, rows accumulating.

You are now sitting in front of a distributed system: five containers, two databases, two Spring
apps and a browser, where a click in the browser changes the behaviour of a service two hops
away — and you can prove every link in that chain from the logs.

**Checkpoint.** All five services up.

```bash
curl -s localhost:8080/api/rates | head -c 200            # or your API_PORT, if you changed it
curl -s localhost:8081/api/feed/summary                  # accepted/declined counts
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "SELECT COUNT(*) FROM fx_rate;"
```

With ACCEPTING **ON**: `fx_rate` grows by 10 roughly every 2s, `/api/rates` still returns exactly
**10 rows** (latest per pair), and the monitor's values change. With it **OFF**: the count holds
steady, the monitor freezes, and the orchestrator reports DECLINED at 10s intervals.

**8. Ship it.** The work is done and proven — now land it on `main`, the same way every
change lands on `main` for the rest of this course.

```bash
git add -A
git commit -m "feat: accept the upstream rate feed"
git push -u origin step-05
```

Open a pull request from `step-05` into `main` on GitHub, and merge it. Then bring your local
`main` back in line before you start the next task:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR. Nobody commits straight to `main` —
> not while you are alone in the repo, and least of all when you are not.

<details>
<summary>Stuck?</summary>

**Orchestrator says HTTP 404.** Your endpoint isn't mapped where it expects. It must be exactly
`POST /api/feed/rates`. Check `@RequestMapping` + `@PostMapping` compose to that path.

**HTTP 400 or 415.** Your record's field names don't match the JSON, or `@RequestBody` is
missing. The orchestrator's log prints the shape it sends.

**"NO ACK" even though you built the ack.** Three usual causes: (a) `FX_ORCHESTRATOR_URL` still
points at `localhost` — inside your container that is your container; (b) you're posting to the
wrong path (`/api/feed/ack`); (c) your ack call threw and you swallowed it silently — check
**your** app's log, not the orchestrator's.

**"ACK for batch #7 but the batch in flight is #9".** You're echoing the wrong id. Send back the
`batchId` you were given, not a counter of your own.

**`/api/rates` returns hundreds of rows.** `findLatest()` is still "newest date". Every row the
feed writes carries the current date. Re-read step 3.

**Rows aren't growing.** Either ACCEPTING is OFF, or `handle()` returns before inserting. Check
your app's log for the "stored N rates" line you wrote.

**`captured_at` is NULL for seeded rows.** The `009b` backfill didn't run — check it's in the
master changelog and look for it in `DATABASECHANGELOG`.
</details>
