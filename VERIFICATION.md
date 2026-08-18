# Verification of the build prompt

§9 of the build prompt asks for its own checkable claims to be verified against current
documentation rather than trusted. This is that pass. Findings are ordered by how much they
change the plan.

Checked 16 August 2026.

---

## 1. NNAPI is deprecated — §4.1 needs rewriting

**Claim:** "ONNX Runtime Mobile (NNAPI execution provider, CPU fallback)".

**Finding:** NNAPI was deprecated in **Android 15 (API 35)** — which is exactly the `targetSdk`
the prompt specifies in §1.6. Google's migration guide points to TFLite in Play Services or
AICore, neither of which is a route for an ONNX ViT.

The prompt already hedges — "If NNAPI is slower than XNNPACK CPU on this device — which happens
with quantised ViTs — measure and pick the winner." That instinct was right, and it is now
stronger than a performance question: building the primary inference path on a deprecated API
for a sideloaded app with a multi-year life is a bad trade even if it benchmarks well today.

**Complication.** XNNPACK is not the clean alternative the prompt assumes. ONNX Runtime's
XNNPACK EP supports **2-D MatMul and Gemm only**. A ViT's attention blocks are batched 3-D/4-D
MatMuls, so XNNPACK would claim the patch-embedding Conv and little else — the transformer body
falls back to the CPU EP anyway.

**Also ruled out:** the QNN EP is Qualcomm-only. The Pixel 6a is Tensor G1, so it is not
available regardless.

**Proposal:** default to the plain **CPU EP** with an explicitly tuned intra-op thread count,
treat XNNPACK as a measured variant rather than the target, and drop NNAPI to an
opt-in-behind-a-flag experiment at most. Benchmark all three on the actual device before
committing — but plan for CPU. This makes the 1.5 s budget in §4.1 the number to watch, and it
may be tight for an INT8 ViT-B/16 on a Tensor G1. If it misses, the honest levers are a smaller
input resolution or a distilled backbone, not a deprecated accelerator.

*Needs your call — see the open questions at the end.*

## 2. BioCLIP v1 — every claim checks out

Verified against `imageomics/bioclip`'s `open_clip_config.json` rather than assumed from CLIP
defaults, as §2.2 instructs:

| §2.2 claim | actual | verdict |
|---|---|---|
| ViT-B/16 | `patch_size: 16`, `width: 768`, `layers: 12` | correct |
| embedding 512-d | `embed_dim: 512` | correct |
| input resolution | `image_size: 224` | correct |
| normalisation | `0.48145466, 0.4578275, 0.40821073` / `0.26862954, 0.26130258, 0.27577711` | correct |

The constants *are* the OpenAI CLIP values, because BioCLIP v1 was fine-tuned from the OpenAI
ViT-B/16 checkpoint and kept its preprocessing. Verified rather than inherited — the distinction
matters because BioCLIP 2 does not necessarily follow.

Training data is TreeOfLife-10M, ~450K taxa with common names where available. OpenCLIP load
string: `hf-hub:imageomics/bioclip`.

These are now baked into `shared/taxonomy-spec.md` §5 and `model_meta.json`.

**One thing to consider:** there is also an `imageomics/bioclip-vit-b-16-inat-only` checkpoint —
same architecture, trained on the iNat subset alone. Since our downstream corpus is iNaturalist
Denmark, it is worth embedding a sample through both and comparing linear-probe accuracy before
committing to the general checkpoint. Cheap to test, potentially free accuracy.

## 3. iNaturalist open data — mostly right, one correction

**Correction:** §3 stage 2 describes "compressed TSVs" as separate files. The bucket actually
publishes a single archive, `inaturalist-open-data-latest.tar.gz` (plus dated monthly snapshots),
containing six tab-separated files: `observations`, `observers`, `photos`, `taxa`,
`observations_projects`, `projects`. Plan the Colab stage around one large download and extract,
not six.

