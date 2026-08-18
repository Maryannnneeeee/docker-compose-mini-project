# Step 3 — Liquibase owns the schema (~70 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-01-05.html) · [overview & cheatsheet](../decks/overview-steps-01-05.html) · [answer](../solutions/step-03/) — struggle first*

### Why we're doing this

There is still one thing in your stack that isn't code: **the shape of your database.**

`fxdb-seed.sql` works, but look at what it actually is — a file someone remembers to run, once,
by hand, against an empty database. It cannot answer any of the questions a real team asks daily:

- *Is my database the same shape as yours?* Nobody knows.
- *Someone added a column last Tuesday. Have I got it?* Nobody knows.
- *Can we rebuild staging from scratch?* Only if the file is still right, and nobody has run
  anything else by hand.
- *Can we undo it?* No.

You would never accept this for Java. You'd never say "run these files in this order and hope."
Your schema deserves the same treatment your code gets: **versioned, reviewed, repeatable, and
applied by the machine, not by a person remembering.**

That is Liquibase. It keeps an ordered list of **change sets**, records which ones this database
has already run, and on every startup applies exactly the ones it hasn't. Run it on an empty
database and you get the whole schema. Run it on a current one and it does nothing. Run it on a
database three changes behind and it applies three changes.

**Skills you're building**
- Expressing schema as versioned, reviewable change sets instead of ad-hoc SQL
- Reading `DATABASECHANGELOG` to know exactly what a database has had done to it
- The one rule that matters, and what breaking it looks like at 9am on a Monday
- Recovering a migration that failed halfway — including the lock nobody warns you about

### What you're producing

`src/main/resources/db/changelog/**` owning the whole of `fxdb`, the init-script mount **gone**
from compose, and a stack that rebuilds its own database from nothing on every `down -v`.

---

### Step-by-step

> **Branch first.** Every step gets its own branch, merged to `main` through a pull request
> at the end. Start on a clean `main`:
>
> ```bash
> git switch main && git pull
> git switch -c step-03
> ```

> Paths below are inside **`fx-app-spring/`** unless they mention `docker-compose.yml`, which
> lives at the workspace root.

**1. Add Liquibase.** In `fx-app-spring/build.gradle`, alongside your other dependencies:

```groovy
implementation 'org.liquibase:liquibase-core'
```

No version — `io.spring.dependency-management` applies the Boot BOM, which picks one that
matches your Boot version. In
`application.properties`:

```properties
spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml
```

**2. Write the master changelog.** Create
`src/main/resources/db/changelog/db.changelog-master.yaml`:

```yaml
databaseChangeLog:
  - include: { file: db/changelog/changes/001-create-currency.yaml }
```

The master is a table of contents and nothing else — **an ordered list**. Order is the whole
contract: change sets run top to bottom, once each, forever.

**3. Write the first change set.** `db/changelog/changes/001-create-currency.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-create-currency
      author: fx
      comment: The eight currencies the exchange trades. Every other table points here.
      changes:
        - createTable:
            tableName: currency
            columns:
              - column:
                  name: code
                  type: CHAR(3)
                  constraints: { primaryKey: true, nullable: false }
              - column:
                  name: name
                  type: VARCHAR(40)
                  constraints: { nullable: false }
              - column:
                  name: symbol
                  type: VARCHAR(4)
      rollback:
        - dropTable: { tableName: currency }
```

`id` + `author` + file path is the **identity** of this change set. Liquibase remembers that
triple; it is how it knows what it has already done.

You wrote `createTable`, not `CREATE TABLE`. That is deliberate: Liquibase renders the right
dialect per database, and — more usefully — it knows how to *reverse* what it generated. The
explicit `rollback` block says what "undo" means when the automatic answer isn't obvious.

**4. Do the other three tables.** Write `002-create-account`, `003-create-fx-rate`,
`004-create-transfer` the same way, and add each to the master. Copy the exact column types from
`ops/fxdb-seed.sql` — this must produce **the same schema the app has been running against**.

Foreign keys go inline on the column:

```yaml
              - column:
                  name: currency_code
                  type: CHAR(3)
                  constraints:
                    nullable: false
                    foreignKeyName: fk_account_currency
                    references: currency(code)
```

**5. Seed the data.** Schema is one job, data is another — separate change sets. Rather than
hand-translating 200 transfers into YAML, keep the SQL and point at it. Split the four `INSERT`
statements out of `ops/fxdb-seed.sql` into
`src/main/resources/db/changelog/data/005-currency.sql` … `008-transfer.sql` (one statement per
file, no `CREATE`, no `DROP`, no `USE`), then one change set each:

```yaml
databaseChangeLog:
  - changeSet:
      id: 005-seed-currency
      author: fx
      changes:
        - sqlFile:
            path: db/changelog/data/005-currency.sql
            splitStatements: false
            stripComments: true
      rollback:
        - delete: { tableName: currency }
```

