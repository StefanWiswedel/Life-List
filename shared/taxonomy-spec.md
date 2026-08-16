# Taxonomy Spec — shared contract

**Status:** contract. Both `training/` (Python, reference) and `app/` (Kotlin, production) implement
this. Where they disagree, this document is right and the code is wrong.

Any change here is a breaking change: bump `spec_version` in `model_meta.json` and update both
implementations in the same commit.

`spec_version: 1`

---

## 1. Data model

### 1.1 Taxonomy tree

A single rooted tree. Every node:

| field | type | notes |
|---|---|---|
| `taxon_id` | `int32` | GBIF taxon key. Stable across model versions. |
| `parent_id` | `int32?` | `null` for root only. |
| `rank` | `string` | One of the ranks in §1.2. |
| `scientific_name` | `string` | Accepted name. Never a synonym. |
| `vernacular_da` | `string?` | |
| `vernacular_en` | `string?` | |
| `leaf_index` | `int32?` | Non-null **iff** this node is a leaf. Index into the head's output vector. |

Root is synthetic: `taxon_id = 0`, `rank = "root"`, `scientific_name = "Life"`.

**Invariants** (assert these in both implementations at load time):

1. Exactly one node has `parent_id == null`.
2. The graph is acyclic and connected — every node reaches root by following `parent_id`.
3. `leaf_index` values are exactly `0..N_taxa-1`, each used once.
4. A node has `leaf_index != null` iff it has no children.
5. A child's `rank` is strictly deeper than its parent's rank (§1.2 ordering).

Invariant 5 permits rank skipping. Real taxonomies have gaps — a genus whose family is
unplaced attaches directly to order. Do not fabricate intermediate nodes to fill them.

### 1.2 Ranks

Ordered, coarsest first:

```
root, kingdom, phylum, class, order, family, genus, species, subspecies
```

`species_aggregate` sorts at the same depth as `species` and is permitted as a leaf where the
training data cannot separate the members (§2.2 of the build prompt). Rendered as
`Taraxacum officinale agg.` — never italicised in full; the `agg.` stays roman.

### 1.3 Synonyms

Separate table, not tree nodes:

| field | type |
|---|---|
| `synonym_name` | `string` |
| `accepted_taxon_id` | `int32` |
| `source` | `string` |

Used for search (§4.2 of the build prompt) and for display when the user searches a name the
tree does not contain. Synonyms are **never** prediction targets and never appear in a rollup
result.

---

## 2. Calibration

The head emits logits `z` over `N_taxa` leaves. Temperature `T` is fitted on the held-out
validation split and stored in `model_meta.json`.

```
p_i = softmax(z_i / T)
```

`T` is a single scalar, `T > 0`. Applied **before** any fusion in probability space and before
rollup. Both implementations must apply it exactly once — double-applying temperature is a silent
accuracy regression that looks like underconfidence.

---

## 3. Multi-image fusion

`k` images, `1 <= k <= 5`. Each yields a raw backbone embedding `e_i` of dimension 512.

### 3.1 Embedding-space fusion (default)

```
ê_i      = e_i / ||e_i||_2
e_fused  = mean(ê_1, ..., ê_k)
ê_fused  = e_fused / ||e_fused||_2
z        = head(ê_fused)
p        = softmax(z / T)
```

Note the normalise-average-renormalise. The mean of unit vectors is not a unit vector; the head
is trained on unit vectors, so the renormalisation is required, not cosmetic.

### 3.2 Probability-space fusion (flagged alternative)

```
p_i      = softmax(head(ê_i) / T)     for each image
p_fused  = geometric_mean(p_1, ..., p_k)
p        = p_fused / sum(p_fused)
```

Compute the geometric mean in log space to avoid underflow with `N_taxa` in the thousands:

```
log_p_fused = mean(log(p_1), ..., log(p_k))
p           = softmax(log_p_fused)
```

Clamp probabilities to `>= 1e-12` before taking logs.

### 3.3 Which one ships

Unknown until measured. Training stage 5 reports both on the validation set; the winner is
recorded in `model_meta.json` as `fusion_mode: "embedding" | "probability"`, and the app reads it
rather than hardcoding. Do not assume embedding fusion wins because it is listed first.