Photo paths confirmed as `s3://inaturalist-open-data/photos/[photo_id]/[size].[ext]`, with sizes
original / large / medium / small / thumb / square. **Use `medium` (500 px)** — we resize to 224
anyway, and it cuts the download by roughly an order of magnitude versus `original`.

**Still unverified:** how `quality_grade` (research-grade) is represented in the observations
table, and the exact licence column values. The public README does not document the columns. I
will confirm these against the actual file headers during stage 2 rather than guess — it is a
five-minute check once the archive is open, and guessing would silently corrupt the filter.

## 4. Competitor read — from your screenshots

Sharper than "not like Seek and Arter":

**Arter already does multi-photo.** Its Billedgenkendelse screen says *"Tilføj gerne flere fotos
(maks 5). Jo flere fotos jo bedre resultat"* — the same cap as §1.2. So multi-image is not the
differentiator. What Arter does with it is: for a moth it returned **Gåsefod-bladmåler /
*Pelurga comitata* — "Vi er 80% sikre på denne artsbestemmelse"**. A species-level binomial at
80% confidence, with no honest fallback to genus or subfamily.

That is precisely the bug §1.3 defines. **The rollup is the product.** Everything else here has
an equivalent in an app your user already has installed; nothing else does this.

Worth stating in the README in those terms, because it also sets the evaluation priority: rollup
accuracy and calibration are the metrics that justify the app's existence, and leaf top-1 is a
diagnostic, not the headline.

**Seek** confirms the §4.2 grouping model (Mammals / Insects / Birds / Plants / Fungi …,
collapsible, per-group counts) — the structure is right and worth matching. Its hexagonal
achievement badges and full-screen "YOU OBSERVED A NEW SPECIES!" celebration are exactly the
gamification §7 rules out.

**Palette confirmation:** both Danish/Seek apps are green-chromed, so §7's avoidance is
well-founded. Notably the third app in your screenshots — the acoustic session view — uses a warm
cream and amber palette that sits much closer to the herbarium direction than either green app,
and gets tabular confidence figures right. Useful visual reference for §7 even though it is a
different modality.

## 5. Unchanged claims

GBIF backbone taxonomy and `country=DK` occurrence filtering (§3 stage 1) are as described.
Observation-level splitting (§3 stage 3), the rollup algorithm (§2.4), and the golden-file parity
requirement (§6) all stand — the spec now pins the parts the prompt left ambiguous (tie-breaking,
float accumulation order, threshold inclusivity).

---

# Second pass — 17 August 2026

Checked from a Cowork cloud session that, unlike the sandbox this repo was written in, could
reach Maven Central, GBIF and the iNaturalist bucket. So these are measurements, not readings.

## 6. CI had never run — the Gradle wrapper was never committed

`.github/workflows/core.yml` ends with `./gradlew :core:test --no-daemon`. `gradlew`,
`gradlew.bat` and `gradle/wrapper/` were not in the tree — only `gradle/libs.versions.toml`.
`git log --diff-filter=A -- gradlew` returns nothing, so they were never committed at all: the
sandbox could not reach the Gradle distribution server, and the gap went unnoticed because the
workflow's red X looked like the known network limitation.

So the "written, never compiled" row in `CLAUDE.md` was optimistic in one direction and
pessimistic in the other: nothing had ever compiled it, *and* nobody would have found out.

**Fixed.** Wrapper pinned at Gradle 8.10.2, committed with the exec bit set. `:core:test`
compiles and passes all five `RollupGoldenTest` cases, including `every golden case reproduces
exactly`. The Kotlin and Python rollups are now demonstrated to agree rather than asserted to.

**Note for a fresh laptop:** `core` pins `jvmToolchain(17)`, so a machine with only a newer JDK
fails with `Cannot find a Java installation ... languageVersion=17`. Either install a JDK 17 or
add the `foojay-resolver-convention` plugin to `settings.gradle.kts` and let Gradle fetch one.

## 7. The iNaturalist archive is 34 GB, not 4.5 GB — §3 and BUILD.md §8 are stale

`BUILD.md` line 387 carries the 2021 figures: 4.5 GB compressed, 11.6 GB uncompressed, 42M
observations. Measured today by `Content-Length`:

