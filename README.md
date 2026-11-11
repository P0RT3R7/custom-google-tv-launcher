# Custom Google TV launcher

A replacement home screen for Google TV / Android TV (built on a Hisense), with no ads or sponsored rows:

- Animated jellyfish background, clock, and local weather with a full forecast screen
- Continue Watching, Recently Watched, and suggestions based on what you watch (optional Trakt)
- Favorites dock, recently used apps, All Apps, search (with voice), Now Playing bar, HDMI inputs
- Screensavers: Night Sky (shows which constellations to look for and where), Topo Glow, Jellyfish
- Night mode: warm, dim filter from 8:30 PM to 5 AM

## Setup with Claude (easiest)
1. Unzip this folder on a PC on the same Wi-Fi as the TV.
2. Open the folder in **Claude Code** (desktop app: Code tab → pick this folder) and say:
   **"Set up this launcher on my TV."**
3. Claude follows `CLAUDE.md`. It'll ask you to turn on developer mode on the TV, approve the
   connection, and OK each step. It makes a backup first, so everything can be undone.

## Manual setup
See `CLAUDE.md` for the exact adb commands, and `launcher/README.md` to build from source.

## Undo
`bash tv-tools/restore.sh <TV_IP>` brings the stock Google TV home screen back.
