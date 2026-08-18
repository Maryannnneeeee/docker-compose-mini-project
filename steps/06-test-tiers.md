# Step 6 — The three altitudes (~90 min)

*[← all steps](../README.md) · [diagrams](../decks/architecture-steps-06-09.html) · [overview & cheatsheet](../decks/overview-steps-06-09.html) · [answer](../solutions/step-06/) — struggle first*

### Why we're doing this

In steps 1–5 you put the exchange in a box: one `docker compose up` raises the API, a
Liquibase-owned MySQL, a monitor and an upstream feed, all reproducible on a laptop that has
none of them installed. It *runs*. What you don't have yet is anything that tells you it's
still **correct** after your next change — nothing but you, clicking around, remembering to
check. Here the machines take over the checking.

Not every test needs to boot the world, and that is the whole idea. A test that starts Spring,
dials MySQL and does an HTTP round-trip just to check a rounding rule is testing the rounding
*and* Spring *and* MySQL *and* the network — when it goes red you have four suspects instead of
one. So you build **three altitudes**:

- a **unit** test that mocks the database and checks the arithmetic in milliseconds — you'll
  write hundreds of these in your life;
- a **slice** test that boots only the web layer and checks HTTP status and JSON, in a second
  or two — you'll write a dozen;
- an **integration** test that boots a real MySQL in a throwaway container and checks your
  actual SQL, in tens of seconds — you'll write two or three.

That shape is the test pyramid, and it's economics as much as engineering: a twenty-minute
suite is a suite that quietly stops being run.

**Skills you're building**
- `@ExtendWith(MockitoExtension.class)` + `@Mock` — pure JUnit, no Spring context
- `@WebMvcTest` + `MockMvc` + `@MockBean` — the web layer alone, everything under it faked
- `@SpringBootTest` + `@Testcontainers` + `MySQLContainer` — a real database, born for the test and thrown away after
- The **two-tier split**: `*Test` on `./gradlew test` (fast, Docker-free) vs `*IT` on `./gradlew build` (real DB) — and why you want both commands
- Reading a green build closely enough to notice when part of it *didn't actually run*

### What you're producing

Three new test classes under `src/test/java/com/fx/api/…` — one at each altitude — plus the
`build.gradle` wiring that makes the split work. Observable end state: `./gradlew test` is green
with **MySQL and Docker both off**; `./gradlew build` is green with **Docker on** and boots a real
MySQL for the integration test.

---

### Step-by-step

**0. Back to your own repo, then branch.**

Everything happens in the `fx-exchange` workspace you have grown since Step 1 — the same one,
not a fresh one. Get onto a clean, known-good `main` and cut the branch:

```bash
cd fx-exchange
git switch main && git pull
git switch -c step-06
```

> **Recovery.** If your repo is behind or broken, `solutions/step-05/` is the parachute — a
> complete, working copy of exactly the state Step 5 ends in. Copy it over and carry on. You
> should never lose an hour to a bad merge; that is what the solution folders are for.

Everything below happens **inside `fx-app-spring/`** — the API subfolder — unless a step says
otherwise:

```bash
cd fx-app-spring
```

**1. The unit altitude first — arithmetic, no Spring.**

Create `src/test/java/com/fx/api/ConversionServiceTest.java`. Annotate the class with
`@ExtendWith(MockitoExtension.class)`, declare `@Mock RateRepository repo`, and **do not import
a single Spring class**. This test must run whether Spring exists or not.