| file | size |
|---|---|
| `inaturalist-open-data-latest.tar.gz` | **34.2 GB** (Last-Modified 27 Jul 2026) |
| `observations.csv.gz` | 12.7 GB |
| `photos.csv.gz` | 19.6 GB |
| `taxa.csv.gz` | 39.5 MB |
| `observers.csv.gz` | 16.6 MB |

The "budget ~20–30 GB free" advice in BUILD.md §8 is therefore wrong by a factor of two or more.
Downloading the tarball alone needs 34 GB before a single row is read.

## 8. The bucket *does* publish the tables separately — correcting finding §3

Finding §3 above says the bucket "publishes a single archive … not six" and tells you to plan
around one large download. That is wrong. The per-table `.csv.gz` files listed in §7 exist at the
bucket root and are individually addressable.

This is not pedantry, it is what makes the stage runnable on a normal disk. `cli/images.py`
already reads observations to completion *before* it opens photos, and `open_table` already
accepts a directory of `.csv.gz` files. So the two tables never need to be resident together:
fetch observations (12.7 GB) → filter → cache the Danish subset → delete → fetch photos
(19.6 GB) → filter against the surviving uuids → delete. Peak disk is 20 GB, not 34 GB, and no
uncompressed copy is ever written.

That sequencing is what `tools/stage2_sequenced.py` does. It calls the same
`filter_observation_chunks` / `filter_photo_chunks` / `coverage` functions with the same
`DENMARK_BBOX`; only the I/O order differs.

## 9. Stage 1 was ~50 hours; it is now ~40 minutes

`GbifClient._get` did `session = self._session or requests` — and nothing ever passed a session,
so every call went through the module-level `requests.get`, which opens and TLS-handshakes a new
connection each time. Stage 1 makes three calls per taxon (`species`, `vernacularNames`,
`synonyms`) across a default 20,000 taxa: 60,000 handshakes.

Measured, 100 taxa, clean network:

| | per taxon | 20,000 taxa |
|---|---|---|
| original (new connection per call) | ~9 s* | ~50 h |
| pooled session, `--workers 1` | 0.81 s | ~4.5 h |
| pooled session, `--workers 8` | **0.13 s** | **~43 min** |

\* the 9 s figure was measured while a 12.7 GB download was saturating the link, so treat it as
indicative rather than exact. The clean serial-to-concurrent comparison is the 6.2× in the last
two rows; connection reuse accounts for the rest.

`GbifClient` already took a `session` argument — it was simply never used. The fix is a pooled
`requests.Session` created on first use, plus a `--workers` flag on `lifelist-taxa`.

**Determinism is preserved deliberately.** `ThreadPoolExecutor.map` yields in submission order,
so `taxa_raw.json` is byte-identical at 1 worker and 8 — verified live (100 taxa, both settings,
identical output) and in unit tests where the fake client sleeps longer on odd-numbered keys, so
a completion-ordered implementation would fail. This matters because leaf indices are assigned
from this ordering and a reshuffle silently invalidates every exported model.

## 10. GBIF's facet endpoint is not perfectly reproducible — stage 1's candidate list can drop a common species

One paired run came back different: the serial list contained *Columba palumbus* (wood pigeon,
704,651 Danish occurrences — top five nationally) and the concurrent list did not, with the
remaining 49 taxa in identical relative order.

Chased down, because an ordering bug here would be serious:

- three back-to-back direct calls to `/occurrence/search?facet=speciesKey` returned **identical**
  1,000-key pages, wood pigeon included;
- a clean re-run of the same serial/concurrent pair was **byte-identical**;
- the shared 49 taxa were in identical order in both, which is not what a race would produce.

So this is transient flakiness in GBIF's faceting, not in our fetch. But the consequence is real
and belongs on the record: **stage 1's candidate list is not guaranteed reproducible, and the
failure mode is a silently missing species rather than an error.** A wood pigeon dropping out of
a Danish organism identifier is not a subtle defect.

