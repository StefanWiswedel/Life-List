# Results

Every number here says what it was measured on. A figure without its caveat is a claim, and
this file is not for claims.

---

## Run 3 — 22 Aug 2026, choosing the observation threshold

410,802 embeddings over all 3,627 iNaturalist taxa at ≥20 observations, split by observation,
fitted through the committed `lifelist-train` rather than a scratch script. Every row is a linear
probe on the same frozen BioCLIP embeddings, so the only thing that varies is how many taxa the
head is asked to separate.

**Read the `shared` columns down the page.** They score every candidate on the same test
photographs — those whose true taxon survives ≥50 — because a 3,482-class model and a
2,299-class model are not being asked the same questions, and their own-test numbers are not
comparable. The `own` columns are each model on its own harder problem.

| min obs | leaves | photos | T | own rollup | own top-1 | **shared rollup** | **shared top-1** | ECE |
|---|---|---|---|---|---|---|---|---|
| ≥50 | 2,299 | 330,945 | 0.737 | 94.7% | 85.4% | **94.7%** | **85.4%** | 0.018 |
| ≥40 | 2,549 | 351,014 | 0.736 | 94.5% | 84.6% | **94.6%** | **85.0%** | 0.020 |
| ≥30 | 2,931 | 375,171 | 0.745 | 93.9% | 83.7% | **94.4%** | **84.6%** | 0.017 |
| ≥20 | 3,482 | 400,753 | 0.759 | 93.5% | 81.7% | **94.4%** | **83.9%** | 0.018 |

The ≥50 row reproduces Run 2 (94.7% rollup, 85.6% → 85.4% top-1, ECE 0.017 → 0.018, T 0.731 →
0.737) from a different code path and a re-derived manifest, which is the check that the
committed builder is the thing that made the shipped model. It also reproduced to the digit
across three separate runs of this comparison.

### What the extra taxa cost

**Rollup accuracy stops falling at ≥30.** 94.7 → 94.6 → 94.4 → 94.4: the last 551 taxa are free
on the metric the product is actually judged on. Leaf top-1 keeps sliding (85.4 → 83.9), which is
the honest description of what is happening — the model is less often certain of the exact
species, and the rollup answers one rank up instead. That is the design working, not failing.

**Calibration does not degrade at all.** ECE is 0.018 at both ends. This was the number most at
risk, since stated confidence is the differentiator (§18), and a model with 50% more classes
having the same ECE is the strongest single argument for the lower threshold.

### Coverage bought, by group

| threshold | leaves | Lepidoptera | Geometridae | Noctuidae |
|---|---|---|---|---|
| ≥50 | 2,299 | 468 | 104 | 113 |
| ≥40 | 2,549 | 513 | 109 | 128 |
| ≥30 | 2,931 | 579 | 123 | 144 |
| ≥20 | 3,482 | **676** | **147** | **167** |

This is the table that matters for the failure Stefan actually hit (§40): a pug moth confidently
misidentified because the real species was not in the model at all. Moths are where the missing
classes are, and ≥20 adds 208 of them.

### By group at ≥20 — test split, calibrated

| group | n | taxa | rollup | leaf top-1 | ECE | depth | refused |
|---|---|---|---|---|---|---|---|
| Insects | 17,676 | 1,547 | 94.8% | 83.6% | 0.020 | 6.38 | 0.7% |
| Plants | 12,302 | 994 | 94.3% | 84.5% | 0.018 | 6.31 | 0.9% |
| Birds | 3,417 | 237 | 91.7% | **70.0%** | 0.006 | 5.85 | 0.6% |
| Fungi | 2,915 | 264 | 90.2% | 81.7% | 0.030 | 6.14 | 2.7% |
| Other | 1,385 | 123 | 89.2% | 77.8% | 0.043 | 5.43 | 2.5% |
| Arachnids | 1,317 | 109 | 90.4% | 71.0% | 0.017 | 5.78 | 1.3% |
| Molluscs | 909 | 77 | 92.4% | 75.8% | 0.012 | 5.85 | 0.7% |
| Mammals | 464 | 39 | 86.0% | 74.1% | 0.057 | 5.95 | 2.4% |
| Amphibians | 210 | 12 | 91.0% | 81.9% | 0.023 | 6.14 | 1.0% |

Birds keep the shape Run 2 found: high rollup, low top-1, and the best ECE in the table. The
model knows it is looking at a gull and says so honestly.

Mammals and "Other" carry the worst calibration (ECE 0.057 and 0.043) at the fewest examples,
which is the case for a per-group threshold that §41 left open and this run does not settle.

---

## Run 2 — 18 Aug 2026, all 84 shards

