# Handoff: CPT World Warrior / SFL broadcast aggregator

## Context

`ww2026.edn` (in repo root) contains the parsed CPT 2026 World Warrior schedule,
extracted from Capcom's official schedule page. Shape:

```clojure
[{:region :europe-west
  :organizer "saltmineleague"
  :countries "Austria, Belgium, Italy, ..."
  :youtube ["https://www.youtube.com/@SaltmineLeague"]
  :twitch  ["https://www.twitch.tv/saltmineleague"]
  :events  [{:round :r1 :date "5/16" :entry "https://www.start.gg/..."}
            {:round :finals :date "10/31" :entry "..."}]}
 ...]
```

25 regions, 143 events, 22 YouTube URLs resolving to **14 distinct channels**
(Saltmine covers 4 EU regions, Capcom Fighters covers 3 US regions).

Goal: a **public static site** showing every World Warrior and Street Fighter
League broadcast across all regions — live now, upcoming, and recent VODs —
regenerated hourly from YouTube Atom feeds.

## Stack and execution model

Babashka. Static HTML output. No frameworks, no build step, no JS bundler.

**The build runs inside an ephemeral container.** Assume:
- No persistent local filesystem between runs.
- No interactive terminal. No prompts. Ever.
- All configuration via environment variables, with sane defaults.
- Scheduling and hosting are handled externally — do not write cron jobs,
  launchd plists, systemd units, or deploy scripts. Produce a
  `Dockerfile` and a single entrypoint that runs to completion and exits.
- Exit non-zero only on total failure (state unreadable, zero feeds reachable,
  render failed). Partial feed failures log a warning and exit zero — one dead
  channel must not take down the site.

Pin the Babashka version in the Dockerfile. Keep the image small.

## Phase 0 — verify the core assumption before building anything

**Do this first and report back before writing the app.** The whole design
rests on YouTube's Atom feed, which has two properties that need confirming:

1. The feed is `https://www.youtube.com/feeds/videos.xml?channel_id=<UCxxx>`.
   It returns **only the ~15 most recent items**. Confirm this.
2. **Unknown: whether scheduled-but-not-yet-live streams appear in the feed at
   all.** Historically the feed publishes on video creation, so scheduled
   livestreams often *do* show up early — but this is inconsistent and may
   have changed. Test against `@CapcomFightersJP`, which has SFL Pro-JP
   broadcasts scheduled (Division S opens 8/25, Division F 8/28, 18:40 JST).
   Fetch the feed and check whether those upcoming streams are present.

If scheduled streams are absent from the feed, say so and stop — the "upcoming
broadcasts" feature needs a different data source (YouTube Data API
`search?eventType=upcoming`, quota-limited and needs a key) and I want to
decide that tradeoff, not have it worked around silently.

## Phase 1 — resolve channel IDs

Atom feeds need `channel_id` (`UC...`). The EDN has three URL forms:
`@handle`, `/c/name`, `/user/name`. Only `/user/` works with the legacy
`?user=` feed param.

This is a **one-shot, run-locally step whose output gets committed to the
repo** — it must not run in the hourly container. Write `channels.edn` and
treat it as a checked-in artifact.

- Fetch each channel page, extract `"channelId":"UC[A-Za-z0-9_-]{22}"`.
- Three are already known — don't refetch:
  - Capcom Fighters: `UCPGuorlvarThSlwJpyTHOmQ`
  - Capcom Fighters JP: `UCu2Fxqf37DAZ0ZhIsd1FZwA`
  - Saltmine League: `UCgRCRmXJuLKZCFXBK6qd-Jg`
- If HTML scraping is blocked or brittle, fall back to browser control, or
  just have me paste them in. This is a one-time cost for 14 channels —
  do not build infrastructure around it.

Two regions have no YouTube channel and should be modelled as such rather than
dropped:
- `:chile` — Twitch only (`twitch.tv/UAFG`, shared with South America East)
- `:middle-east` — no organizer or channel listed by Capcom at all; entry
  links point at Capcom-run start.gg pages. Probably @CapcomFighters but
  unconfirmed. Render it with a "channel unknown" note.

## Phase 2 — state

The Atom feed window is ~15 items and these channels post non-CPT content
between events, so **older VODs roll off the feed**. Without persistence the
site silently loses history. State is required.

But state in an ephemeral container is the awkward part, so keep it dumb:

- **A single append-mostly file, not SQLite.** JSONL or EDN, keyed on
  `video_id`, one record per line: `{:video-id :channel-id :title :published
  :updated :url :first-seen :last-seen :class :region}`. A few hundred rows
  total. SQLite buys queries you don't need and turns your state into an
  opaque binary you can't diff or hand-edit.