*Needs your call:* once you commit to a taxon set, the key list should be frozen as a checked-in
fixture and re-fetches diffed against it, rather than re-derived from a live facet query each
time. Cheap, and it converts a silent omission into a visible diff.

## 11. Stage 1 ran: 19,998 taxa — with six duplicate keys

Full run, `--workers 8`, 39 minutes (14:48–15:27). 19,998 taxa, 229,993 synonyms, 2 keys skipped.

| | |
|---|---|
| ranks | species only (19,998) |
| status | ACCEPTED 19,803, DOUBTFUL 195 |
| kingdoms | Animalia 9,869 · Fungi 4,769 · Plantae 3,833 · Bacteria 822 · Chromista 586 · Protozoa 102 · Viruses 16 |
| Danish vernaculars | 11,138 (56%) |
| English vernaculars | 10,528 (53%) |
| occurrences | 776,374 (*Anas platyrhynchos*) down to 14 |

**Defect found: six taxa appear twice.** `parse_backbone_record` keys on
`nubKey or key or usageKey`, and GBIF's facet can return two distinct `speciesKey`s that resolve
to the same nub key. The pairs:

| key | name | occurrences |
|---|---|---|
| 1536449 | *Episyrphus balteatus* | 4,716 + 4,718 |
| 2493551 | *Locustella luscinioides* | 4,710 + 4,718 |
| 3146791 | *Erigeron canadensis* | 4,695 + 4,701 |
| 2325755 | *Ampharete finmarchica* | 1,696 + 1,696 |
| 2529223 | *Cortinarius anserinus* | 847 + 847 |
| 7351210 | *Protoperidinium depressum* | 847 + 847 |

Not fatal — `build_taxonomy_nodes` already does `if taxon.key in nodes: continue`, so the
taxonomy tree gets one node. But the *occurrence count is split across both rows*, so a
hoverfly with ~9,400 Danish records is recorded twice as ~4,700. Anything that ranks or
thresholds on `occurrences` reads those six as half as common as they are.

Deliberately not fixed here: merging duplicates means choosing whether to sum the counts or take
the max, and that is a judgement about what a GBIF facet split means. Six rows in 19,998, so it
changes nothing structural — but it should be an explicit decision with a test, not a silent
`max()`.

## 12. Stage 2 ran: the number the build has been waiting for

Full run, 17 Aug 2026, against the 27 July 2026 snapshot.

| | |
|---|---|
| research-grade Danish observations | 1,019,878 |
| openly licensed photos of those | 1,531,899 |
| taxa with at least one usable photo | 12,483 |

| min observations | taxa | photos (capped 500/taxon) |
|---|---|---|
| 50 | 2,378 | 725,459 |
| 80 | 1,903 | 673,499 |
| 120 | 1,509 | 608,546 |
| 200 | 1,085 | 502,865 |

Breakdown by group, because "1,903 taxa" does not say whether they are worth having:

| group | 50 | 80 | 120 | 200 |
|---|---|---|---|---|
| Insects | 1,053 | 819 | 642 | 439 |
| Plants | 748 | 622 | 501 | 380 |
| Birds | 207 | 183 | 160 | 136 |
| Fungi | 131 | 95 | 66 | 40 |
| Arachnids | 72 | 51 | 41 | 26 |
| Molluscs | 54 | 42 | 34 | 22 |
| Mammals | 29 | 26 | 20 | 13 |
| Ray-finned fish | 18 | 12 | 4 | 1 |
| Amphibians | 11 | 9 | 9 | 7 |
| Reptiles | 6 | 5 | 5 | 5 |
| Other animals | 39 | 33 | 22 | 12 |

**The schema questions in §3 are closed.** `quality_grade == "research"` and the licence column
values are as coded — `assert_columns` passed and a million rows survived, which is not what a
wrong guess looks like.

**Two things worth noticing before picking a threshold.** Danish herpetofauna is a floor, not a
curve: 6 reptiles and 11 amphibians at 50, and 5 and 7 at 200. Tightening the threshold barely
costs you there, because that is close to the country's actual species count. Fish are the
opposite — 18 at 50 collapsing to 1 at 200, which is a statement about how people photograph
fish, not about Danish waters. Any threshold above ~120 effectively drops the class.

