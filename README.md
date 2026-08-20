# FX exchange — a build-it-yourself mini-project

Five steps that take one Spring Boot application from a jar on your laptop to a three-service
containerised stack, tested and gated by a pipeline that publishes images to a container registry
behind a human approval gate.

You write the code. Each step has an instruction sheet, and a complete working answer you should
only open after you have struggled with it.

## The arc

| Step | Sheet | You end up with |
|---|---|---|
| 1 | [Put it in a box](steps/01-dockerfile.md) | a multi-stage `Dockerfile` and a `.dockerignore`, and an image that runs with no JDK on the host |
| 2 | [The whole stack in one file](steps/02-compose-mysql.md) | `docker-compose.yml` — the app plus a healthchecked MySQL, one command |
| 3 | [A second app, and the port fight](steps/03-monitor-and-ports.md) | `fx-monitor` in the stack, and a lesson about host ports |
| 4 | [The machine runs the tests](steps/04-ci-pipeline.md) | `ci.yml` running your existing tests, a deliberately red PR, and branch protection that blocks it |
| 5 | [From green build to blessed deploy](steps/05-cd-ghcr.md) | `cd.yml` publishing two images to GHCR, gated on a human |

Overview deck with checkpoints and a cheatsheet: [steps 1–5](decks/overview-steps-01-05.html).
Architecture diagrams: [the click-through deck](decks/architecture-steps-01-05.html) or
[the plain images](decks/diagrams/).

## Layout

```
mini-project/
├── start/        the code as you receive it — copy this to begin
├── given/        an application you don't write; it arrives at step 3
├── steps/        the five instruction sheets
├── solutions/    step-01 … step-05, each a complete working tree
└── decks/        overview and architecture decks, plus diagrams
```

`solutions/step-0N/` is the **end state of step N**, not a patch — every one of them runs on its
own. Use them as a parachute if a step goes wrong: copy the previous step's folder over your work
and carry on rather than losing an hour to a bad merge.

Once you've done the "Getting started" step below, `fx-exchange/` appears here too, as a new
sibling of `start/`, `given/`, `steps/` and `decks/` — that folder is your workspace, and
everything you build lives inside it.

## Getting started

You need a GitHub account. **Fork this repository** into your own account, then open your fork —
either in a **GitHub Codespace** (Code → Codespaces → Create codespace on main) or cloned locally.
Either way, you already have git initialized, `origin` pointing at your fork, and `main` holding
everything you're reading right now.

A Codespace already has Docker and a JDK on it — confirm with `docker --version` and
`java -version` before you start. Working locally instead, you need Docker Desktop and a JDK 21 of
your own. Either way you do **not** need Gradle or MySQL installed — the Gradle wrapper and the
containers handle both.

From the root of your fork, make the workspace you'll build everything in:

```bash
mkdir fx-exchange
cp -R start/fx-app-spring fx-exchange/
cp start/README.md fx-exchange/README.md
cd fx-exchange
```

Then open [steps/01-dockerfile.md](steps/01-dockerfile.md).

Work on a branch per step (`step-01`, `step-02`, …) and merge each one to your fork's `main`
through a pull request. It costs nothing while you are alone in the repo and is the only habit
that scales once you are not — and from step 4 onwards the pipeline depends on it.

## What you are building

Two applications that run side by side as one stack:

| Folder | What it is | Arrives at |
|---|---|---|
| `fx-app-spring/` | the API and its database — rates, conversions, and its own live rate feed | step 1 (you build it) |
| `fx-monitor/` | live web view of the rates, static files behind nginx | step 3 (given) |

By step 5 that is three containers, one database, two published images and a deploy that waits
for someone to say yes.

## A note on ports

The stack publishes host ports 8080 (API), 3000 (monitor) and 3307 (the database). If something
else on your machine already holds one, don't edit `docker-compose.yml` — copy `.env.example` to
`.env` and override it there:

```bash
MONITOR_PORT=3100
```

`docker compose up` fails with *"port is already allocated"* when this bites, and a container that
cannot bind its port simply does not start — so check `docker compose ps` before you assume the
code is wrong.
