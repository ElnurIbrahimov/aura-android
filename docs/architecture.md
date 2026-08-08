# Aura Android — Architecture

**Moved.** The architecture document lives at [`../architecture.md`](../architecture.md) in the
repository root.

There were two of these files. They overlapped, drifted apart, and disagreed — this one still
described the proactive layer as using a "calendar monitor", the permanent foreground service
that `CalendarCheckWorker` replaced, and carried a v0.58.0-era module summary against a v0.65.0
build. `scripts/check-version-docs.sh` only compares the version line, so nothing caught the
rest.

Rather than keep two documents in sync, the root file is now the only one, and the CI gate points
at it.
