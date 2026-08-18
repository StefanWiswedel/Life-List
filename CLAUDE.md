# Working on Life List

Orientation for a Claude session picking this repo up cold.

## What this is

Offline-first Android app that identifies organisms from photos and sound, and answers **at the
deepest rank it can defend** rather than always at species. Denmark-scoped, on-device inference.

The taxonomic rollup is the product — but **[corrected 18 Aug 2026]** the wedge is narrower than
this file used to claim. Seek *does* fall back ("a member of the family Katydids"). What no
competitor does is tell you how sure it is about the rank it returned, show what it was choosing
between, or let you set how much certainty you want first. See VERIFICATION.md §18.

**[sharpened 18 Aug 2026]** Sharper still, and this is the one to hold on to: Seek's fallback is
presentational — it will tell you it is a katydid and then refuse to *save* the record, because
its model has a species field. Ours stores the returned node at whatever rank it was. That is a
data-model difference, not a screen difference, and it is why the genus-as-leaf work matters.

When trading off, trade in favour of a record being keepable at the rank the evidence supported.

## Read in this order

1. `BUILD.md` — the plan. Supersedes the original brief. Departures marked `[changed]`,
   `[added]`, `[decided]`, `[open]`.
2. `VERIFICATION.md` — claims that were checked, and the ones that were wrong.
3. `shared/taxonomy-spec.md` — the algorithm, in enough detail to reimplement.

## State

| | |
|---|---|
| contracts + spec | done |
| `training/` — rollup, fusion, audio, splits, names, GBIF, iNat, stages 1–2 | done, 239 tests |
| `core/` — Kotlin rollup + golden parity test | **compiles; 5 parity tests pass** (17 Aug 2026) |
| Gradle wrapper | added 17 Aug 2026 — it had never been committed, so CI had never run |
| stage 3 (`lifelist-embed`) | written, resumable, run against real BioCLIP + S3 |
| stage 4 (`head.py`) | written, trained, first numbers in RESULTS.md |
| stage 6 (`export.py`) | written; **one 350 MB fp32 file, pixels → logits**, §21–22 |
| stage 5 (eval) | partial — metrics live in `head.py`, no per-group table yet |
| `app/` — Android | identifies on device; **Material 3 shell**, life list, reference photos |
| common names | **fixed 18 Aug** — the taxonomy asset shipped with every vernacular null (§28) |
| multi-photo | fused on device via §3.2; §3.1 needs a re-export (§29) |
| release workflow | tag `v*` → GitHub Release with an APK attached |

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
- **Don't write code you cannot test.** Stages 4–6 are unwritten on purpose — untested logic in a
  stage that costs hours to re-run is the worst place to be wrong. Stage 3 is written because
  there is now somewhere to run it, and it follows the same rule: sharding, resume and failure
  handling are pure and tested; the fetcher and the encoder are injected.

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

./gradlew :core:test          # rollup parity, no Android SDK needed
./gradlew :app:assembleDebug  # needs the Android SDK; ANDROID_HOME or local.properties
git tag v0.1.0 && git push --tags   # builds an APK and attaches it to a GitHub Release
```

## Where each stage runs

Stage 3 runs anywhere with bandwidth: measured at 6.4 photos/s on 2 weak cloud cores, so 4–6 h
on an 8-core laptop and less on a T4, where S3 rather than the model is the limit. Stages 1, 2,
4, 5, 6 run on the laptop — stage 1
better there, since free Colab disconnects near 90 minutes and it takes 30–90.

## Decided, don't relitigate

- Backbone: BioCLIP v1 ViT-B/16 default, **benchmark ViT-L/14 on the Pixel 9a** before committing.
  Report mean returned rank alongside top-1 — depth is what a better backbone buys.
- Audio: **BirdNET V3.0, pinned**. ONNX (one runtime), CC BY-SA, covers insects and amphibians —
  the vision model's blind spot.
- Inference: **CPU execution provider**, tuned threads. NNAPI is deprecated as of Android 15.
- Preprocessing lives **inside the ONNX graph**, never reimplemented on the client: a bilinear
  resize instead of antialiased bicubic costs 8% of predictions (§22).
- Precision: **fp32, not int8** — measured at 196 ms/image on two weak cores, so the 1.5 s budget
  is not binding, and int8 changes ~6% of identifications (§21).
- UI: **English only**. Danish vernaculars stored but not surfaced.
- Target: Pixel 9a (Tensor G4), minSdk 29, targetSdk 35.
- **Genus is a trainable leaf** where genus-only observations support it, via a synthetic
  `Carabus sp.` child with a negative taxon id (spec §1.1a, VERIFICATION.md §16).

## Next action

The threshold is **decided: 50 observations, 150 photos per taxon** — 2,376 taxa, 333,702 photos
(VERIFICATION.md §12–14). `cache/photo_manifest.parquet` is built. Do not re-derive it; leaf
indices come from it.

Run stage 3, which is resumable — re-run the same command after any interruption:

```bash
cd training && pip install -e '.[torch]'
lifelist-embed --cache-dir cache --shard-size 4000 --download-workers 24 -v
```

4–6 hours on an 8-core laptop, measured. Then stages 4–6 are still to write.
