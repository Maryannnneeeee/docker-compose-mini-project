# Diagrams — the system after each step

Eight images. Drop them straight into slides, print them, or just open the folder.

| Image | Shows |
|---|---|
| `00-title.png` | how to read the diagrams (the legend) |
| `01-where-we-start.png` | a jar and three hand-installed things on one laptop |
| `02-step1-dockerfile.png` | the app in a container, and why it can't reach a database |
| `03-step2-compose-mysql.png` | app + MySQL on one compose network |
| `04-step3-monitor-ports.png` | nginx serves the page and proxies `/api` |
| `05-ci-pipeline.png` | a PR runs `ci.yml`; branch protection gates the merge |
| `06-cd-pipeline.png` | `ci.yml` narrowed to PRs, `cd.yml` ships two images and waits for a human |
| `07-done-fx-monitor.png` | what "done" looks like in the browser after step 5 |

These are C4 **container** diagrams: a "container" is a separately runnable thing (an app, a
database, a web server). It lines up with a Docker container here, which is convenient but a
coincidence.

## Regenerating them

`architecture-steps-01-05.html` in the parent folder is the source — every diagram is inline SVG
in there. Open the deck to present it (arrow keys or the on-screen controls) or to edit a diagram;
the PNGs are exports.

`07-done-fx-monitor.png` is the exception: it's a screenshot, not SVG. `../fx-monitor-final.jpg`
is `solutions/step-05` running with nothing else built — bring up that stack and capture the full
page at `http://localhost:3000`.