```java
package com.fx.api;

import com.fx.api.model.Rate;
import com.fx.api.repo.RateRepository;
import com.fx.api.service.ConversionService;
import com.fx.api.service.UnknownPairException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversionServiceTest {

    @Mock RateRepository repo;

    @Test void convertsAndRoundsToTwoDecimals() {
        when(repo.findLatestForPair("EUR", "USD"))
            .thenReturn(Optional.of(new Rate(1, "EUR", "USD", 1.0818, LocalDate.of(2026, 1, 12), null)));
        var result = new ConversionService(repo).convert("EUR", "USD", 123.45);
        assertEquals(133.55, result.converted(), 1e-9);   // 133.5482 -> 133.55
        assertEquals("EUR/USD", result.pair());
        assertEquals(1.34, result.fee(), 1e-9);            // retail 1% of 133.55, rounded
        assertEquals(132.21, result.net(), 1e-9);
    }

    @Test void unknownPairThrows() {
        when(repo.findLatestForPair("AAA", "BBB")).thenReturn(Optional.empty());
        assertThrows(UnknownPairException.class,
            () -> new ConversionService(repo).convert("AAA", "BBB", 10));
    }
}
```

The `@Mock RateRepository` never touches a database — it returns exactly what you told it to.
So this test isolates one thing: does `ConversionService` do the sum and the rounding right, and
does it fail gracefully on a pair the exchange doesn't know? `Rate` is the six-field record you
finished in Step 5; the last argument, `capturedAt`, is `null` here because the calculator never
looks at it.

**Checkpoint.** Run `./gradlew test` and find your two new tests among the results —
`com.fx.api.ConversionServiceTest`, both green, in milliseconds. (Target them precisely with
`-Dtest='ConversionServiceTest#convertsAndRoundsToTwoDecimals+unknownPairThrows'` if you like;
plain `-Dtest=ConversionServiceTest` also matches the older `com.fx.core` one of the same name.)
No MySQL, no Docker, no Spring banner in that pair's log.

**2. Up one altitude — the web slice.**

Create `src/test/java/com/fx/api/web/RateControllerTest.java`. `@WebMvcTest(RateController.class)`
builds **only** the web layer — the controller, its `@RestControllerAdvice`, the JSON
serialiser and the request validator — and nothing below it. Everything the controller depends
on you replace with a `@MockBean`:

```java
package com.fx.api.web;

import com.fx.api.model.ConversionResult;
import com.fx.api.model.Rate;
import com.fx.api.repo.RateRepository;
import com.fx.api.service.ConversionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RateController.class)
class RateControllerTest {

    @Autowired MockMvc mvc;
    @MockBean RateRepository rates;
    @MockBean ConversionService conversions;

    @Test void listsLatestRates() throws Exception {
        when(rates.findLatest()).thenReturn(List.of(
            new Rate(25, "EUR", "USD", 1.0818, LocalDate.of(2026, 1, 12), null)));
        mvc.perform(get("/api/rates"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].baseCode").value("EUR"))
           .andExpect(jsonPath("$[0].rate").value(1.0818));
    }

    @Test void unknownPairIs404WithErrorBody() throws Exception {
        when(rates.findLatestForPair("XX", "YY")).thenReturn(Optional.empty());
        mvc.perform(get("/api/rates/XX/YY"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").exists());
    }

    @Test void validConversionReturns201() throws Exception {
        when(conversions.convert(eq("EUR"), eq("USD"), anyDouble()))
            .thenReturn(new ConversionResult("EUR/USD", 100, 1.0818, 108.18, 1.08, 107.10));
        mvc.perform(post("/api/conversions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"base\":\"EUR\",\"quote\":\"USD\",\"amount\":100}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.converted").value(108.18));
    }

    @Test void negativeAmountIs400EvenThoughServiceIsMocked() throws Exception {
        mvc.perform(post("/api/conversions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"base\":\"EUR\",\"quote\":\"USD\",\"amount\":-5}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").exists());   // validation lives in the web layer
    }
}
```

The last test is the load-bearing one. `ConversionService` is mocked — it never runs — and yet
`amount: -5` still returns **400**. That's the proof that `@Valid` rejects a bad body *before*
the controller method executes: validation lives in the web layer itself. Prove it once and
you'll never again wonder where validation runs.

**Checkpoint.** `./gradlew test` now runs the four slice tests too. Still no MySQL, still no
Docker — the slice mocks everything below the controller, so a database it never reaches can't
break it.

**3. The third altitude — the one the mocks can't give you.**

