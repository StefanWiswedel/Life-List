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

## 18. Seek already does the fallback — §4 overstated the differentiator

§4 says Seek and Arter "both return species-level binomials at 80% confidence with no honest
fallback", and `CLAUDE.md` repeats it as *"nothing else here is unavailable elsewhere"*. New
screenshots, 18 Aug 2026, show that is wrong about Seek:

> **WE BELIEVE THIS IS A MEMBER OF THE FAMILY — Katydids.** But Seek couldn't identify the exact
> species. You can try a different angle, zoom in, or try to get a clearer shot of the organism.

That is a rollup, shipped, in the competitor the plan was written against. Arter's screenshot is
as described — species at 80% with no fallback — so the claim holds for Arter and fails for Seek.

**What is actually left**, having looked at all three side by side:

| | Seek | Arter | ObsIdentify |
|---|---|---|---|
| falls back above species | **yes**, to family | no | no |
| says how confident in what it returned | no | yes, one figure | yes, 97% ring |
| shows what it was choosing between | no | no | "show all results" |
| lets the user move the threshold | no | no | no |
| answers at *any* depth, not just species or family | no | no | no |

So the honest claim is narrower and more specific than "nobody else does this": *Seek stops at
family and will not tell you how sure it is; nothing here lets you decide how much certainty you
want before it commits.* That is still a real difference, and it is still the reason the rollup
and the calibration matter more than leaf top-1 — but it is a thinner wedge than §4 assumed, and
the README should not claim more than that.

**This changes priorities, not plans.** If a competitor already does coarse fallback, then
*calibration* — the returned node's probability actually meaning what it says — is what carries
the product, not the fallback itself. Temperature fitting in §2 moves from hygiene to headline,
and `RESULTS.md` should lead with calibration error rather than accuracy.

## 19. The real differentiator: Seek falls back but will not *save* the fallback

§18 narrowed the claim to calibration. That was still not the sharpest reading. From Stefan,
18 Aug 2026, having used it:

> My issue with Seek is that I couldn't save the sighting as "Katydid" — it only allows
> species-level observations. And with insects that will often not be possible.

So Seek's fallback is **presentational**. It tells you it is a katydid and then declines to keep
the record, because its data model has a species field and a katydid is not a species. Arter and
ObsIdentify are the same: an observation is a species or it is nothing.

That is a much better wedge than calibration, and it is a *data model* difference rather than a
screen difference — which is why no amount of UI work by a competitor closes it cheaply.

**Consequences, and they reach further than the result screen:**

1. **An `Identification` record stores the returned node at whatever rank it was.** Not a species
   field left blank, not a "pending identification" state. `Carabus sp.` and *Aglais urticae* are
   both complete records.
2. **The life list counts three numbers, never one.** Records, of which to species, of which to
   genus or coarser. Collapsing them into a single score is the quiet dishonesty this app argues
   against, and it is the mechanism by which a life list becomes a leaderboard — which §7 rules
   out for reasons that now have teeth.
3. **A record can be refined without losing its history.** "A ground beetle" becomes *Carabus
   granulatus* later; the original determination, its threshold and its model version stay
   attached. §4.4 already stores the threshold in force for exactly this reason.
4. **This is what the genus-as-leaf decision (§16) was for.** It stopped being a training detail
   the moment the record model had to hold it.

*Needs your call:* when a genus record is refined to species, does the species tick belong to the
date of the original sighting or the date of the refinement? Birders have strong views; the honest
answer is probably the sighting date with the refinement date recorded alongside, but it is a
product decision and this document does not get to make it.

`design/my-list.html` shows the screen this implies.

## 20. Three decisions from using the app — 18 Aug 2026

**Records hang on the node they were determined at, and every ancestor shows them.** A sighting
kept at *Carabus* is visible when browsing Carabidae, alongside a species-level record from the
next day. Two sightings, two photos, two ranks, both real. No "unidentified" bucket to one side —
that is where the other apps put things and where nobody looks again. The rank ladder on a record
shows explicitly what is settled and what is still open, so "species: not identified yet" is a
state the app displays rather than an absence it hides.

**The user can break a tie the model cannot.** Speckled and Great Green bush-cricket sit at 41%
and 44%; no threshold makes that a species answer, but a person who can see the wing length can.
So the candidate list is selectable, each option carrying the field mark that actually separates
it — not a thumbnail, which is only reassurance.

*And the record stores that the user decided.* A determination by tap is not a determination by
model, and reporting one as the other would be the same overclaim as Arter's 80% species guess,
relocated. `model_version` and `threshold` stay attached either way (§4.4).

**Reference photo beside your photo, during identification.** Seek does this and it is the right
call: comparison is how identification actually works. It also makes the candidate list useful
rather than decorative.

*Consequence, flagged now rather than at the end:* reference photos come from iNaturalist under CC
licences, and BUILD.md §8 already calls attribution a real obligation. This is the screen where it
bites. Photographer credit has to be stored per photo in the manifest — which stage 2 kept, so the
data is there — and shown somewhere findable.

*Still open:* whether refining a genus record to species dates the species tick to the sighting or
the refinement.

`design/identify-and-refine.html` is all three.