**Timings, for BUILD.md's estimates.** Observations: 3 min download, 13 min filter. Photos: 4 min
download, **2 h 0 min filter** (19.6 GB, checked against a 1.02M-uuid set). Budget three hours
for stage 2, not the "streaming CPU" the plan implies.

## 13. Stage 3 written and exercised — threshold 50, cap 150

**Decided:** `--min-observations 50`, `--max-photos-per-taxon 150`. 2,378 taxa, **333,947
photos**, 291,490 observations. Cap 150 rather than 500 halves the embedding job and costs
almost nothing: the cap only bites on species that are already well covered.

Stage 3 (`lifelist-embed`) is written, and unlike stages 4–6 it is written *because* there is
now somewhere to run it. The rule it respects is the same one: the parts that can silently
corrupt a training set are pure and tested, and the parts that cannot be tested — fetching
bytes, running the model — are injected, the same shape `GbifClient` has.

**Resumability, verified rather than asserted:**

| behaviour | check |
|---|---|
| completed shards are skipped | re-ran, went from 84 pending to 83 |
| a finished run is a no-op | re-ran, "3 done, 0 to go" |
| a hard kill mid-shard does not count as done | dropped a `.npz.tmp` in place, run correctly saw it as pending |
| a dead photo costs one photo, not the shard | unit test with a 404-ing fetcher |
| a shard where *everything* fails still completes | otherwise a permanently dead shard loops forever |

The atomic write matters more than it looks. `np.savez_compressed` appends `.npz` to any path
lacking it, which silently defeated the temp-then-rename on the first attempt and would have
left truncated shards looking finished — training data quietly missing rows, invisible until
accuracy was mysteriously bad. It is written through a file handle now, and there is a test.

**Ran for real against BioCLIP and the S3 photo bucket:** 600 photos, 263 taxa, 3 shards, zero
failures. open_clip reported the preprocessing config as `size (224, 224)`, mean
`0.48145466, 0.4578275, 0.40821073`, std `0.26862954, 0.26130258, 0.27577711` — matching §2
exactly, which is the first time those constants have been confirmed from a loaded model rather
than a config file.

Embeddings sanity-checked: 512-d, unit norm to 4 decimal places, no NaNs, and mean cosine
similarity **0.751 within a taxon against 0.314 across taxa**. The signal is real.

**Throughput.** 6.4 photos/s on this container's 2 Xeon cores, download included. A Ryzen 7 PRO
5850U has 8 Zen 3 cores, so 20–25 photos/s is the reasonable expectation: **4–6 hours** for all
333,947, not the 9–18 estimated before measuring. A T4 would be bound by S3 rather than the
model.

## 14. One photo, two species — found by running stage 3 over the real manifest

The photos table is not one row per photo. A `photo_id` appears under several
`observation_uuid`s, and in the Danish subset **565 photo_ids carry more than one taxon**.
1,367 rows were duplicated overall.

This surfaced as a crash, not as a suspicion: `write_shard` looked its rows up with
`.loc[list_of_ids]` against an index holding duplicates, which returns *every* match, so
`taxon_id` came out length 202 for 200 photos and the shard would not load again. Both are
fixed — the writer de-duplicates before the lookup, and there is a test.

The crash was the lucky part. The underlying data problem is silent and worse:

1. **Label noise.** The same JPEG becomes a training example for two species. The head cannot
   satisfy both, so it learns to be unsure about exactly the pairs a user is most likely to
   confuse.
2. **Leakage the existing assertion cannot see.** `assert_no_observation_leakage` splits on
   observations, which is right. But one photo sitting in two observations can land in train
   *and* validation with every observation still on one side. The split looks clean and the
   validation number is inflated — the precise failure the "split by observation" rule exists
   to prevent, arriving by a route it does not cover.

