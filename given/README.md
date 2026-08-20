# Given applications

One application you do not write. It arrives part-way through the sequence and you
containerise it, wire it into the stack, and build against its contract.

| Folder | Arrives at | What it is |
|---|---|---|
| `fx-monitor/` | step 3 | a read-only live web view of the rates, static files behind nginx |

Each step sheet tells you when to copy one into your workspace root:

```bash
cp -R ../given/fx-monitor .
```
