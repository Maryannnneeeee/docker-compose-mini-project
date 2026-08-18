# FX exchange — a build-it-yourself mini-project

Nine steps that take one Spring Boot application from a jar on your laptop to a three-service
containerised stack with a migrated schema, three tiers of tests, and a pipeline that publishes
images to a container registry behind a human approval gate.

You write the code. Each step has an instruction sheet, and a complete working answer you should
only open after you have struggled with it.

## The arc

| Step | Sheet | You end up with |
|---|---|---|
| 1 | [Put it in a box](steps/01-dockerfile.md) | a multi-stage `Dockerfile` and a `.dockerignore`, and an image that runs with no JDK on the host |
| 2 | [The whole stack in one file](steps/02-compose-mysql.md) | `docker-compose.yml` — the app plus a healthchecked MySQL, one command |
| 3 | [Liquibase owns the schema](steps/03-liquibase.md) | the schema as code; the hand-imported seed script is gone |
| 4 | [A second app, and the port fight](steps/04-monitor-and-ports.md) | `fx-monitor` in the stack, and a lesson about host ports |
| 5 | [The orchestrator loop](steps/05-orchestrator-feed.md) | a live rate feed pushing batches, an ACK contract, and a toggle that changes an upstream service's behaviour |
| 6 | [The three altitudes](steps/06-test-tiers.md) | a mocked unit test, a `@WebMvcTest` slice, and a Testcontainers integration test |
| 7 | [The machine runs the tests](steps/07-ci-pipeline.md) | `ci.yml`, a deliberately red PR, and branch protection that blocks it |
| 8 | [Supply chain & Definition of Done](steps/08-supply-chain.md) | a dependency inventory, Dependabot as code, and a five-point DoD |
| 9 | [From green build to blessed deploy](steps/09-cd-ghcr.md) | `cd.yml` publishing three images to GHCR, gated on a human |

Two overview decks with checkpoints and cheatsheets: [steps 1–5](decks/overview-steps-01-05.html)
and [steps 6–9](decks/overview-steps-06-09.html). Architecture diagrams:
[1–5](decks/architecture-steps-01-05.html), [6–9](decks/architecture-steps-06-09.html).

## Layout

```
mini-project/
├── start/        the code as you receive it — copy this to begin
├── given/        two applications you don't write; they arrive at steps 4 and 5
├── steps/        the nine instruction sheets
├── solutions/    step-01 … step-09, each a complete working tree
└── decks/        overview and architecture decks, plus diagrams
```

`solutions/step-0N/` is the **end state of step N**, not a patch — every one of them runs on its
own. Use them as a parachute if a step goes wrong: copy the previous step's folder over your work
and carry on rather than losing an hour to a bad merge.

## Getting started

You need Docker Desktop, a JDK 21, and a GitHub account. You do **not** need Gradle or MySQL
installed — the Gradle wrapper and the containers handle both.

```bash
mkdir fx-exchange && cd fx-exchange
git init -b main
cp -R ../mini-project/start/. .
```

Then open [steps/01-dockerfile.md](steps/01-dockerfile.md).

Work on a branch per step (`step-01`, `step-02`, …) and merge each one to `main` through a pull
request. It costs nothing while you are alone in the repo and is the only habit that scales once
you are not — and from step 7 onwards the pipeline depends on it.

## What you are building

Three applications that run side by side as one stack:

| Folder | What it is | Arrives at |
|---|---|---|
| `fx-app-spring/` | the API and its database — rates, conversions, feed intake | step 1 (you build it) |
| `fx-monitor/` | live web view of the rates, static files behind nginx | step 4 (given) |
| `fx-orchestrator/` | upstream rate feed with its own database | step 5 (given) |

By step 9 that is five containers, two databases, three published images and a deploy that waits
for someone to say yes.

## A note on ports

The stack publishes host ports 8080 (API), 3000 (monitor), 8081 (feed), 3307 and 3308 (the two
databases). If something else on your machine already holds one, don't edit `docker-compose.yml` —
copy `.env.example` to `.env` and override it there:

```bash
MONITOR_PORT=3100
ORCH_PORT=8091
```

`docker compose up` fails with *"port is already allocated"* when this bites, and a container that
cannot bind its port simply does not start — so check `docker compose ps` before you assume the
code is wrong.