Add all four to the master, in dependency order — currency before account, account before
transfer.

**6. Take the seed mount away.** This is the moment the ownership changes. In
`docker-compose.yml`, **delete** this line:

```yaml
      - ./fx-app-spring/ops/fxdb-seed.sql:/docker-entrypoint-initdb.d/01-seed.sql:ro
```

MySQL now starts with an empty `fxdb` and no opinion about what's in it. Then:

```bash
docker compose down -v
docker compose up --build
```

Watch the app's log on startup — Liquibase announces each change set as it runs. Then:

```bash
curl -s localhost:8080/api/rates/EUR/USD
docker compose exec fx-db mysql -uappuser -papppass fxdb -e "SELECT COUNT(*) FROM fx_rate;"
```

**Checkpoint: EUR/USD = 1.0818 and 30 rate rows — with no seed file mounted anywhere.** The
database built itself from your repo.

> Two things owned your schema a minute ago. Now one does. If a change isn't in
> `db/changelog/`, it isn't real — that is the rule that makes every environment reproducible.

**7. Read the ledger.** Liquibase created two tables. Look at them:

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "SELECT id, author, filename, dateexecuted, orderexecuted, md5sum FROM DATABASECHANGELOG;"
```

Eight rows — one per change set, in the order they ran, each with a checksum of the change set's
content at the moment it ran. **This table is the answer to "what has been done to this
database?"**, and it lives in the database itself, which is the only place that can't lie.

Restart the app without wiping:

```bash
docker compose restart fx-app-spring
docker compose logs fx-app-spring | grep -i liquibase
```

Nothing runs. Eight change sets, all already recorded. **That is idempotence** — the reason this
can safely run on every single startup in every environment.

---

### Now break it — three ways, on purpose

You will meet all three of these for real. Meeting them here, deliberately, is much cheaper.

**8. Break #1 — edit a change set that has already run.**

Open `db/changelog/data/005-currency.sql` — the file change set `005` points at — and add a
currency to the end of the `INSERT`:

```sql
...,('SGD','Singapore Dollar','S$'),('NZD','NZ Dollar','NZ$');
```

Then `docker compose up --build -d fx-app-spring` and read the log.

The app **refuses to start**:

```
liquibase.exception.ValidationFailedException: Validation Failed:
     1 changesets check sum
          db/changelog/changes/005-seed-currency.yaml::005-seed-currency::fx
          was: 9:3eea500a72ab1f1b13414c2cbd4f4177 but is now: 9:43f0ff754eef629bd2cbc3f6aa327818
```

It names the change set, the old hash and the new one.

Why: Liquibase hashed that change set when it ran it, and stored the hash in
`DATABASECHANGELOG`. The file no longer matches. It cannot apply it again (it's already run) and
it cannot verify the database matches what you now claim — so it stops rather than guess.

**The rule: never edit a change set that has already run anywhere.** Not on your machine, not on
a teammate's, and absolutely not in production. The schema moves **forward**.

**The fix:** put the file back exactly as it was, and express your change as a **new** change set
appended to the master:

```yaml
databaseChangeLog:
  - changeSet:
      id: 009-add-nzd
      author: fx
      changes:
        - insert:
            tableName: currency
            columns:
              - column: { name: code,   value: NZD }
              - column: { name: name,   value: NZ Dollar }
              - column: { name: symbol, value: "NZ$" }
      rollback:
        - delete:
            tableName: currency
            where: code = 'NZD'
```

Restart — it applies cleanly, `currency` has 9 rows and `DATABASECHANGELOG` has 9. Same outcome
you wanted, reached the way that keeps every other database in the world able to follow you.

You will also find `liquibase.clearCheckSums` on the internet, which recomputes the stored
hashes. Know it exists and know what it means: *"trust the files, forget what actually ran."* It
is a last resort for a development database you're about to rebuild anyway, never a fix for a
real one.

**9. Break #2 — ship bad SQL.**

Add a change set with a deliberate typo in the type:

```yaml
databaseChangeLog:
  - changeSet:
      id: 010-broken-on-purpose
      author: fx
      changes:
        - addColumn:
            tableName: currency
            columns:
              - column: { name: region, type: VARCHR(20) }   # VARCHR, not VARCHAR
```

Restart. The app fails to start, and the log names **which change set** failed and hands you the
database's own error underneath — including the exact SQL it tried:

```
ChangeSet db/changelog/changes/010-broken-on-purpose.yaml::010-broken-on-purpose::fx
  encountered an exception.
liquibase.exception.DatabaseException: You have an error in your SQL syntax; ... near
  'VARCHR(20) NULL' at line 1
  [Failed SQL: (1064) ALTER TABLE fxdb.currency ADD region VARCHR(20) NULL]
