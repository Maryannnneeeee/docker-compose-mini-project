# Diagrams — the system after each step

Twelve images, in order. Drop them straight into slides, print them, or just open the folder.

| Image | Shows |
|---|---|
| `00-title.png` | how to read the diagrams (the legend) |
| `01-where-we-start.png` | a jar and four hand-installed things on one laptop |
| `02-step1-dockerfile.png` | the app in a container, and why it can't reach a database |
| `03-step2-compose-mysql.png` | app + MySQL on one compose network |
| `04-step3-liquibase.png` | Liquibase builds the schema; the seed mount is gone |
| `05-step4-monitor-ports.png` | nginx serves the page and proxies `/api` |
| `06-step5-orchestrator.png` | five services, two databases, the loop closed |
| `07-feed-loop-sequence.png` | the rate feed as a sequence, incl. the DECLINED branch |
| `08-done-fx-monitor.png` | what "done" looks like in the browser after step 5 |
| `09-title-tests-pipeline.png` | the legend for the testing and pipeline half |
| `10-test-pyramid.png` | the three altitudes — unit, slice, integration |
| `11-ci-cd-pipeline.png` | CI gates the merge, CD publishes three images and waits for a human |

Diagrams 00–08 cover steps 1–5; 09–11 cover steps 6–9.

These are C4 **container** diagrams: a "container" is a separately runnable thing (an app, a
database, a web server). It lines up with a Docker container here, which is convenient but a
coincidence.

## Regenerating them

The two `architecture-steps-*.html` decks in the parent folder are the source — every diagram is
inline SVG in there. Open a deck to present it (arrow keys or the on-screen controls) or to edit a
diagram; the PNGs are exports.

`08-done-fx-monitor.png` is the exception: it embeds `../fx-monitor-final.jpg`, a real screenshot
of `fx-monitor` taken against the running stack with about 90 seconds of feed history behind it.
To retake it, bring up `solutions/step-09`, leave the page open for a minute or two so the
sparklines fill, then capture the full page.