## 21. Export: int8 costs more than the budget saves — 18 Aug 2026

Stage 6 exports BioCLIP's image tower to ONNX and then checks that the export still computes
what torch computed. The check earned itself immediately.

| variant | size | ms/image (2 weak cores) | min cosine vs torch | top-1 agreement |
|---|---|---|---|---|
| fp32 | 329 MB | 196 | **1.00000** | **100%** |
| int8 dynamic, per-tensor | 84 MB | 74 | 0.918 | 93.8% |
| int8 dynamic, per-channel | 84 MB | 72 | 0.915 | 90.6% |
| fp16 | 165 MB | impractical | — | — |

**fp32 is exact.** Not "close": cosine 1.00000 to five places on all 32 real photos, and the head
picks the same class every time.

**int8 changes roughly one identification in fifteen.** Per-channel quantisation, the usual fix
for transformers, did not help — marginally better cosine, slightly worse agreement.

**fp16 is a dead end on CPU.** ONNX Runtime's CPU provider has no native fp16 kernels, so it
inserts casts around every operator; the parity run did not finish in fifteen minutes against
fp32's six seconds. Half the size, many times the latency.

### The §4.1 budget is not the binding constraint

This is the useful part. §4.1 worried that an INT8 ViT-B/16 might miss 1.5 s, and treated
quantisation as necessary. Measured: **fp32 runs at 196 ms per image on two throttled Xeon
cores.** A Tensor G4 has eight and is not throttled. The budget has room; the reason to quantise
would be APK size, not speed — and paying ~6% of identifications for 245 MB is a bad trade for a
sideloaded app whose entire argument is not overclaiming.

**Recommendation: ship fp32.** Revisit only if the APK becomes a real obstacle.

### Two methodology notes, because both nearly produced a wrong answer

The first parity run used **random normal tensors** as input and reported int8 top-1 agreement of
16.7%. That was an artefact: random noise is nothing like a CLIP-normalised photograph, and the
comparison also skipped the L2 normalisation the head was trained behind. Re-run on 32 real
iNaturalist photos with normalisation applied, the true figure is 93.8%. A parity harness fed
synthetic inputs measures the harness.

**"Top-1 agreement" is not "accuracy loss".** A disagreement can move either way, and 6% of
predictions changing might cost 6 points or none. Measuring the real delta means embedding a test
split through the int8 graph and re-running stage 5 — worth doing before any decision to quantise,
and not worth doing to justify shipping fp32, which is exact.

### Also fixed here

The first export traced at batch 1, which folded the batch dimension into the attention reshapes.
It ran at batch 1 and threw at batch 5 — exactly the size the app uses for multi-photo fusion, so
the failure would have appeared only on the feature the export exists to serve. Traced at batch 2
now, and the parity harness runs batches 1, 5 and 24 before it reports anything.

## 22. Preprocessing drift costs as much as quantisation — so it went into the graph

Having just refused int8 because it changed ~6% of identifications, the obvious next question was
whether anything *else* changes that many. It does, and it is the thing that would have been
reimplemented by hand on Android.

**Bicubic versus bilinear resize, same photos, same model: 91.7% top-1 agreement, min cosine
0.915.** That is a bigger effect than int8 quantisation. Android's `Bitmap.createScaledBitmap`
is bilinear. PIL — which every training embedding went through — is bicubic *with antialiasing
on downscale*. Two reasonable engineers would have shipped that gap without noticing, and it
would have presented as "the app is a bit worse than the notebook".

**So preprocessing is not reimplemented; it is exported.** The shipped graph takes raw `uint8`
NHWC pixels and returns logits:

```
image_uint8 [batch,h,w,3]
  -> Cast -> Transpose(NCHW)
  -> Resize(cubic, antialias, half_pixel, cubic_coeff_a=-0.5) -> 224x224
  -> /255 -> -mean -> /std
  -> ViT-B/16 -> LpNormalization -> MatMul(head) + bias
  -> logits [batch, n_taxa]
```

Verified against PIL + torch on 20 real photos: **100% argmax agreement, logit correlation
0.99978.** One file, 350.8 MB, **165 ms per image pixels-to-logits on two throttled cores**.

The app's remaining obligation is a centre crop to a square, which is integer arithmetic and
cannot drift. That is exact rather than approximate for a nice reason: cropping to a square of
side `min(w,h)` and then scaling to 224 gives the same pixels as scaling the short side to 224
and then cropping 224.

`antialias` on Resize requires **opset 18**, so the opset is load-bearing rather than incidental
— the torch exporter's automatic downgrade to 17 silently removed the attribute, and the first
attempt failed the ONNX checker. Better than passing.

**Softmax and temperature stay outside the graph.** The threshold is adjustable at display time
(§4.4), and baking a temperature in would freeze at export something the user is meant to move.

## 23. The model ships through CI, not through a file transfer

A 433 MB APK cannot be handed over this session's bridge — 20 MB per file, 30 MB per upload —
and a 335 MB `.onnx` should not be committed either. Both problems have the same answer: **the
repo carries the 4 MB head and the taxonomy, and CI rebuilds the model.**