**This is the model that ships.** 333,700 embeddings, 260,487 train / 32,347 val / 32,939 test,
split by observation. 2,294 leaves. Linear probe on frozen BioCLIP, 205 s.

| split | T | rollup acc. | leaf top-1 | refused | mean depth | ECE |
|---|---|---|---|---|---|---|
| test | 1.000 | 96.3% | 85.6% | 1.5% | 6.00 | 0.065 |
| test | **0.731** | 94.7% | 85.6% | 0.8% | 6.38 | **0.017** |

### Two predictions in Run 1 were wrong

**"Expect the full-data numbers to be worse, not better."** They are better: rollup 94.3% → 94.7%,
leaf top-1 84.2% → 85.6%, ECE 0.019 → 0.017. The reasoning — that shards ordered by `photo_id`
under-represent rarer taxa, which are the hard ones — was sound, and the effect of doubling the
data per class simply outweighed it.

**"Insects and fungi are where this will hurt."** Insects are the *best* group in the set at 95.7%
rollup and 87.5% leaf top-1. A botanist's intuition about which groups are hard to identify turns
out not to predict which groups a model finds hard, because the model is limited by photographs
per taxon rather than by morphology.

Both were reasonable and both were guesses, which is what the per-group table is for.

### By group — test split, calibrated

| group | n | rollup | leaf top-1 | refused | depth | ECE |
|---|---|---|---|---|---|---|
| Insects | 14,304 | 95.7% | 87.5% | 0.5% | 6.51 | 0.019 |
| Plants | 10,557 | 95.3% | 87.5% | 0.7% | 6.44 | 0.018 |
| Molluscs | 803 | 93.9% | 81.6% | 0.2% | 6.06 | 0.009 |
| Fungi | 1,804 | 92.8% | 88.4% | 2.8% | 6.38 | 0.031 |
| Birds | 2,932 | 92.5% | **74.2%** | 0.5% | 6.02 | 0.007 |
| Arachnids | 1,054 | 92.5% | 79.5% | 0.5% | 6.13 | 0.014 |
| Mammals | 429 | 90.9% | 78.3% | 0.9% | 5.87 | 0.025 |
| Other | 901 | 89.8% | 81.1% | 2.8% | 5.70 | 0.045 |
| Amphibians | 155 | 89.0% | 78.7% | 1.9% | 6.15 | 0.050 |

**Birds are where the rollup earns its keep.** An 18-point gap between rollup accuracy and leaf
top-1 — the widest of any group. One bird in four cannot be pinned to species from a photograph,
and the app places it correctly at genus or family instead of guessing. Arter would have offered
a species name at 80%.

**Amphibians are the weakest group and the worst calibrated** — 89.0% rollup, ECE 0.050, on 155
test photos. §12 already noted Denmark has barely a dozen amphibian species; the constraint is
that there are few of them, photographed rarely, so there is little to learn from. Worth watching
rather than fixing: more epochs on 155 examples buys nothing.

**Fungi have the highest leaf top-1 (88.4%) and the second-highest refusal rate (2.8%).** When
the model can see a fungus it is confident, and when it cannot it says so. That is the intended
behaviour, visible in the numbers.

### Calibration

ECE 0.065 → **0.017** after temperature. Fitted T = 0.731, still below 1, so the head remains
under-confident and sharpening it pushes answers deeper: mean depth 6.00 → 6.38, refusals 1.5% →
0.8%, for 1.6 points of rollup accuracy.

T moved from 0.616 on partial data to 0.731 on full — more data, less under-confidence to correct,
which is the direction it should move.

Per-group ECE runs from 0.007 (birds) to 0.050 (amphibians). Since §18 established that stated
confidence is what competitors do not have, a group whose confidence means less than the others
is a real defect, not a rounding difference — and it is the small groups.

### Caveats

1. **No device measurement.** Everything here is fp32 on a server. §21 measured 165 ms/image
   pixels-to-logits on two throttled cores, but nothing has run on a Pixel.
2. **`max_photos_per_taxon` is 150**, so the class balance is not the balance of Danish nature.
   Deliberate (§12), but it means accuracy is not weighted the way a user's experience will be.
3. **2,294 leaves, not 2,376.** 53 taxa did not bridge to GBIF — §15 and §16 cover why, and 13 of
   them are genuine GBIF homonyms that refuse on purpose.
4. **No confusion matrix yet.** That is what picks the reference-photo set (§20), and it is the
   obvious next measurement.

---

## Run 1 — 18 Aug 2026, 41 of 84 shards

Superseded by Run 2. Kept because its two wrong predictions are more useful than its numbers:
rollup 94.3%, leaf top-1 84.2%, ECE 0.019 on 16,195 test photos, with "expect the full run to be
worse" and "insects and fungi are where this will hurt" attached to it. Neither held.