```

That last bracket is the useful part: Liquibase shows you the statement it generated, so you can
see your YAML's effect and not just its intent.

Then check the ledger:

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "SELECT id, exectype FROM DATABASECHANGELOG ORDER BY orderexecuted DESC LIMIT 3;"
```

The failed change set is **not** recorded as executed — so the fix really is just "fix it and
restart", and it will be retried from clean. Correct the typo to `VARCHAR(20)`, restart, watch
it apply.

> Note what this means for a half-applied change set that contains *several* statements: some
> may have run and been rolled back, some not, depending on your database. This is why change
> sets should be **small and single-purpose**. One idea per change set.

**10. Break #3 — the lock nobody warns you about.**

Liquibase takes a row-level lock in `DATABASECHANGELOGLOCK` before it migrates, so two instances
starting at once can't both migrate. If a process dies *while holding it*, the lock stays — and
the next start waits for a process that no longer exists.

To see it you need **pending work**, so add any small change set first (that's the nuance below).
Keep `010` from the previous step — fixed, but not yet applied — then:

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "UPDATE DATABASECHANGELOGLOCK SET LOCKED=1, LOCKEDBY='ghost', LOCKGRANTED=NOW() WHERE ID=1;
      SELECT ROW_COUNT();"
docker compose restart fx-app-spring
docker compose logs -f fx-app-spring
```

`ROW_COUNT()` must be **1** — if it's 0 the row doesn't exist yet and you've proved nothing.

Every ten seconds, forever:

```
liquibase.lockservice : Waiting for changelog lock....
liquibase.lockservice : Waiting for changelog lock....
```

The app never finishes starting — `curl localhost:8080/health` doesn't answer at all. Nothing is
wrong with your schema, your SQL, or your app.

> **The nuance that makes this confusing in the wild:** if the database is already **up to date**,
> Liquibase sees no work to do and starts normally *even with the lock held*. So the same stuck
> lock produces a healthy app on one machine and a hanging one on the machine that happens to
> have a new change set. "Works for me" — and both of you are right.

**The fix:**

```bash
docker compose exec fx-db mysql -uappuser -papppass fxdb \
  -e "UPDATE DATABASECHANGELOGLOCK SET LOCKED=0, LOCKEDBY=NULL, LOCKGRANTED=NULL WHERE ID=1;"
docker compose restart fx-app-spring
```

Remember this one. It is a genuinely confusing hour the first time it happens — usually right
after someone Ctrl-C'd a starting app — and the symptom looks nothing like the cause.

**11. Tidy up and prove it from nothing.** Release the lock if you haven't, delete the
`010-broken-on-purpose` change set and its `include:` line (it was scaffolding for the exercise),
and keep `009-add-nzd` — it's a legitimate change, arrived at the legitimate way. Then:

```bash
docker compose down -v
docker compose up --build -d
```

**Checkpoint.** From a completely destroyed database: the stack comes up, Liquibase builds and
seeds `fxdb` unaided, `curl localhost:8080/api/rates` returns 10 pairs and EUR/USD is
**1.0818**. No seed file is mounted. `DATABASECHANGELOG` lists your change sets in order.

**12. Ship it.** The work is done and proven — now land it on `main`, the same way every
change lands on `main` for the rest of this course.

```bash
git add -A
git commit -m "feat: Liquibase owns the fxdb schema"
git push -u origin step-03
```

Open a pull request from `step-03` into `main` on GitHub, and merge it. Then bring your local
`main` back in line before you start the next task:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR. Nobody commits straight to `main` —
> not while you are alone in the repo, and least of all when you are not.

<details>
<summary>Stuck?</summary>

**`Table 'currency' already exists` on first run.** Your volume still has the old
seed-script-built schema. `docker compose down -v`.

**`could not find the changelog file`.** Path is relative to the **classpath root**, so it starts
`db/changelog/...` with no leading slash and no `classpath:` prefix inside `include:`. Confirm the
files landed in `build/resources/main/db/changelog/` after a build.

**`sqlFile` can't find the .sql.** Same rule — `path: db/changelog/data/005-currency.sql`. Also
check the file has no `USE fxdb;` line: Liquibase is already connected to the right database.

**Data seeded twice.** You ran the seed both ways — the init-script mount is still in compose.
Remove it and `down -v`.

**Everything hangs on startup with no error.** That's break #3, for real —
`Waiting for changelog lock....` every 10s. Check `DATABASECHANGELOGLOCK` and release it. Usually
caused by someone Ctrl-C'ing an app mid-migration.

**I set the lock and the app started anyway.** Your database had no pending change sets, so
Liquibase had nothing to do and didn't need the lock. Add a change set first, then retry.

**Liquibase is silent in the logs.** Bump it: `logging.level.liquibase=INFO`.
</details>