| committed | size |
|---|---|
| `shared/model/head.npz` | 4.1 MB |
| `shared/model/taxonomy.json` | 743 KB |
| `shared/model/model_meta.json` | 684 B |
| `app/src/main/assets/lifelist.onnx` | **gitignored — built by CI** |

`lifelist-export` rebuilds the 335 MB graph from those plus a pinned public checkpoint, so a
release APK is reproducible from source rather than a binary someone once made and nobody can
regenerate. The BioCLIP download is cached between runs.

**The app degrades rather than crashes without a model.** `android.yml` runs on every push and
does not spend five minutes exporting; that APK has no model asset, so `Identifier.openOrNull`
returns null, the demo cases are shown, and the screen says *"No model in this build"*. A missing
asset should not make every push look broken.

**Built and verified here:** debug APK **433 MB**, assembles cleanly with the 335 MB asset,
`noCompress "onnx"` so ONNX Runtime can map it out of the APK instead of inflating it to disk on
first run.

**Still unverified, and it is the important part.** No line of `Identifier.kt` has run. The
session opens, the tensor is built, the rollup is called — on a device none of which has happened.
Compilation is not correctness, and this is the last link in the chain that only Stefan's Pixel
can close.

## 24. The first APK with a model said it had none — two bugs, one screen

Installed on the Pixel, the v0.2.0 build reported *"No model in this build — showing example
results"* while being several hundred megabytes larger than the previous one. The model was in
the APK. It failed to load, and the message pointed everyone at CI.

**Bug one: the error was swallowed.** `openOrNull` returned a bare null for both "no asset was
bundled" and "the asset is there and broke", so a real failure and an intentional debug build
read identically. Now `openOrReport` distinguishes them by checking the asset descriptor first,
and a failure shows its exception on screen. A model that is present and broken is a bug report,
and the reason belongs where the person holding the phone is.

**Bug two, and the actual cause: `assets.open().readBytes()` on a 335 MB file.** That pulls the
whole model into the Java heap, and Android's default heap is around 256 MB. It works on any
machine with real memory, which is exactly why it survived being written and reviewed here — the
container has 7 GB and never ran it anyway.

Fixed by materialising the model as a file in internal storage once and letting ONNX Runtime map
it, rather than holding it twice. Size is the version check: a new APK ships a different model,
and a copy interrupted mid-write matches neither.

**The general lesson, which is the one worth keeping:** `Identifier.kt` was written, compiled,
packaged into a 433 MB APK and shipped without a single line of it ever executing. §23 said so
plainly — "no line of Identifier.kt has run" — and the first thing it did on a real device was
fail. Compiling is not running, and the repo's own rule about not writing code you cannot test
had been quietly suspended for the Android layer because testing it needs hardware.

## 25. It identified a bush-cricket — and reference photos are ready to ship

v0.4.0 on the Pixel, first real end-to-end identification: **Leptophyes punctatissima at 97%**,
from a photograph, on the device. The runner-ups were *Tettigonia viridissima*, *Phaneroptera
falcata*, *Conocephalus dorsalis* and *Decticus verrucivorus* — every one a bush-cricket. The
embedding space is coherent, not lucky.

The same screenshot showed a defect. All four runner-ups carried an amber **OTHER BRANCH** label,
because the answer was a leaf and every other candidate is therefore outside it. True, and
useless: it puts a warning colour on four rows of a confident, correct identification. The flag
now only appears when the rollup stopped somewhere with room underneath it, which is the only
case where it means anything.

### Reference photos

Every one of the **2,294 leaves has a reference photograph with a named photographer and a
licence**. Nothing is uncredited, and a taxon whose credit went missing would be skipped rather
than shipped uncredited.

| licence | photos |
|---|---|
| CC-BY-NC | 1,257 |
| CC-BY | 853 |
| CC0 | 142 |
| CC-BY-NC-SA | 23 |
| CC-BY-SA | 19 |

Measured: 120 photos at `small` (240 px) is 3.2 MB, so all 2,294 is **~61 MB** and about 40
seconds of CI at 24 workers.

Same split as the model: the **index is committed** (294 KB — which photo, whose, under what
licence) and CI fetches the bytes. Other people's photographs are not checked into this
repository, and the bucket stays the canonical copy.

BUILD.md §8 called attribution a real obligation. This is where it stopped being a note: the
credits ride into the app beside the images, so nothing has to ask the network who took a photo.

## 26. The warm rewrite, and the life list

`ResultScreen.kt` rewritten to the direction in `design/result-screen-warm.html`, and
`LifeListScreen.kt` added.

**The result screen leads with the photograph and the common name.** Your photo beside the
reference, one sentence, confidence as a ring whose colour says whether the app stopped short on
purpose — green for a species, amber for a rank above it. The taxonomic key and the full
candidate list are behind a tap: nothing honest was removed, it stopped leading with apparatus.

The keep button **says what it will keep and at what rank**: "Add to my list" for a species,
"Keep as *Carabus*" for a genus. Nobody should have to guess which of those they are about to
save, and this is the screen where §19's whole argument either shows up or does not.

Reference photo credit sits under the sentence, on every screen that shows one. CC-BY is not
satisfied by a credits page nobody opens.