Mocked tests are fast, but a mock can't catch a broken *query*: a wrong column name, a bad
"latest per pair" subquery, a type mismatch on `captured_at`. For that you need a real database.
And you already know how to conjure one on demand — in Step 2 you ran MySQL in a container.
Testcontainers just does that from **inside a Java test**: it starts `mysql:8`, hands your app
the connection details, runs your assertions, and destroys the container when the test ends.

First, wire `fx-app-spring/build.gradle` for a second test task. Add the version override:

```groovy
ext['testcontainers.version'] = '1.21.4'
```

two **test-scoped** dependencies:

```groovy
testImplementation 'org.testcontainers:mysql'
testImplementation 'org.testcontainers:junit-jupiter'
```

and the split itself:

```groovy
tasks.named('test') {
    filter { excludeTestsMatching '*IT' }
}

tasks.register('integrationTest', Test) {
    description = 'Runs integration tests (*IT) against containers. Requires a Docker daemon.'
    group       = 'verification'
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath       = sourceSets.test.runtimeClasspath
    filter { includeTestsMatching '*IT' }
    shouldRunAfter tasks.named('test')
}

tasks.named('check') { dependsOn tasks.named('integrationTest') }
```

> **Gradle has no surefire/failsafe convention — you write the split yourself.** Maven ships two
> plugins that already know `*Test` from `*IT`; Gradle ships one `test` task that runs everything
> it finds. The three blocks above are what buy you the same two tiers: `test` excludes `*IT` and
> stays fast, a second `Test` task runs only `*IT`, and hanging it off `check` means `./gradlew
> build` runs both. Two commands, two tiers: the fast one you can run on a locked-down laptop on
> a train, the full one that needs Docker.

> **The Testcontainers version is not optional — and getting it wrong does not turn your build
> red.** Spring Boot 3.3.4 manages Testcontainers to 1.19.8, whose bundled docker-java speaks an
> old Docker API. Modern engines (Docker 28/29+) reject it with **HTTP 400 → "Could not find a
> valid Docker environment."** Here is the trap: Testcontainers cannot tell that apart from
> "this laptop has no Docker", so with `disabledWithoutDocker = true` your integration test is
> **silently skipped and the build says BUILD SUCCESSFUL.** A green tick over zero database
> coverage. Because 1.19.8 is what you get by default, this one line is the difference between a
> test that runs and a test that only looks like it does. You will meet this trap again, on
> purpose, in Step 8.

**4. Write the integration test.** Create `src/test/java/com/fx/api/repo/RateRepositoryIT.java` —
note the `IT` suffix, that's what routes it to `integrationTest` rather than `test`:

```java
package com.fx.api.repo;

import com.fx.api.model.Rate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RateRepositoryIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8")
            .withDatabaseName("fxdb");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired RateRepository repo;

    @Test void latestReturnsTheTenPairsOfTheNewestDate() {
        assertThat(repo.findLatest()).hasSize(10);
    }

    @Test void eurUsdIsExactlyTheSeededRate() {
        Optional<Rate> eurUsd = repo.findLatestForPair("EUR", "USD");
        assertThat(eurUsd).isPresent();
        assertThat(eurUsd.get().rate()).isCloseTo(1.0818, within(1e-9));
    }

    @Test void unknownPairIsEmptyNotAnError() {
        assertThat(repo.findLatestForPair("AAA", "BBB")).isEmpty();
    }
}
```

Read the container line closely, because this is the part everybody gets wrong. The container
starts an **empty** `fxdb` — no seed file mounted, nothing copied in. So where does the data come
from? **Your own Liquibase changelog.** On startup the app runs the exact migrations from Step 3
that create and seed the schema — the same ones that run in dev and in production. `@DynamicPropertySource`
points Spring at the container's random port before the context boots; the app connects, Liquibase
builds `fxdb` from your repo, and only then do your assertions run against real SQL.

> That is why there is no `fxdb-seed.sql` anywhere in this test. In Step 3 you made the schema
> **code** — versioned change sets in `src/main/resources/db/changelog/`. A test that mounted a
> separate seed file would be testing a *different* database shape than the one you ship. This
> test gets its schema the same way every environment does, which is the only way it can prove
> anything about production.

