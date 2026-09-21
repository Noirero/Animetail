# Animetail source adapters

This directory contains Aniyomi-compatible anime extension overlays used to test
sources for Animetail without vendoring the full upstream extension repository.

The working branch is intentionally neutral: `feat/source-adapter`.

## Targets

- `src/en/aniwatch` - anime catalog and player bridge using the site's current
  WordPress HiAnime REST endpoints.
- `src/id/nekopoi` - NSFW Indonesian source adapter. It remains isolated and
  declares `isNsfw = true`.

## Build

The workflow checks out `yuzono/anime-extensions`, copies these modules into
its `src` tree, and builds both APKs with the upstream Gradle toolchain.

For a local build:

```bash
git clone https://github.com/yuzono/anime-extensions.git
cp -R tools/source-adapters/src/en/aniwatch anime-extensions/src/en/
cp -R tools/source-adapters/src/id/nekopoi anime-extensions/src/id/
cd anime-extensions
./gradlew :src:en:aniwatch:assembleDebug :src:id:nekopoi:assembleDebug
```

The probe script checks AniWatch's live episode REST bridge and reports
Nekopoi reachability. It does not download media.
