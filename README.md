# fgc-tv

A static site aggregating every **Capcom Pro Tour 2026 World Warrior** and
**Street Fighter League** broadcast across all 25 regions, rebuilt every few
hours from YouTube Atom feeds. Unofficial fan project, not affiliated with Capcom.

The point is navigation: these broadcasts are scattered across 18 organizer
channels in a dozen languages, and YouTube gives you no way to see them as one
list. This does.

## How it works

```
ww2026.edn ──┐
channels.edn ├─→ poll.clj ──→ state/videos.jsonl ──→ render.clj ──→ public/
patterns.edn ┘   (18 GETs)      (append-mostly)       (no network)
```

`run.clj` does both and exits — no loop, no daemon. It is what the scheduled
GitHub Action runs.

## Why state exists

The Atom feed returns only the **15 most recent items** per channel and supports
no paging. These channels post non-tournament content between events — Capcom
Fighters alone posts several Shorts a day — so broadcasts roll off fast. A
measured example: *"Top 8 - US Midwest 4 - World Warrior 2026"* was already gone
from the feed **five days** after it aired.

Without persistence the site silently loses history. `state/videos.jsonl` must
survive between runs.

## Quick start

```bash
mise run poll
mise run render
mise run serve

# or just
mise run
```

Nothing to install beyond [babashka](https://babashka.org/) — `bb.edn` declares
no dependencies, so there is no maven fetch and no JVM required.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `STATE_PATH` | `./state/videos.jsonl` | Video store. **Must persist between runs.** Absent = empty, not an error. |
| `OUTPUT_DIR` | `./public` | Generated site. Overwritten each run. |
| `CHANNELS_PATH` | `./channels.edn` | Resolved channel IDs (committed artifact). |
| `SCHEDULE_PATH` | `./ww2026.edn` | Parsed Capcom schedule. |
| `PATTERNS_PATH` | `./patterns.edn` | Classification regexes. |
| `SITE_URL` | `https://example.invalid` | Public origin, used for OpenGraph and the Atom feed. **Set this in production.** |
| `USER_AGENT` | `fgc-tv/1.0 (…)` | Sent on every feed request. |
| `REQUEST_DELAY_MS` | `1200` | Pause between feeds. |
| `MAX_RETRIES` | `3` | Backoff attempts on 429/5xx before giving up on a feed. |

## Deployment: GitHub Pages

`.github/workflows/pages.yml` does the whole thing on a schedule — no server, no
container, no secrets:

1. installs the pinned babashka,
2. runs `bb run.clj` (poll → render),
3. **commits `state/videos.jsonl` back to the repo**,
4. uploads `public/` and deploys it to Pages.

The state file lives in git. That is the entire persistence layer, and it is the
reason this can run on ephemeral runners at all — see *Why state exists* above.
It also means the site's history is diffable and hand-editable.

One-time setup: **Settings → Pages → Source → GitHub Actions**. Nothing else.
Then trigger the first run from the Actions tab.

### Notes

- `SITE_URL` comes from `actions/configure-pages`, so a project site's
  `/repo-name` prefix is handled without hardcoding anything. All in-site links
  are relative, so it works at a subpath and at a custom domain unchanged.
- Commits pushed with `GITHUB_TOKEN` do not trigger workflows, so the state
  commit cannot loop back into another build.
- `concurrency` serialises runs — two builds racing on the state commit would
  lose a poll.
- GitHub disables scheduled workflows after 60 days of repository inactivity.
  The state commits count as activity, so in practice this only bites if every
  run is a no-op for two months.
- `robots.txt` is only honoured at a domain root. On a project Pages site it
  lands at `/repo-name/robots.txt` and crawlers ignore it — `_debug.html` still
  carries `noindex`, which is the part that actually matters.

## Exit codes

| Code | When |
|---|---|
| `0` | Success, **including partial feed failures**. One dead channel logs a warning and does not fail the run. |
| `1` | Total failure: every feed failed, a required file is missing, state is unreadable, or the render threw. |

State writes are atomic (temp file + rename), so a run killed mid-write leaves
the previous store intact.

## Tuning the classifier

`patterns.edn` holds every regex. Titles are classified as `:world-warrior`,
`:sfl`, `:premier` or `:other`; only the first three are shown publicly.

**Classification is a pure function over stored state.** Edit a pattern, run
`bb render.clj`, and history is reclassified retroactively — no refetch, no
network.

`public/_debug.html` lists everything the filter rejected, so you can see what
you are missing. It is `noindex` and excluded in `robots.txt`, and there is
deliberately no `?all=1` toggle on the public page.

### `:premier` is an addition, not in the original spec

The same channels broadcast the CPT Premier stops and majors (CEO, Evo, Esports
World Cup, Combo Breaker, BAM). Those are not World Warrior or SFL, but they are
the biggest tournament VODs these channels publish. Delete the `:premier` rule
from `patterns.edn` to get a strict World Warrior + SFL site.

## Regenerating channel IDs

`resolve_channels.clj` is a **local one-shot** whose output is committed. It is not
part of the scheduled build and must never run on a schedule.

```bash
bb resolve_channels.clj
```

It scrapes each channel page for `"externalId":"UC…"` (falling back to the
canonical `/channel/UC…` link). Note that the older `"channelId":"UC…"` key that
most scrapers look for **no longer appears** in YouTube's channel HTML.

Two regions have no YouTube channel and are modelled explicitly rather than
dropped, so they still render with a note:

- `:chile` — Twitch only (`twitch.tv/UAFG`, shared with South America East)
- `:middle-east` — no organizer or channel listed by Capcom at all

## Known limits of the data source

Verified against live feeds, 2026-08-15:

- **15 items per feed, no paging.** Hence the state file.
- **Scheduled streams usually appear before airing.** Confirmed: a CEO 2026 Day 3
  stream scheduled for 8/16 was in the feed from 8/13. But it is neither instant
  nor guaranteed — two streams scheduled on Capcom Fighters JP were absent from a
  cache-busted fetch. Treat "Upcoming" as best-effort.
- **Lookahead is ~2–3 days, not weeks.** Capcom creates broadcast placeholders
  shortly before airtime, so the feed can never show the full season. The
  schedule section from `ww2026.edn` covers the long horizon.
- **No start time, no live flag.** The feed carries no `yt:liveBroadcastContent`
  and no scheduled start time; `<published>` is the video's *creation* time. The
  only available signal is `media:statistics views="0"`, which is what drives the
  "upcoming" tag — advisory, since a brand-new upload also reads as zero briefly.
- **Conditional requests do nothing.** No `Last-Modified`, no `ETag`;
  `If-Modified-Since` returns `200` with a full body. The header is sent anyway
  in case that changes. Each run is 18 full fetches, roughly 500 KB total.

### YouTube coverage is patchy, and that is not a bug here

Several regions are Twitch-first and treat YouTube as an occasional VOD dump.
As of 2026-08-15, Saltmine League (4 EU regions) had posted nothing to YouTube
since 2026-05-13, and Union Argentina (South America East) nothing since 2024.
Those regions render with their schedule and Twitch link but few or no videos.
That reflects reality — the alternative is a page that pretends the VODs exist.

## Dates

Capcom publishes schedule dates as bare `M/D` with no year and no timezone.
They are rendered exactly as published rather than guess-converted, because
inventing precision is worse than showing `5/16`. Video timestamps are real
instants and are rendered in UTC, since the audience is global.

## Files

| File | Role |
|---|---|
| `run.clj` | Entrypoint: poll then render |
| `poll.clj` | Fetch feeds, update state. `--prune-before <date>` for manual cleanup |
| `render.clj` | Build the site from state, offline |
| `resolve_channels.clj` | Local one-shot, writes committed `channels.edn` |
| `patterns.edn` | Classification regexes, hand-editable |
| `channels.edn` | Resolved channel IDs, committed artifact |
| `ww2026.edn` | Parsed Capcom schedule (input, hand-maintained) |
| `src/fgc/` | Library code: state, feed, classify, render, html |
| `serve.clj` | Local preview server (bundled http-kit, no deps) |
| `.github/workflows/pages.yml` | Scheduled build, state commit, Pages deploy |

## Output

| Path | |
|---|---|
| `index.html` | The site |
| `feed.xml` | Atom feed of newly-seen broadcasts, ordered by discovery |
| `_debug.html` | Everything the filter rejected (`noindex`) |
| `robots.txt` | Allows all, disallows `_debug.html` |
| `.nojekyll` | Stops Pages from swallowing `_debug.html` for its leading underscore |
