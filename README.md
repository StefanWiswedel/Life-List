# Life List

Offline-first Android app that identifies organisms from photographs and from sound, resolves
them **as far down the taxonomic tree as confidence honestly allows**, and records them in a
personal life list. Scope is Denmark. All inference runs on-device.

## Why it exists

Existing tools answer at species level whether or not they can defend it. Arter's image
recognition will return *Pelurga comitata* at "80% sure"; Seek does the same and adds achievement
badges. Neither will tell you it only really knows the genus.

Life List stops where the evidence stops. Reporting `Carabus` is a correct answer. Guessing
*Carabus granulatus* is a bug — not a cosmetic one, a category error about what the model knows.

That behaviour — the **taxonomic rollup** — is the product. Everything else here has an
equivalent in software you already have installed.

## Status

Early. The contract and its reference implementation exist; the app does not yet.

| | state |
|---|---|
| `shared/taxonomy-spec.md` — the contract | done |
| `training/` — rollup, fusion, calibration, audio, tests | done, 56 tests |
| `core/` — Kotlin rollup + golden parity test | done, unverified in CI |
| training pipeline stages 1–6 | not started |
| `app/` — Android | not started |

## Layout

```
life-list/
├── shared/taxonomy-spec.md   # the contract both halves implement
├── shared/golden/            # cross-language parity fixtures
├── training/                 # Python: pipeline + reference implementation
├── core/                     # Kotlin/JVM: rollup, no Android dependencies
├── app/                      # Android: Compose UI, ONNX sessions, Room
├── BUILD.md                  # the build plan
└── VERIFICATION.md           # what the original brief got wrong
```

`core/` is pure Kotlin/JVM on purpose. The rollup is what the app is for, so it is the cheapest
thing in the repo to test — no Android SDK, no emulator, seconds in CI.

## Development

```bash
# Python reference implementation
cd training && pip install -e '.[dev]'
pytest tests -q
ruff check src tests tools

# Regenerate the cross-language fixture (deliberately, and commit the diff)
python tools/gen_golden.py

# Kotlin parity
./gradlew :core:test
```

The golden fixture is the contract between the two implementations. `gen_golden.py --check` runs
in CI so the Python side cannot drift out from under the Kotlin test unnoticed.

## Models

| | source | licence |
|---|---|---|
| vision backbone | BioCLIP v1 ViT-B/16 (`imageomics/bioclip`) | MIT (model), see model card |
| audio | BirdNET V3.0 developer preview | CC BY-SA 4.0 |
| taxonomy | GBIF Backbone | CC BY 4.0 |
| training images | iNaturalist open data | per-image CC, logged |

BioCLIP 2 / 2.5 are not used — see `BUILD.md` §2.1 and §4.1a, where the Pixel 9a reopens that
decision.

Attribution obligations are real and tracked in an in-app credits screen: **Powered by BirdNET**,
BioCLIP, GBIF, iNaturalist. BirdNET V3.0 additionally forbids use for poaching or military
purposes.

## Reading order

1. `BUILD.md` — the plan, and where it departs from the original brief
2. `VERIFICATION.md` — the claims that were checked, and the two that were wrong
3. `shared/taxonomy-spec.md` — the algorithm, in enough detail to reimplement
