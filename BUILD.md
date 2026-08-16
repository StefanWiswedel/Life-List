# Life List — build plan

This supersedes `lifelistbuildprompt.md`. That document was written from memory and is treated
here as a design brief, not a specification. Where it was wrong I have said so and changed it;
where it was silent on something that turned out to matter I have decided and flagged the
decision. Every change is marked **[changed]**, **[added]** or **[open]** so you can overrule me
quickly rather than reading the whole thing.

Verification evidence for the factual corrections is in `VERIFICATION.md`.

---

## 0. What this is, and why it should exist

**Life List** is an offline-first Android app (sideloaded, no Play Store) that identifies
organisms from photographs *and from sound*, resolves them as far down the taxonomic tree as
confidence honestly allows, and records them in a personal life list. Scope is Denmark. All
inference runs on-device.

### 0.1 The one-sentence justification **[changed]**

The original brief framed the differentiator as multi-image identification. Your Arter
screenshots kill that framing: Arter's Billedgenkendelse already accepts up to five photos with
the same "more photos, better result" copy. Multi-image is table stakes.

What Arter does with those photos is the opening. For a moth it returned **Gåsefod-bladmåler /
*Pelurga comitata* — "Vi er 80% sikre på denne artsbestemmelse."** A species-level binomial at
80% confidence, with no fallback to genus, tribe or subfamily. Seek behaves the same way and
wraps it in achievement badges.

> **Life List's reason to exist is that it stops at the rank it can defend.** Reporting
> *Carabus* honestly is correct behaviour. Guessing *Carabus granulatus* is a bug — not a
> cosmetic one, a category error about what the model knows.

Everything else in this app has an equivalent in software you already have installed. The
taxonomic rollup does not. That reorders the evaluation: **rollup accuracy and calibration are
the headline metrics**; leaf top-1 is a diagnostic.

### 0.2 The second thing Arter got wrong **[added]**

You like Arter but it is Danish-only, and you want English. So: **the UI is English throughout.**
Scientific names are always present and never translated; English vernaculars lead.

One engineering note rather than a product one. GBIF supplies Danish vernaculars for free
alongside the English ones, and the app is Denmark-scoped, so the `vernacular_da` column stays in
the schema as a nullable field and the pipeline keeps populating it. It costs nothing now, it
makes the data searchable against Danish sources and Arter records, and it means adding a
language toggle later is a UI change rather than a migration plus a retrain. **No bilingual UI
gets built** — this is just declining to throw away data we are handed.

### 0.3 Scope: two modalities **[added]**

The original brief was vision-only. You want BirdNET for audio. Having researched it, audio is
not a bolt-on for later — it changes the architecture enough that it must be designed in now,
and it turns out to fix the vision model's worst weakness. See §3.

---

## 1. Constraints

1. **Everything runs on the phone.** No inference server, no network required for ID.
2. **Multiple images per identification** (up to 5). Adding images must measurably improve
   confidence — measured across the validation set, not asserted per-observation.
3. **Answer at the deepest defensible rank**, never deeper.
4. **Fresh codebase.** No code imported or adapted from another repository.
5. **Light theme, herbarium aesthetic** (§7). Must not resemble Seek or Arter.
6. Target device: **Pixel 9a (Tensor G4)**. minSdk 29, targetSdk 35. **[changed]**
7. **English UI.** Scientific names always shown; Danish vernaculars stored but not surfaced.
   **[changed]**
8. **Two modalities — image and audio — sharing one taxonomy, one life list, and one rollup.**
   **[added]**

---

## 2. Vision model

### 2.1 Backbone — confirmed

BioCLIP v1 image tower, ViT-B/16, `hf-hub:imageomics/bioclip`, frozen. Verified against the
model's `open_clip_config.json` rather than assumed: 512-d embedding, 224×224 input, patch size
16, and preprocessing constants `mean = 0.48145466, 0.4578275, 0.40821073`,
`std = 0.26862954, 0.26130258, 0.27577711`. These coincide with the OpenAI CLIP values because
BioCLIP v1 was fine-tuned from that checkpoint — verified, not inherited.

