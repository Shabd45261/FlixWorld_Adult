# Flix World — Adult (CloudStream extension)

One CloudStream extension with both adult sources from the Flix World app:

| Provider | Source | Ported from |
|---|---|---|
| **OK.XXX** | ok.xxx (KVS/Streamray, behind Cloudflare) | `scrapers.py` |
| **FikFap Shorts** | api.fikfap.com | `fikfap_shorts.py` |

- **OK.XXX Home** = Newest + Popular rows (as the app merges them).
- **FikFap Shorts** = the fikfap feed; ads/livestream promos filtered out.

## Install (users)

CloudStream → Settings → Extensions → Add repository, paste:

```
https://raw.githubusercontent.com/Shabd45261/FlixWorld_Adult/main/repo.json
```

Then enable **Show NSFW providers** in settings.

## First-time setup (owner)

The CI (`.github/workflows/build.yml`) builds the plugin and publishes the
compiled `.cs3` + `plugins.json` to a **`builds`** branch, which is what
`repo.json` points at.

1. Create an empty **`builds`** branch (the workflow checks it out):

   ```bash
   git checkout --orphan builds
   rm -rf ./*
   git commit --allow-empty -m "init builds"
   git push -u origin builds
   git checkout main
   ```

2. Repo → Settings → Actions → General → Workflow permissions → **Read and write**.
3. Push any source change (not a `.md`). CI builds and force-pushes `builds`.

## Local build

Needs Android SDK + JDK 17:

```bash
gradle make              # -> FlixWorldAdult/build/*.cs3
gradle makePluginsJson   # -> build/plugins.json
```

## Notes

- ok.xxx is behind Cloudflare → `CloudflareKiller`.
- `static.ok.xxx` 403s a foreign Referer → thumbnails carry a `Referer` header,
  and are upgraded 640x360 → 960x540.
- The `/get_file/` playback gate is resolved server-side; the signed HLS master
  is handed to the player.
- FikFap CDN tokens are India-locked (`token_countries=IN`) and expire.