**The life list is grouped, and counts three numbers.** Grouped the way Seek groups, because
that part of Seek is right — seeing forty insects and no amphibians is what sends someone
looking for amphibians, so empty groups are kept and say so rather than being hidden.

What is not like Seek: a record kept at genus is *in* the list, in its group, counted. The header
carries **records / to species / to genus or above**, never a single score, because collapsing
them is precisely how a life list becomes a leaderboard — which §7 rules out and §19 finally
gives a reason for.

`LifeList.kt` in `core` holds all of it: grouping, the three totals, browsing everything under a
node, and refinement. 15 tests, and the ones that matter are the counting rules — that an
indeterminate leaf is not a species tick, that ten photographs of one mallard is one taxon, that
browsing a family shows both the species record and the genus one, and that refining upward or
sideways is refused.

**Storage is a JSON file and a photo directory, not a database.** Hundreds of rows on a personal
device; Room would be a schema, a migration story and a DAO in exchange for nothing. Writes are
atomic, because a life list that loses records to a mid-write kill is worse than one that never
existed.

Still unrun on a device, as ever.

## 27. Every build was signed with a different key

Installing a new APK over an old one failed and needed an uninstall first. Signature mismatch:
Android will not let a differently-signed APK replace an installed one, and the release build
used `signingConfigs.getByName("debug")` — a key Gradle generates **per machine**, so every CI
run signed with a fresh one.

The reasoning behind that choice was sound and the consequence was not considered. "A real
signing key does not belong in a public repo" is true; it does not follow that a *random* key
per build is the alternative.

Fixed by committing a keystore at `app/keystore/sideload.jks`, password `sideload`, in plain
sight. That looks like a mistake and is not:

- It protects nothing. It is not a Play upload key, it identifies nobody, and the APK it signs
  is a sideload build from a public repository that anyone can rebuild.
- Its only job is to be **the same key every time**, so v0.6 can replace v0.5 on the phone.
- Distributing anywhere real needs a proper key from CI secrets — and that will be a *different*
  signing config, not this one with a better password. The distinction matters: a secret-backed
  key that people have got used to seeing committed is worse than no secret at all.

Debug builds use it too, so a locally-built APK and a CI one can also replace each other.

**One more uninstall.** Anything already on the phone was signed by a key that no longer exists,
so v0.5.1 needs a clean install. Everything after that updates in place.

---

## 28. The taxonomy shipped with no common names at all — 18 Aug 2026

Reported from the phone as "the common name isn't given, it just gives the species name twice".
It was not a display bug.

`shared/model/taxonomy.json` had `vernacular_en: null` on **all 4,657 nodes**. The two scripts
that built it (`train_smoke.py`, `full_run.py`) constructed `GbifTaxon(...)` with `key`,
`scientific_name`, `rank`, `status`, `lineage` and `lineage_names` — and quietly omitted
`vernacular_en` and `vernacular_da`, both of which default to `None`. `build_taxonomy_nodes`
faithfully copied the nulls through.

`headline(answer)` then fell through its `answer.vernacular != null` branch to the scientific
name, and the line directly beneath printed `answer.scientificName` again. Two correct lines of
code, one missing field, and the screen said *Leptophyes punctatissima* twice.

**The fix needed no refetch of anything expensive.** `cache/taxa_raw.json` already held 10,528
English and 11,138 Danish names from stage 1 — they were fetched, then dropped on the floor. The
ancestors are a different matter: family, order and class nodes are *derived* from
`lineage_names` and were never GBIF records in their own right, so they had no vernaculars to
drop. Those were fetched, 2,677 of them, from `/species/{key}/vernacularNames`.

| rank | with an English name | of |
|---|---|---|
| species | 2,115 | 2,294 |
| genus | 877 | 1,596 |
| family | 442 | 532 |
| order | 121 | 168 |
| class | 36 | 45 |

Family coverage matters more than it looks. A family-level answer is the answer this whole app
exists to give, and "Carabidae" is not an answer a person can use — "Ground beetles" is.

Two smaller decisions fell out of it:

- **Case is normalised on the first letter only.** GBIF returns whatever the contributing
  checklist used: `ground beetles` sits next to `Common Speckled Bush-cricket`. Lower-casing the
  rest would wreck `Eurasian Teal`, so only the first character is touched.
- **Indeterminate leaves inherit their genus's name.** `Arctium sp.` is a burdock, and GBIF has
  never heard of the synthetic node, so it takes the parent's vernacular. Saying "Burdock, and
  the species is not determined" is exactly what §1.1a is for.

`training/tests/test_shipped_assets.py` now asserts all of this against the real file — the
generated asset had no test of any kind, which is why a field being null on every row shipped.

---

## 29. Multi-photo fusion on device is §3.2, not §3.1 — and that is the export's fault

Several photos of one individual now fuse before the head sees them. The spec's default is
**§3.1, embedding-space**: normalise, average, renormalise, then run the head. The app does
**§3.2, probability-space** instead, and the reason is not a preference.

The exported graph folds preprocessing, the backbone, the L2 and the linear head into one
pixels-to-logits function (§21–22). That was the right call for a single photo — it is what
makes preprocessing drift impossible — but it means the 512-d embedding §3.1 wants to average
is not an output of the model. There is nothing on device to average.