BioCLIP 2 (ViT-L/14) and 2.5 (ViT-H/14) remain out of scope on size grounds. Revisit if
distillation or better mobile hardware changes the arithmetic.

**[open]** There is also `imageomics/bioclip-vit-b-16-inat-only` — same architecture, trained on
the iNat subset alone. Since our corpus *is* iNaturalist Denmark, this may fit better. One Colab
session embeds a sample through both and compares linear-probe top-1. Cheap; possibly free
accuracy. Worth doing before committing.

### 2.2 Head

Frozen backbone, trained MLP head on cached embeddings — the only CPU-feasible shape given no
GPU:

```
embedding (512-d, L2-normalised)
  → Linear(512 → 1024) → GELU → Dropout(0.3)
  → Linear(1024 → N_taxa)
```

Plain linear probe `Linear(512 → N_taxa)` trained as a baseline. Ship the probe unless the MLP
beats it by a meaningful margin on held-out top-1 — smaller, and less prone to overfitting thin
classes.

Output space is a flat softmax over **leaf taxa only**. Higher ranks are always derived, never
predicted.

### 2.3 Fusion and calibration

Specified in `shared/taxonomy-spec.md` §3 and implemented in `training/src/lifelist_train/`.
Embedding-space fusion by default, probability-space geometric mean behind a flag, both measured,
the winner recorded in `model_meta.json` and read by the app rather than hardcoded.

Temperature scaling fitted on a held-out split. The displayed percentage must mean something.

### 2.4 Export **[changed]**

ViT-B/16 image tower to ONNX, INT8 dynamic quantisation. Head exported as a separate small ONNX
graph so it can be retrained and swapped without re-exporting the backbone.

Validate quantised top-1 within ~2 points of the PyTorch FP32 baseline; fall back to FP16 and
report the size cost if not.

---

## 3. Audio model **[added — entire section]**

### 3.1 Which BirdNET

Two live options, and the choice is not obvious:

| | **V2.4** (stable, Jun 2023) | **V3.0** (developer preview 3.1, Jun 2026) |
|---|---|---|
| classes | 6,522 (birds) | ~11,000, **incl. non-birds** |
| format | TFLite only | **PyTorch + ONNX FP16** |
| sample rate | 48 kHz | 32 kHz |
| segment | 3 s fixed | variable, 3 s default |
| licence | CC **BY-NC-SA** 4.0 | CC **BY-SA** 4.0 |
| stability | stable | *"models, labels and code will change before final release"* |

**Recommendation: V3.0, pinned to a specific release.** Three reasons, in order of weight:

1. **One runtime.** V3.0 ships ONNX. V2.4 is TFLite-only, which would put both ONNX Runtime and
   TFLite in the same APK — two inference stacks, two preprocessing paths, two sets of native
   libraries, for one app. V3.0 lets ONNX Runtime serve both modalities.
2. **Non-bird coverage.** See §3.2 — this is the interesting part.
3. **Licence.** V2.4 is NonCommercial; V3.0 is CC BY-SA, no commercial restriction. Irrelevant
   for a sideloaded personal build today, but it removes a decision you would otherwise have to
   revisit if this ever became something you shared. Both require attribution — "Powered by
   BirdNET" goes in an in-app credits screen and the README. V3.0 additionally forbids poaching
   and military use, neither of which is a live risk here.

The cost is that a developer preview is a moving target. Mitigation: pin the exact release,
record it in `model_meta.json`, and treat a BirdNET upgrade as a deliberate retrain-and-revalidate
step rather than something that drifts in.

**[open]** Confirm this. If you would rather have the stable model, V2.4 works — it costs a
TFLite dependency and the non-bird coverage.

### 3.2 Why audio is not just a bird feature

The original brief's §5 concedes that evaluation will look good for plants and birds and bad for
invertebrates. That is honest and correct — it is also the app's biggest real weakness, because
inverts are where a naturalist most needs help.

