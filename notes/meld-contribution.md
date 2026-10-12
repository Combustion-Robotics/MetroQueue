# Idea: contribute our features to Meld

Status: parked (2026-10-12). We're continuing with our own fork (MetroQueue) for now.

## What Meld is

- Repo: https://github.com/FrancescoGrazioso/Meld (GPL-3.0)
- A Metrolist fork that uses Spotify for search, home and recommendations, plays audio through
  YouTube Music, and has optional lossless audio via Qobuz.
- Actively maintained, and it merges pull requests from outside contributors.
- Resynced with **Metrolist v13.7.0** on 2026-09-28 (PR #296). That's the same base as our fork,
  so our commits should port with few conflicts.

## Our features Meld doesn't have (checked 2026-10-12)

| Feature | Our commits |
|---|---|
| Clear queue button | `87a814d` |
| "Play added songs first" + "New radio after added songs" + queue headers | `123d21c` |
| Wi-Fi remote control web page (Ktor server, M3 page, artwork theme) | `c316c40`, `e6f46b7`, `7846f55` |

Meld has its own "smart queue generation" (Spotify-based radio queues). For a port, "new radio after
added songs" must treat those queues as radio too. Today it only checks `YouTubeQueue.isRadio`.

## How they take contributions

- `CONTRIBUTING.md`: branch prefixes `feat/`, `fix/`, …; commits use `type(scope): description`.
- Same rules as Metrolist: English strings only in `values/metrolist_strings.xml`, no version bumps.
- There's a `development` branch that gets merged into `main`, so PRs probably target `development`.
  Confirm before opening one.
- Their PR template still says "DO NOT PR NEW FEATURES, WE'RE ON A FEATURE FREEZE". It looks
  copied from Metrolist, because CONTRIBUTING lists `feat/` branches and a feature example.
  Ask first anyway.

## Plan if we pick this up

1. Open an issue on Meld describing the features (with screenshots) and ask whether they want them
   and which branch to target.
2. Fork Meld, attach the fork to a Claude Code session with push access.
3. Send three separate PRs, smallest first:
   1. Clear queue button
   2. Play added songs first + queue headers (adapt the radio check for Spotify queues)
   3. Remote control (biggest, adds `ktor-server-cio`, most likely to need discussion)
4. Use their PR template (Problem / Cause / Solution / Testing / Related Issues).
