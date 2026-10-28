# Flipper-IRDB license note

Checked 2026-10-28 with `gh api repos/Lucaslhm/Flipper-IRDB/license`.

- **License:** Creative Commons Zero v1.0 Universal (CC0-1.0), a public-domain dedication.
- **Redistribution in an OS image:** allowed, including commercial use, with no attribution requirement.
- **Recommendation for Task 9:** bundle the database in the image by cloning it at a pinned commit into `/usr/share/vela/irdb`. No first-boot download is needed. Add a `CREDITS.md` next to it naming the source repository and commit, as a courtesy.
- The database is not vendored in this repository. The daemon reads it from `VELA_IRDB` (default `/usr/share/vela/irdb`).