BirdNET V3.0 covers insects and amphibians alongside birds. **Orthoptera and Anura are exactly
the groups the vision model will be worst at and where sound is diagnostic** — a bush-cricket is
far more reliably identified from stridulation than from a photograph of a brown insect in
grass. So the two modalities are complementary rather than parallel, and audio covers the vision
model's blind spot rather than duplicating its strength.

This is the strongest argument for doing audio now rather than in v2.

### 3.3 The output-semantics problem — and the fix

**This is the one genuinely hard design question in adding audio, and it needs stating clearly.**

The vision head emits a softmax: a single distribution over leaves, summing to 1, expressing
"this one organism is one of these". The rollup in `shared/taxonomy-spec.md` §4 depends on that
— node probability is the *sum* of descendant leaves, which is only meaningful for a normalised
distribution over mutually exclusive outcomes.

BirdNET emits **independent per-class sigmoid confidences** over 3-second windows. They do not
sum to 1, and they should not: multiple species can sing simultaneously, and that is a feature,
not noise. Feeding sigmoid scores into a mass-summing rollup would produce meaningless numbers
that look authoritative — the exact failure mode this app exists to avoid.

**Resolution: separate detection from identification.**

1. **Detection.** Over a window, a class is *detected* if its sigmoid confidence exceeds a
   detection threshold. This stays multi-label — three species singing gives three detections.
   It is BirdNET's native semantics and we do not distort them.
2. **Identification.** Each detection is then resolved separately. Take that detection's
   confusion set — the detected class plus taxonomically-related classes scoring within a margin
   of it — restrict to the Danish species list, and renormalise across that set. This yields a
   proper conditional distribution: *given that this sound is one of these, which is it?*
3. **Rollup.** Run the identical rollup from §4 of the spec over that conditional distribution.

The payoff is that one rollup module serves both modalities, and audio gets the same honesty
property: two chiffchaff-vs-willow-warbler candidates at 0.45 and 0.40 resolve to
*Phylloscopus*, not to a coin-flip binomial. Given how often those two are genuinely
indistinguishable in a poor recording, this is not a contrived example.

It also means the app never claims a species-level bird ID it cannot defend — which, since
BirdNET's raw confidences are widely over-trusted in the field, is a real contribution rather
than a technicality.

**[open]** The confusion-set margin is a parameter I have not chosen. It needs empirical fitting
against Danish recordings, and I would rather fit it than invent it. Flagging rather than
guessing, per §9 of your brief.

### 3.4 Range filtering

BirdNET ships a geo/meta model taking latitude, longitude and week-of-year, returning per-species
range likelihoods from eBird data. This is the audio-side equivalent of filtering GBIF to
`country=DK`, but better — it is seasonal.

Use it as a **prior, not a mask.** Multiply, renormalise, and surface it in the UI when it
materially changed the answer. A hard filter would make a genuine vagrant — precisely the record
a naturalist most wants — impossible to log, which would be an unforgivable behaviour in a tool
like this. A visible prior is honest; a silent mask is not.

**Store the raw unfiltered scores** alongside the filtered ones, so a suppressed rarity remains
recoverable after the fact.

### 3.5 Taxonomy bridging

BirdNET labels are `Scientific_name_Common name` strings against its own taxonomy. GBIF taxon
keys are the app's spine. A pipeline stage must map BirdNET classes → GBIF accepted taxon keys,
via the synonym table, reporting unmatched classes rather than dropping them silently. Expect
genuine mismatches around recent splits; those need a human decision, and they get a report, not
a guess.

### 3.6 Audio capture

CameraX has an audio analogue in `AudioRecord`: 32 kHz mono PCM, 3-second windows with
configurable overlap. Live "listening" session with a running list of detections, in the shape of
the acoustic session view in your third screenshot — which, notably, has a warm cream and amber
palette much closer to the herbarium direction than either green app, and gets tabular
confidence figures right. Worth stealing the layout logic from; not the colours.