**Resolved by refusing, not guessing.** `join_photos_to_taxa` now drops any photo whose taxon is
ambiguous and collapses photos duplicated within a single taxon. Dropping 565 photos costs
nothing; assigning one of two labels silently is how a beetle gets filed as a plant.

Revised figures, replacing §12–13:

| | before | after |
|---|---|---|
| photos with a taxon | 1,531,899 | 1,530,595 |
| taxa at threshold 50 | 2,378 | **2,376** |
| manifest at cap 150 | 333,947 | **333,702** |

Two taxa fell below 50 observations once their ambiguous photos were removed, which is the
correct outcome: their coverage was partly other species.

Stage 3 was re-verified end to end after the fix — 700 photos, four shards, an extension from
400 to 700 embedding only the 300 new photos, zero duplicates, manifest fully covered.

## 15. The name index was refusing 17% of the training photos

Stage 4 needs the photos labelled with GBIF keys, because the taxonomy tree is GBIF. The
manifest labels them with **iNaturalist** taxon ids. Nothing in the repo bridges the two —
there is a BirdNET→GBIF bridge (§3.5) but no iNaturalist→GBIF one, so this had to be measured
before stage 4 could be written at all.

Bridging by name through `SynonymIndex` matched **83.0%** of the 2,376 manifest taxa. The 17%
that failed were not obscure: teal, bullfinch, common crossbill, common frog, brown rat,
chanterelle. That is not a plausible failure rate for a Danish species list, so it was a bug,
not a fact.

**Two defects, both in `names.py`, both the same mistake — precedence.**

*One.* `Anas crecca carolinensis` is accepted under a different key than `Anas crecca crecca`,
which correctly makes the **binomial unsafe to reach by fallback** from an unknown trinomial.
But the old index stored that in the same `_ambiguous` set the exact-name lookup consults, so
`resolve("Anas crecca")` refused — despite *Anas crecca* being an accepted name with exactly one
key. A fact about the fallback was poisoning the direct hit. Split into two sets; exact accepted
names now win. **83.0% → 93.1%.**

*Two.* GBIF publishes duplicate backbone entries. On this list 134 names carry more than one
accepted entry — but **133 of those pairs sit in the same family**, and 89 are an
ACCEPTED/DOUBTFUL pair where the doubtful entry has two orders of magnitude fewer occurrences
(*Rana dalmatina*: 6,210 against 54). Exactly **one** is a genuine cross-kingdom homonym.
Refusing all 134 was refusing GBIF's own answer.

Claims are now strong or weak. A DOUBTFUL entry and a synonym are weak; they lose a collision
rather than causing one, and only two claims of equal standing make a name ambiguous. That one
real homonym still refuses. **93.1% → 97.2%.**

This is not a softening of "never guess a taxon". Reading the status field GBIF publishes for
exactly this purpose is not guessing. Two ACCEPTED entries still refuse, and there is a test
that says so.

| | matched | photos dropped |
|---|---|---|
| as found | 83.0% | 56,974 (17.1%) |
| exact name beats contested fallback | 93.1% | 22,978 (6.9%) |
| ACCEPTED beats DOUBTFUL, synonyms weak | **97.2%** | **9,243 (2.8%)** |

The BirdNET bridge resolves through the same index, so its match rate improves too — unmeasured
until the labels file is in the repo.

**What still does not bridge, and why it is a decision rather than a bug.** 54 taxa remain
unmatched and 13 ambiguous.

| rank | count | |
|---|---|---|
| species | 32 | genuine misses, and 12 more with two equal ACCEPTED entries |
| genus | 14 | *Carabus*, *Cepaea*, *Syrphus* — observations identified only to genus |
| hybrid | 4 | correctly refused; §1.3 forbids resolving a hybrid to a parent |
| subgenus / complex | 4 | ranks the spec does not model |

The genus-rank rows are the interesting ones. iNaturalist observers frequently stop at genus,
and stage 1 fetched **species only** (all 19,998 are rank `species`), so a genus label has
nothing to match against. Whether the model should have genus-level leaves — so "a *Carabus*,
species indeterminate" is a trainable class rather than discarded data — is a real fork, and
BUILD.md §8 says to raise those rather than invent them.

