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

## Open questions

1. **Inference backend.** Accept the CPU-EP-first proposal in §1 above, or hold NNAPI as the
   default until benchmarked on your actual Pixel 6a?
2. **Backbone checkpoint.** Spend one Colab session comparing `bioclip` against
   `bioclip-vit-b-16-inat-only` before committing?
3. **The 1.5 s budget.** If an INT8 ViT-B/16 on CPU misses it on a Tensor G1, which gives —
   the budget, the resolution, or the backbone?
