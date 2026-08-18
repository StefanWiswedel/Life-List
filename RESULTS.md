# Results

Every number here says what it was measured on. A figure without its caveat is a claim, and
this file is not for claims.

---

## Run 1 — 18 Aug 2026, partial data, pipeline smoke test

**This is not an accuracy figure for the app.** It is a test that stages 1→4 connect, run, and
produce something sane. Read the caveats before quoting anything.

### What it ran on

| | |
|---|---|
| embeddings | 163,998 — **41 of 84 shards**, stage 3 still running |
| after bridging to GBIF | 160,102 rows over **2,294 leaves** (of 2,376 planned) |
| split | 128,042 train / 15,865 val / 16,195 test, by observation |
| taxa missing from train | 0 |
| head | linear probe on frozen BioCLIP, 25 epochs, 101 s on 2 CPU cores |

### Numbers

| split | T | rollup acc. | leaf top-1 | refused | mean depth | ECE |
|---|---|---|---|---|---|---|
| val | 1.00 | 96.1% | 84.4% | 2.5% | 5.51 | 0.123 |
| val | **0.62** | 94.6% | 84.4% | 0.6% | 6.33 | **0.018** |
| test | 1.00 | 96.2% | 84.2% | 2.5% | 5.51 | 0.124 |
| test | **0.62** | 94.3% | 84.2% | 0.8% | 6.32 | **0.019** |

Val and test agree to within 0.3 points on every metric, which is what a clean
observation-level split should look like.

### What is actually interesting here

**Calibration improved 6.5×.** ECE 0.124 → 0.019 on test. Given §18 — coarse fallback is not
unique to us, but *stated confidence* is — this is the metric the product rests on, and
temperature fitting is doing real work rather than hygiene.

**The fitted temperature is below 1.** T = 0.616 means the head was *under*-confident, which is
the opposite of the usual failure and the opposite of Arter's 80%-on-everything. Sharpening it
pushes answers **deeper** — mean depth 5.51 → 6.32, refusals 2.5% → 0.8% — for 1.9 points of
rollup accuracy.

That trade is the product's central dial, and it is worth naming rather than optimising away:
the uncalibrated model was buying accuracy by hedging. Answering "Anatidae" when you could
defend "*Anas crecca*" scores well and helps nobody.

**Leaf top-1 84.2% against rollup 94.3%** is the gap the rollup exists to fill: roughly one
identification in ten is one the model cannot pin to species but can place correctly higher up.

### Caveats, in order of how much they matter

1. **The 41 shards are not a random 41.** Shards are ordered by `photo_id`, which is roughly
   chronological iNaturalist upload order. Rarer taxa are systematically underrepresented, and
   they are the hard ones. **Expect the full-data numbers to be worse, not better.**
2. **2,294 leaves, not 2,376.** 52 taxa have no photo in this subset.
3. **No per-group breakdown yet.** Insects and fungi are where this will hurt, and a single
   aggregate hides that. Stage 5 owes a table by group.
4. **`max_photos_per_taxon` 150 caps the common species**, so the class balance here is not the
   balance of Danish nature. That is deliberate (§12) but it means accuracy is not weighted the
   way a user's experience will be.
5. Nothing has been quantised or run on a device. The 1.5 s budget in §4.1 is untested.

### Next

Re-run on all 84 shards when stage 3 finishes, add the per-group table, then hold out a
confusion matrix so the reference-photo set (§20) can be chosen from what actually gets confused.