§3.2 is available, and cheaper than it looks. Writing the geometric mean in log space:

```
log p_i     = z_i / T - logsumexp(z_i / T)
mean_i      = mean(z_i) / T - mean(logsumexp(z_i / T))
softmax(mean_i) = softmax(mean(z_i) / T)
```

The per-photo log-normalisers are constants across taxa, so the final softmax divides them out.
**Averaging logits is exactly the geometric mean of the probabilities** — one softmax instead of
k, and no underflow to guard against with 2,294 classes.

**Still to do:** re-export with the embedding as a second output, and switch to §3.1. Worth
measuring rather than assuming — the two agree on easy cases and diverge exactly where the
photos disagree, which is the case fusion exists for.

---

## 30. Why it "felt like a web app"

Reported as a feeling, and it had a specific cause. The app had:

- no `Scaffold`, no `TopAppBar`, no navigation bar — three screens, each drawing its own
  header and its own way back
- hand-rolled `Box` + `clickable` in place of every button, so no ripple, no elevation, no
  state layer, and touch targets set by whatever padding happened to be there
- a `MaterialTheme` given five colours and no typography or shapes, so the two M3 components
  that *were* used drew from defaults unrelated to everything around them
- a cold launch straight into a full-bleed viewfinder with two floating boxes on it
- no launcher icon, so the phone showed the green Android default

None of that is a taste question. Compose does not make an app feel like Android; using
Material 3 does. The rebuild keeps the palette exactly — same rust, same paper — and expresses
it as a real `ColorScheme`, `Typography` and `Shapes`, so a `Button` is a Material button that
happens to be rust.

Dynamic colour was refused deliberately. `ColorScheme.fromSeed` off the wallpaper is the modern
default and it would make this look like every other Android 12+ app; the warm palette is the
identity, and every competitor is already green.

---

## 31. Wikipedia, bundled — and a resume cache that recorded a throttle as a fact

"I would like information on each species (Wikipedia is totally fine), so that I can read a bit
about each species to help with ID."

**Bundled, not fetched.** The text ships in the APK. This is an offline-first app used in a
field with no signal, and an "about this" panel that is blank exactly when you are standing in
front of the animal is worse than no panel. The whole taxonomy costs about 1.6 MB of text next
to a 350 MB model.

**The Action API, not the REST summary endpoint.** `/api/rest_v1/page/summary/{title}` is one
page per request, and an anonymous caller from this sandbox is throttled to a 25-second
`Retry-After` — 4,645 titles would take a day. `action=query&prop=extracts&exlimit=max` takes
**50 titles per request**: the same job in 94 calls.

**A common name recovers what a binomial misses.** *Aglais urticae* is a redlink where "Small
tortoiseshell" is not, so a second pass asks for English vernaculars wherever the scientific
name found nothing.

That pass needed a guard. "Small tortoiseshell" is also a cat coat and a hair comb, and an
identification that opens an article about combs is worse than one that opens nothing. Every
candidate must name the taxon in its own text — the binomial, or at least the genus — before it
is accepted. Wikipedia's own convention puts the binomial in the first sentence, so the check
costs a handful of real articles and rejects every absurd one.

| rank | with an article | of | |
|---|---|---|---|
| species | 2,220 | 2,294 | 97% |
| genus | 1,497 | 1,596 | 94% |
| family | 527 | 532 | 99% |
| order | 166 | 168 | 99% |
| class | 44 | 45 | 98% |

2.6 MB of text, keyed by taxon id, parsed lazily on first use rather than at startup — decoding
it on the main thread before the viewfinder appears is a stutter a user would feel and never
understand.

### Two bugs, both of which reported themselves as coverage

This job produced three plausible-looking coverage figures before it produced a correct one:
38%, then 61%, then 81%, then 96%. Every wrong number looked like a fact about Wikipedia.

**First: the resume cache recorded a throttle as a fact.** The initial attempt ran ten threads
flat out, was rate-limited on nearly every request, and wrote its failures into
`wikipedia_missing.json` — the same file it consulted next run to skip titles already known to
have no article. It recorded **3,930 titles as having no Wikipedia article**, including *Anas
platyrhynchos*. Every later run skipped the mallard because a throttle three hours earlier had
been filed as a fact about the bird.

`fetch_all` now returns three things rather than two: articles, **absent** (Wikipedia answered
and has no article — permanent, cache it forever) and **unreachable** (we could not ask — a
fact about the afternoon, never written to disk). The poisoned file was discarded wholesale
rather than repaired, because there is no way to tell which entries were real.

**Second: `prop=extracts` is capped at 20 pages per request, and does not say no.** Asking for
50 titles returns all 50 pages; the last 30 simply have no `extract` field, which is
indistinguishable from "there is no article" unless you look at `limits.extracts` in the
response. Batches were 50. So roughly three in five titles were being marked absent on the
strength of a request-size mistake — and the resulting 81% read as a believable coverage
ceiling for English Wikipedia on Danish taxa.

The cap is *reported*, so it is checkable rather than guessable. `is_truncated()` compares the
batch against `limits.extracts` and hands the whole batch to `unreachable`, and `BATCH` is now
20 with a test asserting it never exceeds the cap. Real coverage was never 81%; it was 96%.