Each detection is savable to the life list independently. A session is an observation container,
not a single observation.

---

## 4. Where the original brief was wrong

Summarised here; evidence in `VERIFICATION.md`.

### 4.1 NNAPI **[changed — material]**

The brief specifies "ONNX Runtime Mobile (NNAPI execution provider, CPU fallback)". **NNAPI was
deprecated in Android 15 — API 35, exactly the `targetSdk` the brief sets.**

The brief's own hedge ("if NNAPI is slower than XNNPACK CPU, measure") was directionally right
and understated. Worse, XNNPACK is not the clean alternative it assumes: ONNX Runtime's XNNPACK
EP supports **2-D MatMul and Gemm only**, while a ViT's attention is batched 3-D/4-D. XNNPACK
would claim the patch-embedding Conv and hand the transformer body back to the CPU EP anyway.
The QNN EP is Qualcomm-only, so Tensor G1 rules it out.

**Decision: default to the CPU EP with a tuned intra-op thread count.** Build the ML layer
EP-agnostic behind an interface, ship a benchmark harness that measures CPU / XNNPACK / NNAPI on
the actual device, and let data pick. NNAPI is at most an opt-in experiment. This keeps the
decision cheap and late rather than baked into a deprecated API.

### 4.1a The Pixel 9a changes the budget — and reopens a closed decision **[changed]**

The brief targeted a Pixel 6a (Tensor G1, 2021). You have a **Pixel 9a — Tensor G4**, three
generations on, with a substantially stronger CPU cluster. Two consequences:

**The 1.5 s budget stops being the binding constraint.** An INT8 ViT-B/16 on a G4's CPU EP should
clear it with room to spare. The worry in §4.1 was a G1 concern; it largely evaporates. Good
news, and it means the CPU-EP-first decision is now comfortable rather than a compromise.

**It reopens the backbone choice, which the brief closed.** §2.2 of the original ruled out
BioCLIP 2 (ViT-L/14) as "too heavy for on-device inference" — a judgement made against a 2021
midrange chip. ViT-L/14 is roughly 3–4× the compute of ViT-B/16, so on a G4 it moves from
implausible to *arguably feasible*, especially quantised and with the 1.5 s budget relaxed.

BioCLIP 2 is materially more accurate, and accuracy is what buys deeper honest ranks — a better
backbone doesn't just improve top-1, it lets the rollup stop *lower down* more often, which is
the actual product. That makes this worth measuring rather than assuming.

**[open]** Worth benchmarking ViT-L/14 on the 9a before committing to ViT-B/16? It costs one
export and one on-device timing run, and the downside of not checking is shipping a needlessly
shallow model on hardware that could carry a better one. My recommendation is yes — but ViT-B/16
stays the default until numbers say otherwise, since a bigger APK and slower cold start are real
costs too.

**[open]** If a backbone does miss the budget, what gives: the budget, the input resolution, or
the backbone? My instinct is the budget — 1.5 s is an assertion rather than a measured
requirement, and a naturalist waiting two seconds for an honest answer is not obviously worse
off. But it is your app.

### 4.2 iNaturalist open data **[changed]**

The brief describes six separate compressed TSVs. The bucket actually publishes one archive,
`inaturalist-open-data-latest.tar.gz` (plus dated monthly snapshots), containing six tab-separated
files: `observations`, `observers`, `photos`, `taxa`, `observations_projects`, `projects`. Plan
that Colab stage around one large download and extract.

Photo paths confirmed: `s3://inaturalist-open-data/photos/[photo_id]/[size].[ext]`. **Use
`medium` (500 px)** — we resize to 224 regardless, and it cuts download volume by roughly an
order of magnitude versus `original`.

**Unverified, deliberately:** how `quality_grade` and the licence column are represented. The
public README does not document columns. This gets confirmed against actual file headers during
stage 2 — a five-minute check that would silently corrupt the filter if guessed.

