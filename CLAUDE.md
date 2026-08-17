# Working on Life List

Orientation for a Claude session picking this repo up cold.

## What this is

Offline-first Android app that identifies organisms from photos and sound, and answers **at the
deepest rank it can defend** rather than always at species. Denmark-scoped, on-device inference.

The taxonomic rollup is the product. Arter and Seek both return species-level binomials at 80%
confidence with no honest fallback; nothing else here is unavailable elsewhere. When trading off,
trade in favour of the rollup.

## Read in this order

1. `BUILD.md` — the plan. Supersedes the original brief. Departures marked `[changed]`,
   `[added]`, `[decided]`, `[open]`.
2. `VERIFICATION.md` — claims that were checked, and the ones that were wrong.
3. `shared/taxonomy-spec.md` — the algorithm, in enough detail to reimplement.

## State

| | |
|---|---|
| contracts + spec | done |
| `training/` — rollup, fusion, audio, splits, names, GBIF, iNat, stages 1–2 | done, 182 tests |
| `core/` — Kotlin rollup + golden parity test | **compiles; 5 parity tests pass** (17 Aug 2026) |
| Gradle wrapper | added 17 Aug 2026 — it had never been committed, so CI had never run |
| stages 3–6 (embed, train head, eval, export) | not written — need GPU/torch/ONNX |
| `app/` — Android | not started |

## Rules that have already earned their place

- **The spec is the contract.** Python (`training/`) and Kotlin (`core/`) must agree exactly.
  `shared/golden/golden_rollup.json` is the check; `training/tools/gen_golden.py --check` runs in
  CI so Python cannot drift out from under Kotlin unnoticed. Regenerating the fixture is a
  deliberate act that shows up in a diff.
- **Never guess a taxon.** Refusing to resolve an ambiguous name is correct; picking one silently
  is how a beetle gets filed as a plant. Homonyms and unmatched BirdNET classes get reported.
- **Priors are never masks.** The audio geo prior reweights inside a confusion set built from raw
  scores. Applying it earlier would erase a vagrant, which is the record most worth having.
- **Split by observation, never by photo.** Photos of one individual straddling train and
  validation inflates accuracy invisibly. `assert_no_observation_leakage` runs in the pipeline,
  not only in tests.
- **Count observations, not photos.** Ten photos of one beetle is one piece of evidence.
- **Assert external schemas, loudly.** The iNaturalist columns are undocumented. A wrong guess
  filters to zero rows and reads as a fact about Denmark rather than a bug in us.
- **Don't write code you cannot test.** Stages 3–6 are unwritten on purpose — untested download
  and retry logic in the stage that costs hours to re-run is the worst place to be wrong.

## Environment constraints that shaped the above

A cloud sandbox wrote most of this and could not reach Maven Central, Google's Maven, the Gradle
distribution server, GBIF, iNaturalist, or S3. Hence: `core/` is pure Kotlin/JVM so CI can test
the rollup without the Android SDK; network access is confined to thin clients; all logic is pure
functions over already-fetched records. Keep that shape — it is why any of this is testable.

**[changed 17 Aug 2026]** A Cowork cloud session *can* reach all of those. Maven Central resolved,
`:core:test` ran, GBIF answered, and the iNaturalist bucket served 12.7 GB at ~65 MB/s. The
constraint that shaped this repo no longer binds. Keep the shape anyway — the reason it is worth
having was never the sandbox, it is that pure functions over fetched records are the only part of
a pipeline you can test in a second rather than an hour. Nothing here should be rewritten to
assume the network.

## Commands

```bash
cd training && pip install -e '.[dev]'
pytest tests -q
ruff check src tests tools
python tools/gen_golden.py --check

./gradlew :core:test
```

## Where each stage runs

Only stage 3 needs Colab (GPU + S3 bandwidth). Stages 1, 2, 4, 5, 6 run on the laptop — stage 1
better there, since free Colab disconnects near 90 minutes and it takes 30–90.

## Decided, don't relitigate

- Backbone: BioCLIP v1 ViT-B/16 default, **benchmark ViT-L/14 on the Pixel 9a** before committing.
  Report mean returned rank alongside top-1 — depth is what a better backbone buys.
- Audio: **BirdNET V3.0, pinned**. ONNX (one runtime), CC BY-SA, covers insects and amphibians —
  the vision model's blind spot.
- Inference: **CPU execution provider**, tuned threads. NNAPI is deprecated as of Android 15.
- UI: **English only**. Danish vernaculars stored but not surfaced.
- Target: Pixel 9a (Tensor G4), minSdk 29, targetSdk 35.

## Next action

Run `lifelist-taxa` then `lifelist-images`. The second prints the taxon-count table and **stops**
— that number sets the model's output dimension and every accuracy figure downstream. It wants a
human decision, not a default.