The shape is worth naming, because this project has now hit it twice: **a cache must record
what was observed, never what was attempted.** Stage 3's shard files get this right — a shard
exists only once it has been written atomically — and this did not. The tell in both cases was
the same: a number that got worse quietly, and looked like the world rather than like a bug.

---

## 32. "Doesn't feel coherent" had a structure, not a style — 19 Aug 2026

Reported as a feeling, twice, and the second time after the Material 3 rebuild had already
fixed the obvious things. Worth taking seriously rather than restyling again.

**The diagnosis: the app was a classifier with a list bolted on.** It opened on a viewfinder;
the collection lived behind a tab. That is a tool you use, and it is why nothing felt like it
belonged to anything — there was no *place*, only three screens taking turns. A life list is a
collection you add to, and a collection opens on what you have.

So home is now the list, capture is a full-screen moment entered from one button and left with
an X, and there is no tab bar because there is nowhere else to be.

Four things followed from that, and each was a separate defect:

1. **Nothing was ever a reward.** The app stored forty sightings without once saying "you have
   not seen this before". Firsts are the whole of why a collection is worth keeping, and
   `LifeList.isFirst` is four lines. Deliberately per *taxon*, not per genus — a mallard after
   a teal is a first, and making the badge rarer to feel special would be flattering the list
   rather than describing it.
2. **The result screen read as a document.** Three collapsed grey expanders stacked in a row
   was the most web-page-like thing in the app. Everything honest survives, behind one "Why
   this answer?"; the photograph, the name and one sentence are the screen.
3. **The hedge was passive.** It stopped at genus, explained itself, and left you there. It now
   offers the contenders as photographs to choose between. `LifeList.choices` returns only
   candidates *under* the returned node — a runner-up in another family belongs in the full
   list (§4.3) but picking it would contradict the answer rather than refine it. A choice is
   stored as `Determiner.USER` with `refinedFrom` set, so what the model said is kept beside
   it and never overwritten (§20).
4. **Too many voices.** Serif, sans and letterspaced caps, with rust, ochre, sage and moss all
   carrying signal. Now: serif for organism names only, rust for action, moss for "settled",
   amber for "stopped short deliberately", and nothing else means anything.

### The prototype was the cheapest part

Rather than guess in Kotlin at 15 minutes per APK, the redesign went out first as a single
self-contained HTML file with real iNaturalist photographs in it, tappable on a phone. Wrong
direction costs one file instead of one release. That is worth repeating for anything where the
question is "how does this feel" rather than "does this work".

---

## 33. The screens can now be looked at without a phone

Every screen in `app/` has shipped at least one bug that a single glance would have caught: the
camera drawn under the system buttons, a slider that did nothing, a species name printed twice,
a status line truncated to 28 characters inside a box 96dp wide. The cause was always the same.
Nothing in `app/` had ever been *run* before it reached the phone, because running it meant a
device, and this container has no `/dev/kvm` so it cannot run an emulator.

**Paparazzi renders composables through layoutlib on the JVM in about a second**, which closes
that loop without a device. `./gradlew :app:recordPaparazziDebug` writes eight PNGs, and CI
uploads them on every push.

It found three layout faults in the first run of the new screens:

| what the render showed | why it happened |
|---|---|
| "Why this answer?" nowhere on the confident screen | hero photo at 1.12 × screen width pushed it two scrolls down — the one thing this app exists to show, hidden |
| the Wikipedia paragraph ending mid-sentence under the action bar | 140dp of bottom padding against a ~160dp bar; it read as a rendering fault rather than as more text |
| "Nothing yet in … **other**" on the home screen | `UNGROUPED` listed as a gap to go and fill, which is not a thing anyone can find |

None of those would have failed a unit test. All three would have been reported from a field in
Denmark a day later.

Two notes for anyone extending the file. Bitmaps must be `by lazy`, not eager fields —
layoutlib's graphics stack is stood up by the Paparazzi rule and a field initialiser runs
before any rule, so `Bitmap.createBitmap` returns null and every test dies on the same NPE. And
these are deliberately *not* pixel-comparison tests in CI: golden images across layoutlib
versions are a maintenance tax, and the value here is being able to look at the thing.

---

## 34. Eight things from a day of real use — 19 Aug 2026

The app has now been used on actual moths in an actual garden, which found more in one evening
than the previous three days of building. All eight are fixed; two were bugs of the kind that
look like a design decision from the outside.

### The ermine moth was not asked about, and the crambid was

Two identifications, minutes apart. *Yponomeuta* at 71% offered no "which one is it?"; Crambidae
at 99% offered three. Same code.

`LifeList.choices` was reading `RollupResult.candidates`, which is the **global top five**. A
genus can easily hold one of those and no more — *Yponomeuta evonymella* at 69% was the only
one under the genus, so `size < 2` returned nothing. It now works from the **full probability
vector** via `subtreeLeafIndices`, so every leaf under the node is considered however far down
the global ranking it sits.

Two rules came with it. Indeterminate leaves are excluded: `Yponomeuta sp.` *is* the genus-level
answer, and listing it among the species to choose between offers the question as one of its own
answers. And **one contender is still a question** — "is it this one?" is something a naturalist
can answer, and the old `size < 2` rule was the reason the ermine moth was mute.