- Path comes from `STATE_PATH` (default `./state/videos.jsonl`). The
  container reads it at start and writes it at end. How it gets in and out —
  mounted volume, `gsutil cp` either side of the run, or committed back to
  git — is my problem, not yours. Just don't assume it exists on first run:
  absent file means empty state, not a crash.
- Writes must be atomic: write to a temp file, then rename. A container killed
  mid-write must not corrupt the store.
- Rows are never deleted. Add a `--prune-before <date>` flag I can run
  manually if it ever matters.

## Phase 3 — poller

- Fetch all 14 Atom feeds. Parse with `clojure.data.xml`.
  Extract: `yt:videoId`, `yt:channelId`, `title`, `published`, `updated`,
  `link`.
- Sequential, with a small delay between requests. Set a descriptive
  `User-Agent`. Send `If-Modified-Since` and handle 304.
- **Expect to be rate-limited more aggressively than from a home IP** — this
  runs from a datacenter. Handle 429 and 5xx with bounded exponential backoff
  and then give up on that feed for this run. Never hammer.
- Upsert into state on `video_id`, refreshing `last-seen` and `title`
  (titles get edited, especially livestream titles after the fact).

## Phase 4 — classification

Feeds are mixed-content. Saltmine also runs 2XKO events; Capcom Fighters posts
trailers, character reveals, and general FGC content. Filtering matters more
than it looks.

Classify each video into `:world-warrior`, `:sfl`, or `:other` by title regex.
Starting heuristics — **put the patterns in a config map or an EDN file, not
inlined in code**, because I will be tuning these against real data:

- WW: `(?i)world.?warrior|\bWW\d{2,4}\b|CPT.?WW`
- SFL: `(?i)street fighter league|\bSFL\b|Pro-JP|Pro-US|Pro-EU`
- Region tagging: match channel_id back to `:region` in the EDN. One channel
  can map to multiple regions (Saltmine → 4), so also try to pull the region
  out of the title (`"Europe West"`, `"Germany"`, `"UK & Ireland"`).

Keep `:other` rows in state but exclude them from the public page. Emit a
**separate** `public/_debug.html` listing everything the filter rejected, so I
can tune the regexes — do not put a `?all=1` toggle on the public page.
Reclassification must be a pure function over stored state, re-runnable
without refetching, so improved regexes fix history retroactively.

## Phase 5 — static site

Generate to `OUTPUT_DIR` (default `./public`). Hiccup → string, inline CSS,
zero JS if possible. This is public, so:

- Responsive, works on a phone, dark mode via `prefers-color-scheme`.
- No hotlinking Capcom's images or logos — text and your own CSS only.
- Footer: unofficial fan project, not affiliated with or endorsed by Capcom.
  Link the official schedule at `https://sf.esports.capcom.com/cpt/schedule/`
  as the source of the underlying data.
- Emit `<meta>` description, OpenGraph tags, and a `robots.txt`. Include a
  visible "last updated" UTC timestamp — a stale site with no timestamp is
  worse than no site.
- Also emit `public/feed.xml` (Atom) of newly-seen broadcasts. Cheap, and it
  means the site is useful to people who won't visit it twice.

Sections, in this order:
1. **Live now** — if detectable from the feed.
2. **Upcoming** — pending the Phase 0 finding.
3. **Recent VODs** — grouped by region, newest first.
4. **Schedule** — the `:events` data from the EDN, so the calendar is visible
   even where no video exists yet. Highlight regional finals; most land
   Oct 17 – Nov 15, with US East trailing to Dec 13.

Notes:
- Dates in the EDN are bare `M/D`, **no year and no timezone**. Do not
  guess-convert them. Render as-is next to the region name, or normalize to
  2026 in the organizer's local zone and clearly label it approximate.
  Since this is public and the audience is global, render absolute UTC rather
  than my local time. Inventing precision is worse than showing `5/16`.
- Show the organizer name and link the Twitch channel alongside YouTube, since
  several regions are Twitch-first with YouTube as a VOD dump.

## Constraints

- Boring over clever. 14 feeds, a few hundred rows, one page.
- No YouTube Data API unless Phase 0 forces it — no keys, no quota, no secrets
  in the container.
- Only network egress is 14 GETs an hour. Keep it that way.
- Idiomatic Clojure, no unnecessary abstraction layers.

## Deliverables

- `resolve_channels.clj` — one-shot, local, writes committed `channels.edn`
- `poll.clj` — reads state, fetches feeds, writes state
- `render.clj` — reads state + `ww2026.edn`, writes `OUTPUT_DIR`
- `run.clj` (or shell entrypoint) — poll then render, the container's CMD
- `Dockerfile` — pinned bb version
- `patterns.edn` — classification regexes, hand-editable
- `README.md` — env vars, how to run each piece locally, what state needs
  persisting between runs

Build in phases. Stop after Phase 0 and report the finding.