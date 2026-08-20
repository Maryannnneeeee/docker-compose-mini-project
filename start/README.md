# FX exchange

**Name:** _<your name here>_

The API and its database. Right now it is one application in one folder, built by Gradle and
run from your IDE or the command line against a MySQL you have to provide yourself.

Over five steps it becomes a containerised stack of two applications, backed by a seeded
database, tested and gated by a pipeline that publishes images to a registry.

| Folder | What it is | Built by |
|---|---|---|
| `fx-app-spring/` | the API and its database | you |

Start with `steps/01-dockerfile.md`.