### 4.3 Multi-image confidence **[changed]**

The brief says adding images "must measurably improve confidence". As literally stated that is
false and would fail as a test: a single unlucky second photo can lower confidence, and a test
forbidding that tests noise. The claim is about the mean across the validation set, and the spec
and tests now say so.

---

## 5. Repo layout **[changed]**

```
life-list/
├── training/
│   ├── notebooks/           # Colab-ready, one per stage
│   ├── src/lifelist_train/
│   │   ├── taxonomy.py      # tree, invariants, lineage
│   │   ├── rollup.py        # THE algorithm — shared by both modalities
│   │   ├── fusion.py        # multi-image fusion, temperature, ECE
│   │   ├── audio/           # BirdNET bridging, confusion sets, geo prior
│   │   └── cli/
│   └── tests/
├── app/
│   └── src/main/java/dk/lifelist/
│       ├── ml/
│       │   ├── vision/      # ONNX session, preprocessing, fusion
│       │   ├── audio/       # AudioRecord, windowing, BirdNET session
│       │   └── rollup/      # Kotlin port — parity-tested against Python
│       ├── data/
│       ├── ui/
│       └── di/
├── shared/
│   ├── taxonomy-spec.md     # the contract
│   └── golden/              # cross-language parity fixtures
├── VERIFICATION.md
└── BUILD.md                 # this file
```

`ml/rollup/` is deliberately its own package, not nested under `vision/`. It serves both
modalities and it is the thing the app is for.

---

## 6. Build order

1. `shared/taxonomy-spec.md` + Room schema — the contracts. **Done.**
2. Python rollup, fusion, calibration, with tests. **Done — 37 passing.**
3. CI: signed debug APK on every push to `main`, downloadable as a workflow artifact.
4. Training stages 1–2 with the taxon-count-versus-threshold report. **Stop here and show it** —
   the taxon count determines everything downstream and deserves a human decision.
5. Stages 3–6 to exported artefacts.
6. Android ML layer against those artefacts, with the golden-file parity test.
7. Data layer and UI.
8. Audio: BirdNET integration, taxonomy bridging, confusion sets, session UI.
9. iNat export, settings, polish.

Audio sits at 8 rather than earlier because it depends on the taxonomy spine from 4–5. But its
*interfaces* are fixed in step 1, so nothing about the earlier steps has to be redone.

---

## 7. Visual design

Unchanged from the original brief, which was well-judged and is now better supported by evidence.
Both Arter and Seek are green-chromed; the avoidance is correct.

Palette: bone `#F7F4ED`, surface `#FDFCF9`, ink `#22201C` / `#5F5B54`, rust accent `#A85331`,
sage `#7C8471`, ochre `#B8892B`. Humanist serif for taxon names, correctly-cased italic
binomials, tabular figures for confidence. Specimen-label framing, confidence as a thin
horizontal bar, taxonomic tree drawn as a printed key. Restrained motion.

No gamification. Seek's hexagonal badges and full-screen "YOU OBSERVED A NEW SPECIES!"
celebration are the specific things not to build.

**[added]** Audio needs its own visual vocabulary consistent with this: a spectrogram rendered as
an ink-on-paper plot rather than a viridis heatmap, and detections listed as specimen labels down
the page.

---

## 8. Working notes

- Ask before inventing requirements. Where this document is silent on a real fork, raise it.
- Where a claim here is checkable, verify it against current documentation rather than trusting
  it. This document has already been through one such pass; that does not make it finished.
- Commit in coherent units with real messages.
- Test the rollup, the fusion maths, the synonym mapping, the BirdNET taxonomy bridge, and the
  preprocessing parity. UI tests are lower priority.
- Keep `RESULTS.md` honest. Weak groups are useful information.
- **[added]** Attribution obligations are real: BioCLIP, BirdNET ("Powered by BirdNET"), GBIF,
  iNaturalist and its photo licences. One in-app credits screen, maintained as dependencies are
  added, not archaeologically reconstructed at the end.