### Back threw the identification away

Picking a species and then pressing back landed on the home screen with the photograph gone, so
the shot had to be taken again. Back now un-picks first, then leaves the result, then leaves the
group — the order a person actually means it in.

### Photographs were only ever in memory

"Does it add it to my regular camera roll, so I don't lose the photo if I go back to the wrong
screen?" It did not, and it was worse than that: an un-kept capture existed only as a bitmap, so
backing out destroyed it. Every shutter press now writes a JPEG to `Pictures/Life List` through
MediaStore, which needs no permission from API 29.

### A second photo changed nothing visible

The hero kept showing the first one and the only evidence of fusion was "2 photos fused" in grey
type at the bottom of a scroll. There is now a thumbnail strip on the photograph, and a new shot
is the one you land on.

### The group cards went nowhere

`onOpenGroup = { }`. A number you cannot open is a scoreboard, and the point of grouping was
never the score. `GroupScreen` lists the group's sightings, newest first, with genus-level
records sitting beside species-level ones rather than in a bucket off to the side (§20).

### Location was three separate faults

Reported as "the location is not filling in", and none of the three was the one I would have
guessed:

1. **Asked too late.** Permission was requested after the first *keep*, so the record that
   triggered the dialog could never benefit from it. Now requested when the camera opens.
2. **Never actually requested a fix.** `getLastKnownLocation` is null on a phone where nothing
   has asked recently. `Where.current` now makes a real single-shot request with a 2.5 s
   deadline and falls back to last-known.
3. **The photograph knew and was not asked.** A picture from the gallery carries EXIF GPS, and
   that is better evidence of where the sighting happened than wherever the phone is standing
   now — which may be a sofa, three days later.

Suburb and town come from `Geocoder`, resolved once when the record is made and then stored: a
field that empties itself on a walk with no signal is worse than one that never filled.

**No inline map.** That means the Maps SDK and an API key in a public repository, or a second
tile source and its licence. Tapping the location opens the phone's own map app on the point,
which is one line and lands somewhere the user already knows how to use.

### "Why this answer?" was two mistakes

Renamed to **All results**, which is what it contains, and the ⓘ removed — an information icon
promises that tapping *it* explains something further, and here it explained nothing. It was
decoration wearing an affordance's clothes.

### A determination was final, and should never have been

"If I select genus level but find out more info later, I should be able to select the correct
species." That is the promise the data model has been making since §19. Opening a record now
offers **Settle the species**: a searchable list of the species under the recorded node, going
through `LifeList.refine`, which refuses to move sideways or upward and keeps the original in
`refinedFrom`. Photographs can be added to an existing record too — same sighting, same date,
same id.

Not a re-run of the model: the probabilities are long gone by then, only the one number that was
true at the time is stored. Settling later is a *choice*, and it is recorded as the user's.

---

## 35. What a real map would cost — measured, then deferred — 19 Aug 2026

`Where.openInMaps` hands the sighting to the phone's map app, which is one line of code and no
dependency. The question was what it would take to draw the map *inside* the app. Measured
rather than estimated, because the answer turns on numbers nobody remembers correctly.

### MapLibre Native + our own OpenStreetMap extract

No API key, no account, and it is the only option that works where this app is used.

| | measured 19 Aug 2026 |
|---|---|
| `org.maplibre.gl:android-sdk:13.5.0`, arm64-v8a only | **12.2 MB** |
| …all four ABIs | 47 MB |
| Glyphs (Latin ranges) + sprite, bundled | 0.45 MB |
| Denmark extract, z0–10 — towns and coastline | 29.3 MB |
| Denmark extract, z0–12 — neighbourhood | **124.3 MB** |
| Denmark extract, z0–14 — street level | 462.5 MB |

The extracts are real: `pmtiles extract` was run against the Protomaps daily planet
(`20260817.pmtiles`, 120 GB, z0–15) over HTTP range requests with
`--bbox=7.9,54.4,15.3,57.9`. Denmark z0–14 took 51 s and 100 requests. Each zoom level costs
roughly 4× the one below it, which is the number to remember.

Three findings that make the shape of the work clear:

- **PMTiles has been a first-class MapLibre Android source since 11.7.0**, including
  `pmtiles://file://` for a local archive. So an offline pack is a file in `filesDir` and a URL,
  not a tile database and a sync layer.
- **It cannot be read from `asset://`** — `AssetManagerFileSource` does not implement the byte
  ranges PMTiles needs. The archive has to be copied out of the APK first, which is exactly the
  dance `Identifier.modelFile` already does for the 350 MB model.
- **GitHub release assets serve byte ranges.** Verified against our own v0.6.1 APK:
  `Accept-Ranges: bytes`, `206 Partial Content`. So the extract can ride on a release beside the
  APK and be *streamed* when there is signal, with the download optional.

Licence is ODbL — an "© OpenStreetMap" line on the map, which the style already carries.

### Google Maps

