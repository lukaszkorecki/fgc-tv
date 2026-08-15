# Pinned. Bump deliberately, not implicitly — an unpinned bb is the one thing
# that can silently change behaviour in an hourly job nobody is watching.
FROM babashka/babashka:1.12.218-alpine

# No `bb prepare` / dependency fetch step: bb.edn declares no :deps, so
# everything used here (http-client, data.xml, cheshire, fs) is already in the
# babashka binary. Build is hermetic and needs no network.

WORKDIR /app

COPY bb.edn ./
COPY src/ ./src/
COPY poll.clj render.clj run.clj ./
# Committed data artifacts. channels.edn is produced by resolve_channels.clj,
# which is a local one-shot and deliberately not part of this image.
COPY ww2026.edn channels.edn patterns.edn ./

ENV STATE_PATH=/state/videos.jsonl \
    OUTPUT_DIR=/public

# Both are expected to be mounted. /state must persist between runs or the site
# loses history as videos roll off the 15-item feed window; /public is where the
# generated site lands for whatever serves or uploads it.
RUN mkdir -p /state /public && \
    adduser -D -u 10001 fgc && \
    chown -R fgc /state /public /app
USER fgc

VOLUME ["/state", "/public"]

# Runs to completion and exits. No cron, no daemon, no loop — scheduling is
# external. Exits non-zero only on total failure.
CMD ["bb", "run.clj"]
