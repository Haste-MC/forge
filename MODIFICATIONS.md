# Modifications

This is a modified copy of [Forge](https://github.com/Card-Forge/forge), not the original.

- **Base:** tag `forge-2.0.15`
- **Branch carrying the changes:** `mtg-player-2.0.15`
- **Modified by:** Haste-MC, 2026
- **Upstream source (original, unmodified Forge):** https://github.com/Card-Forge/forge
- **Modified source (this fork, branch `mtg-player-2.0.15`):** https://github.com/Haste-MC/forge/tree/mtg-player-2.0.15

The changes are AI and rules-engine fixes made while running Forge as the engine behind a separate
program. Each one is a single commit on `mtg-player-2.0.15` with its reasoning in the commit message, so
`git log forge-2.0.15..mtg-player-2.0.15` is the authoritative list of what differs from upstream.

The earlier state of these changes, the one used for packages built on Forge 2.0.14, remains available
as the tag `mtg-player-2.0.14` (https://github.com/Haste-MC/forge/tree/mtg-player-2.0.14), so the source
reference given with those packages stays valid.

Forge is free software under the GNU General Public License v3; see `LICENSE`. This copy is
distributed under the same license, and this notice exists to satisfy its requirement that modified
versions carry prominent notice of the modification.