Cheaper to build (~2 hours) and better looking. Google's own page says **"All mobile usage of
the Maps SDK for Android is unlimited"**, so displaying a map is not billed. But it needs a
Cloud project with **billing enabled and a card on file**, and an API key in the repository. The
key can be restricted to package name plus release SHA-1, which makes a leaked one close to
useless; Google's own advice is still not to commit it.

The disqualifying part is not the key. **There is no offline mode.** Grey squares in a field
with no signal is the exact situation this app exists for, and this app has otherwise spent 350
MB to avoid needing a network.

### Decided: neither, for now

**[decided 19 Aug 2026]** Leave `openInMaps`. The map app on the phone is already good, already
offline, and already installed. If this comes back, it is MapLibre, streamed from a GitHub
release, with the offline pack a toggle rather than a default — the numbers above are the ones
to build against and they do not need measuring again.

---

## 36. v0.7.1 crashed on launch for anyone with a saved record — 19 Aug 2026

Reported as: could not open after updating, worked after a clean install, crashed again right
after the first identification, and then crashed on every launch. No error shown.

That pattern names the bug exactly. **Empty list works, one record kills it.** It is a lookup
against the wrong tree.

```kotlin
val live = identifier != null && leafProbabilities != null
val taxonomy = if (live) identifier!!.taxonomy else Demo.taxonomy   // <- the bug
```

`live` requires an identification to have *already happened this session*. On every cold start
it is false, so the home screen was handed `Demo.taxonomy` — a hand-built stand-in with
nineteen nodes — and asked to look up real saved taxa in it. `Taxonomy.node` is
`nodes.getValue`, which throws `NoSuchElementException`. The first frame died, and so did every
frame after it, with no way back except clearing the app's data.

It also explains the crash straight after the first identification: `startOver()` sets
`leafProbabilities = null`, which flips `live` back to false, which swaps the taxonomy under the
home screen that is about to draw the record just saved.

v0.7.0 had the same substitution written as `identifier?.taxonomy ?: Demo.taxonomy`. That is
only wrong during the window while a 350 MB session opens — which is why v0.7.0 was flaky where
v0.7.1 was reliably dead.

### Why the fix is three things and not one

**1. The list reads the real taxonomy, and there is no fallback.** `Loaded` now carries the
parsed taxonomy independently of the model session, because the two fail independently: a 350 MB
ONNX session can refuse to open on a device where an 850 kB JSON file parses perfectly, and the
life list needs only the second. While it parses, the screen shows a spinner rather than an
empty list — "you have collected nothing" is a lie worth avoiding.

**2. A missing taxon stops being fatal.** This one outlives the wiring bug and would have bitten
eventually anyway: **a life list is permanent and a taxonomy is a build artefact.** Retrain at a
different occurrence threshold and taxa leave the tree; every record of a departed taxon then
points at nothing. `Taxonomy.nodeOrNull` and `contains` now exist beside `node`, `lineage` and
`isAncestorOrSelf` return empty and false rather than throwing, and everything in `LifeList`
that reads a *stored* id degrades. `node` still throws, because the rollup operates on ids it
produced from that same tree and a miss there is a broken invariant.

An orphaned record stays visible, reads "Not in this model", is filed under Other, and counts as
a record but not as a species. Hiding it would look like the app had eaten a sighting.

**3. The demo answer can no longer be saved.** With no model bundled, the result screen shows a
demo identification whose taxon ids mean nothing to the real taxonomy. Keeping one would put an
unresolvable record in a real list. The button now reads "No model in this build" and is
disabled.

`RecordStore.save` also stopped throwing — a failed write returns false instead of taking the
app down mid-gesture.

### The app now keeps its own crash log

"Is there an error log somewhere?" There was not. The only routes to an Android stack trace are
`adb logcat` from a computer or Developer options → Take bug report, neither of which is a
reasonable thing to ask of someone in a garden holding a moth.

`CrashLog` installs an uncaught-exception handler in `onCreate` before anything else can throw,
writes the trace plus version and device to `filesDir`, and the next launch offers it in a sheet
with a share button. The previous handler is still called afterwards, so the system dialog and
any platform reporting behave exactly as before — this only adds a copy the user can reach.

### What would have caught it

`OrphanedRecordTest` (core) and two Paparazzi renders. The render test is the honest one: it
fails by *throwing during composition*, which is precisely how the bug presented. Both were
written before the fix and both failed against the old code.

The deeper lesson is narrower than "test more". The demo taxonomy was a convenience that made a
screen work before there was a model, and it quietly became a fallback on a code path that
handles permanent user data. **A stand-in for missing data must never be reachable from the code
that reads real data.** It is now structurally impossible: the list surfaces do not have a
reference to `Demo` at all.

---

## Open questions

1. **Inference backend.** Accept the CPU-EP-first proposal in §1 above, or hold NNAPI as the
   default until benchmarked on your actual Pixel 6a?
2. **Backbone checkpoint.** Spend one Colab session comparing `bioclip` against
   `bioclip-vit-b-16-inat-only` before committing?
3. ~~**Genus-level leaves.**~~ **Decided 18 Aug 2026: yes.** See §16.
4. **The 1.5 s budget.** If an INT8 ViT-B/16 on CPU misses it on a Tensor G1, which gives —
   the budget, the resolution, or the backbone?
