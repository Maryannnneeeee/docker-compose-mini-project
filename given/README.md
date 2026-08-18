# Given applications

Two applications you do not write. They arrive part-way through the sequence and you
containerise them, wire them into the stack, and build against their contract.

| Folder | Arrives at | What it is |
|---|---|---|
| `fx-monitor/` | step 04 | a read-only live web view of the rates, static files behind nginx |
| `fx-orchestrator/` | step 05 | the upstream rate feed, a Spring app with its own database |

Each step sheet tells you when to copy one into your workspace root:

```bash
cp -R ../given/fx-monitor .
```