*Needs your call — added to the open questions below.*

## 16. Genus is now a trainable leaf — decided 18 Aug 2026

**Decision:** a genus that carries genus-only observations becomes a class the head can predict,
rather than data thrown away. Danish naturalists stop at *Carabus* often enough that those
photographs are evidence, and an app whose whole argument is "answer at the deepest rank you can
defend" should be able to learn that answer directly rather than only reach it by rollup.

Invariant 4 forbids a genus being both an internal node and a leaf, and relaxing it would break
both implementations and the golden fixture. So the genus gets a synthetic child instead —
spec §1.1a:

| | |
|---|---|
| id | `-1036775` for *Carabus* `1036775` — negative, so it cannot collide with a GBIF key |
| name | `Carabus sp.`, the `sp.` roman per §1.2 |
| rank | one deeper than the parent |

A genus that has no species of its own is skipped: it is already a leaf, and a synthetic child
there would state the same thing twice.

**No refetch needed.** Every species record already carries `genusKey` and the genus name in its
lineage, so all 14 genus-rank manifest taxa resolve from data stage 1 already fetched. That
recovers **1,995 photos**, taking the bridge to 97.8%.

**Both implementations, because negative ids are exactly the kind of assumption that fails
quietly.** Nothing in the Kotlin side assumed `taxonId > 0`, but nothing proved it either, and
the failure mode is a silently dropped class rather than a crash. `IndeterminateLeafTest` now
covers loading, subtree membership, rollup in both directions, and — the one that matters for
honesty — that `Carabus sp.` never renders as a bare *Carabus*, which would claim a
determination the model does not have.

## 17. The app builds — an actual APK, verified here

`:app` existed only as a comment in `settings.gradle.kts` saying it would be added later. It is
added now, and the reason it could be added is that this container reaches Google's Maven and
the Android SDK repository, so the module was **built rather than merely written** — the rule
that kept stages 4–6 unwritten applies to Android code too.

| | |
|---|---|
| debug APK | 28.7 MB |
| release APK | 21 MB |
| package | `dk.lifelist.app`, minSdk 29, targetSdk 35 |
| permissions | `CAMERA` only |
| signing | Android debug key |
| version | injected from Gradle properties, so CI can stamp it from the tag |

What is in it: the result screen from the design mock, rendered by Compose over the **real**
`dk.lifelist.core.Rollup` and `Presentation`. The threshold slider re-runs the rollup on device.
Camera capture is wired — permission flow, lifecycle binding, preview surface — and the shutter
advances through four stand-in probability vectors, because stage 6 has exported no model.

**Two things that are not verified, and should not be presented as if they were.** There is no
camera and no display in this container, so every line of `CameraScreen.kt` is unproven until it
runs on the Pixel, and the layout has been reasoned about rather than seen. Compilation is not
correctness.

**One change with a consequence worth stating.** Adding `:app` means the root build configures an
Android module, which could have broken `core.yml` — that workflow has no Android SDK. Checked by
running `:core:test` with `ANDROID_HOME` unset *and* `local.properties` removed: it passes, because
AGP defers the SDK requirement to task execution. `core.yml` stays as it is.

Releases are cut by tag (`v*`), which builds `:app:assembleRelease` and attaches the APK to a
GitHub Release. The rollup parity test gates it: a build that disagrees with the spec must not
ship even if it compiles.

---

## Open questions

1. **Inference backend.** Accept the CPU-EP-first proposal in §1 above, or hold NNAPI as the
   default until benchmarked on your actual Pixel 6a?
2. **Backbone checkpoint.** Spend one Colab session comparing `bioclip` against
   `bioclip-vit-b-16-inat-only` before committing?
3. ~~**Genus-level leaves.**~~ **Decided 18 Aug 2026: yes.** See §16.
4. **The 1.5 s budget.** If an INT8 ViT-B/16 on CPU misses it on a Tensor G1, which gives —
   the budget, the resolution, or the backbone?
