<img src="assets/icon.svg" width="64" height="64" alt="">

# Project Vela

An open Android TV shell with an agent that can operate apps, find and play media, control a home,
and compose its own home screen. Built for the remote and a paired phone.

The Android implementation includes configurable home rows, app and Jellyfin search, title/episode details,
a persistent watchlist, Home Assistant controls, task activity, voice/text input and phone setup.
The sideloaded prototype is also being integrated into a Shield Tube firmware image. Full image compilation,
native 4K output, real-model qualification and hardware acceptance remain open.

- [Build and run Android TV](android/README.md)
- [Build the Shield Tube firmware image](firmware/shield/README.md)
- [Design system, backend contracts and release inventory](docs/design/vela-tv-system.md)
- [Requested agent-team work plan](docs/design/vela-agent-team.md)
- [Android TV product direction](docs/superpowers/specs/2026-10-30-vela-agentic-tv-design.md)

The `apps/`, `packages/` and `os/` directories retain the earlier desktop/Linux proof of concept.
Run `pnpm install --frozen-lockfile`, `pnpm typecheck` and `pnpm test` to check that workspace.