**5. Run both commands, then don't trust the green.**

```bash
./gradlew test     # Docker off is fine — unit + slice only
./gradlew build    # Docker ON — integrationTest adds RateRepositoryIT against a real MySQL
```

Watch `build` pull `mysql:8`, boot it, let Liquibase seed it, run the three IT assertions, then
throw the container away.

> Gradle caches test results. If a run says `UP-TO-DATE` and prints no counts, nothing actually
> executed — add `--rerun-tasks` when you want to watch it happen.

Now **read** the output — a skipped IT and a passing IT produce the same green tick. Find this
line:

```
integrationTest: 3 tests, 3 passed, 0 failed, 0 skipped
```

If it says `0 tests` or `3 skipped`, your integration tier never ran. Two causes, in order of
likelihood: Docker Desktop isn't running, or your `testcontainers.version` is not `1.21.4`. Scroll up for
`Could not find a valid Docker environment` — with Docker clearly running, that message means the
version. **A green build you haven't read is not evidence.**

**Checkpoint.** With **MySQL and Docker off**:

```
$ ./gradlew test
...
test: 29 tests, 28 passed, 0 failed, 1 skipped
BUILD SUCCESSFUL
```

29 run, none failed. The one skip is a pre-existing `@Disabled` in `com.fx.core.CurrencyConverterTest`
— **not** your IT (the IT is `*IT`, so the `test` task filters it out entirely). Then with **Docker on**:

```
$ ./gradlew build
...
integrationTest: 3 tests, 3 passed, 0 failed, 0 skipped
...
BUILD SUCCESSFUL
```

The same 29 from the fast tier, **plus** 3 integration tests green against a real MySQL that
`findLatest()` returns exactly **10** rows from and where EUR/USD is **1.0818**. That combination
— fast tier green with nothing installed, full tier green with a real database, and you having
*read* that the IT ran — is the pass mark.

**6. Ship it.** The work is done and proven — now land it on `main`, the same way every change
lands on `main` for the rest of this course.

```bash
git add -A
git commit -m "test: three altitudes (unit, web slice, Testcontainers IT)"
git push -u origin step-06
```

Open a pull request from `step-06` into `main` on GitHub, and merge it. Then bring your local
`main` back in line before you start the next task:

```bash
git switch main
git pull
```

> One branch per exercise, every exercise merged through a PR. Nobody commits straight to `main` —
> not while you are alone in the repo, and least of all when you are not.

<details>
<summary>Stuck?</summary>

**`./gradlew build` reports `0 tests` or `3 skipped` on the IT.** Docker Desktop isn't running, or
`testcontainers.version` isn't `1.21.4`. Look for `Could not find a valid Docker environment` in
the log — with Docker running, that is the version, not the daemon.

**The slice test suddenly wants a `DataSource`.** Something in the web layer is reaching the repo
directly instead of going through the mocked service. `@WebMvcTest` only builds the web layer —
anything the controller touches must be `@MockBean`ed. You already mock `RateRepository`; if it
still complains, a bean is being pulled in by component scan — narrow the slice to
`@WebMvcTest(RateController.class)`.

**`./gradlew test` tries to start a MySQL container.** Your integration test is named `…Test`,
not `…IT`, so the `test` task's `excludeTestsMatching '*IT'` filter never excluded it. Rename it
to end in `IT`.

**Unit test red on the fee — got `1.35` not `1.34`.** You're rounding the fee off the *unrounded*
converted amount. Fee is 1% of the **rounded** `converted` (133.55), then rounded again.

**`./gradlew build` hangs pulling `mysql:8`.** First run downloads the image; give it a minute.
After that it's cached and the container starts in seconds.

**`./gradlew build` says `UP-TO-DATE` and prints no counts.** Gradle cached the previous result
and ran nothing. `./gradlew build --rerun-tasks` forces it.
</details>