**Required property:** whichever mode ships, adding a photo of the same individual must not
*decrease* mean confidence in the correct taxon across the validation set. If it does, that is a
bug in fusion, not a fact about the data.

---

## 4. Rollup

### 4.1 Node probabilities

For every node `n`:

```
P(n) = sum of p[leaf_index] over all leaves in subtree(n)
```

`P(root) == 1.0` up to float error. Leaves have `P(leaf) == p[leaf_index]`.

**Determinism requirement.** Floating-point addition is not associative, so Python and Kotlin
must sum in the same order or the golden-file test will drift. Rule: accumulate in **ascending
`leaf_index` order**, in `float64`, then cast to `float32` for comparison and display. Implement
as a single pass over the leaf array per node, not a recursive tree walk with arbitrary child
ordering.

### 4.2 Descent

```
node = root
loop:
    children = children_of(node)
    if children is empty: break
    best = argmax over children of P(child)
    if P(best) >= threshold:
        node = best
    else:
        break
return node
```

Notes that matter:

- The comparison is `>=`, not `>`. A probability exactly equal to the threshold descends.
- **Tie-breaking:** when two children share the maximum `P` (exactly equal in `float64`), pick the
  one with the lower `taxon_id`. Deterministic, arbitrary, and documented — which is enough.
- `P` is non-increasing as you descend, since a parent's probability is the sum of its children's.
  So the loop terminates at the first failure and no deeper node could have passed. Assert this
  monotonicity in tests.
- If the loop breaks at `root`, nothing cleared threshold even at kingdom level. Return root and
  let the UI take the §4.1.7 path — say so plainly and offer to save as unidentified.

### 4.3 Result

```
RollupResult:
    taxon_id:    int32       # may be root
    rank:        string
    probability: float32     # P(returned node)
    candidates:  [Candidate] # top-5 leaves by p, descending
    threshold:   float32     # the threshold actually used
```

```
Candidate:
    taxon_id:    int32
    leaf_index:  int32
    probability: float32
```

Candidates are the top-5 **leaves** by `p`, regardless of where the rollup landed. They are not
restricted to the returned node's subtree — a naturalist wants to see the runner-up genus, and
hiding it because the rollup stopped higher would be exactly the opacity §2.4 forbids.

Ties in the top-5 ordering break by lower `taxon_id`, as in §4.2.

### 4.4 Threshold

Default `0.70`. User-adjustable across `0.50–0.95` in settings, stored per-user, applied at
display time. The stored `Identification` record keeps the full leaf probability vector's top-5
and the threshold in force when it was made, so changing the setting later re-renders old
identifications honestly rather than rewriting history.

---

## 4A. Audio: detection vs identification

The rollup in §4 assumes a normalised distribution over mutually exclusive leaves. The vision
head provides one. **BirdNET does not** — it emits independent per-class sigmoid confidences, and
several species genuinely can be singing at once. Summing sigmoid scores up a tree produces
numbers that look authoritative and mean nothing.

So audio takes an extra step before it reaches §4.

### 4A.1 Detection

Over a 3-second window, class `c` is **detected** if `sigmoid_c >= detection_threshold`.
Multi-label by design: three singing species give three detections. Default
`detection_threshold = 0.25`, user-adjustable. This is BirdNET's native semantics, undistorted.

### 4A.2 Confusion set

Each detection is resolved independently. For detection `c` with score `s_c`:

```
confusion_set(c) = { d : d in danish_class_list
                       and s_d >= s_c * margin
                       and lca_rank(c, d) is at or below FAMILY }
```

`margin` defaults to `0.5`. The rank constraint keeps the set taxonomically coherent — a
simultaneous frog and warbler are two detections, not two candidates for one identification.

`c` is always in its own confusion set.

### 4A.3 Conditional renormalisation

```
p_d = s_d / sum(s_e for e in confusion_set(c))
```

This is a proper conditional distribution: *given that this sound is one of these, which is it?*
It is then fed to §4 unchanged, over the subtree induced by the confusion set.

The result: two *Phylloscopus* candidates at 0.45 and 0.40 resolve to the genus, not to a
coin-flip binomial — the same honesty property the vision path has, from the same code.

### 4A.4 Geographic prior

BirdNET's geo model takes latitude, longitude and week-of-year and returns per-species range
likelihoods. Apply as a **prior, not a mask**:

```
s'_d = s_d * (geo_d ^ geo_weight)
```

`geo_weight` defaults to `1.0`; `0.0` disables. Renormalise afterwards.

**Ordering is load-bearing.** The confusion set (§4A.2) is built from **raw** scores; the prior
is applied only afterwards, within the set. Applying the prior first lets a strong seasonal prior
push a candidate below the margin and out of the set entirely — which is a silent mask wearing a
prior's clothing. Built in this order, an out-of-season species stays visible on the result card
and is outvoted rather than erased. Both implementations must preserve this order, and both test
suites assert it.

Never let `geo` reach exactly zero in the multiplication; clamp to `1e-6`. A range likelihood of
zero must lower a species' odds, never make it unloggable.

**Store the raw pre-prior scores on the observation.** A hard filter would make a genuine vagrant
unloggable — precisely the record most worth having. When the prior materially changed the
answer, the UI says so. A visible prior is honest; a silent mask is not.

### 4A.5 What is stored

An audio session records: the recording, per-window raw sigmoid vectors (top-k only, to bound
size), the geo prior applied, the confusion sets, and the resulting rollups. Each detection is
savable to the life list independently. A session is an observation *container*, not a single
observation.

---

## 5. Preprocessing

Verified against the BioCLIP v1 `open_clip_config.json`, not assumed from CLIP defaults.

| parameter | value |
|---|---|
| input resolution | 224 × 224 |
| resize | shorter side to 224, bicubic |
| crop | centre crop 224 × 224 |
| channel order | RGB |
| scale | `[0,255] → [0,1]` |
| mean | `0.48145466, 0.4578275, 0.40821073` |
| std | `0.26862954, 0.26130258, 0.27577711` |
| dtype | `float32`, NCHW |
| embedding dim | 512 |

These happen to equal the OpenAI CLIP constants, because BioCLIP v1 was fine-tuned from the
OpenAI ViT-B/16 checkpoint and kept its preprocessing. Verified, not inherited.

### 5.1 Parity test (mandatory)

`shared/golden/` holds:

- `golden.jpg` — one fixed 3-channel image, committed.
- `golden_preprocessed.npy` — the Python `float32` NCHW tensor, committed.
- `golden_embedding.npy` — its `float32` 512-d backbone embedding, committed.
- `golden_rollup.json` — a fixed leaf probability vector, threshold, and expected `RollupResult`.

Kotlin instrumented tests assert:

1. Preprocessed tensor matches within `max |Δ| < 1e-3` per element. Bitmap decoding and bicubic
   resampling differ between libraries; this tolerance catches channel-order and normalisation
   bugs while surviving legitimate resampler differences.
2. Embedding cosine similarity `> 0.99`.
3. Rollup output matches `golden_rollup.json` exactly — same `taxon_id`, same rank, probability
   within `1e-5`, identical candidate ordering.

Test 3 needs no model and no ONNX session: it is pure arithmetic over a fixed vector, so it runs
fast and fails loudly. Write it first.

---

## 6. `model_meta.json`

```json
{
  "spec_version": 1,
  "model_version": "2026-08-16-a",
  "embedding_dim": 512,
  "n_taxa": 0,
  "temperature": 1.0,
  "fusion_mode": "embedding",
  "default_threshold": 0.70,
  "backbone": {
    "source": "imageomics/bioclip",
    "arch": "ViT-B/16",
    "input_size": 224,
    "quantisation": "int8-dynamic",
    "mean": [0.48145466, 0.4578275, 0.40821073],
    "std": [0.26862954, 0.26130258, 0.27577711]
  },
  "trained_at": "",
  "max_images": 5
}
```

`model_version` is stamped onto every saved `Identification` so old records stay interpretable
after a model swap (§4.3 of the build prompt). The app refuses to load assets whose
`spec_version` it does not implement — fail loudly at startup, not subtly at inference.
