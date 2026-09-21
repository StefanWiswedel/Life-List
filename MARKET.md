# Does anyone pay for this? — 2026-09-21

Written because the idea was rejected for months on one untested sentence: *"there are
free alternatives which are pretty close, so no one will buy it."* That sentence is
checkable, and it is wrong.

All figures below are third-party estimates or vendor claims, marked as such. None are
audited. Where a check came back against the thesis it is recorded here too — three
proposals in the sibling `yt` project died from asserting a moat without looking for the
incumbent, and that is not happening again.

## The category pays, and the free alternatives do not stop it

**PictureThis**, plant ID, **$39.99/year**:

| | |
|---|---|
| monthly revenue, iOS | ~$5M |
| monthly revenue, combined | **~$8M** |
| monthly downloads, iOS | ~700K |
| App Store reviews | 1M+ at 4.6 |

Sources: [Sensor Tower](https://app.sensortower.com/overview/1252497129?country=US),
[onboarding teardown](https://tasu.ai/library/picturethis).

**PlantNet is free. Seek is free. iNaturalist is free.** PictureThis does $8M/month
anyway. Whatever else is true, "a free alternative exists" does not settle this market.

**Two caveats, both load-bearing.**

1. The teardown describes a *fake demo scan* and a **paywall that appears twice**, one of
   them before the user has even opened the camera; App Store reviews complain about
   trials auto-renewing into $39.99. An unknown share of that revenue is funnel, not
   product. This app's entire thesis is *honest confidence* — it is the anti-PictureThis,
   and it should be expected to monetise worse per install for exactly that reason.
2. **$5M of the $8M is iOS.** This app is Android-only Kotlin/Compose. The paying half of
   the market is the half it cannot reach.

## The four claimed differentiators, scored against what exists

### 1. Region-specific models — **already taken**

[Merlin Bird Packs](https://merlin.allaboutbirds.org/bird-packs/) are exactly this:
download a whole region for offline use. Cornell, free.

Novel only in that Merlin's packs are *birds*. All-taxa regional packs are not on offer
anywhere I found. But the mechanic is not new and cannot be sold as new.

**And this is the one that costs.** A region pack is not a download — it is training
data, a trained head, calibration, and validation, per region, forever. That is recurring
work, and it runs directly against the "passive, minimise administration" constraint that
has governed every decision in this project. It is the most expensive of the four by a
wide margin.

### 2. Rollup — stop at the rank the evidence supports — **the real one**

The closest anyone comes:

- **Arter** (Danish official portal) added *genus suggestions* recently. A suggestion is
  not a calibrated bound. Its recogniser is also trained on a **Norwegian photo archive**
  because no Danish one covers all species groups yet, and is retrained roughly **once a
  year** ([Arter knowledge base](https://om.arter.dk/vidensbase/hjaelp-til-arters-app/genkend-art/)).
- **Seek** reveals higher ranks progressively in its AR view, but as a UI affordance
  while it narrows down — it does not stop and say *the genus is as far as the evidence
  goes*, and it publishes no calibration.

Nobody ships ECE. This is the differentiator with the least competition and it is already
built and measured (ECE 0.018, 94.7% rollup accuracy at 2,299 leaves — `RESULTS.md`).

### 3. Life list central, species count per family — **partly taken, good hook**

Merlin's *Save My Bird* builds a life list; Fieldbook does nature journaling across 19
categories. Neither frames it as **completion against the family tree**. Per-family counts
turn identification into a collection with a denominator, which is a genuine completionist
mechanic and the thing most likely to produce retention. `FamilyProgress.kt` exists.

### 4. Audio and visual in one life list — **occupied, but thinly**

This was the strongest-looking claim and it does not survive cleanly:

- **Merlin** does photo + Sound ID + life list — **birds only**. Sound ID covers 2,066
  species with comprehensive Europe / Western Palearctic coverage.
- **Animal Sound Identifier: AI** claims sound *and* photo across birds, mammals,
  reptiles, amphibians and insects, saving your finds — but on its own numbers, **855
  bird songs from 285 species**, against BirdNET's thousands. Thin.
- **Fieldbook** (iPhone) combines AI ID, journaling and offline across 19 categories.

So: not an empty gap. A **quality** gap. The serious audio app is birds-only; the
all-taxa audio apps are shallow. That is defensible, but it is a claim about execution,
not about territory, and it has to be argued on the numbers every time.

## What the honest pitch is

Not "a better Seek". **"Merlin, but for everything — and it tells you when it doesn't
know."**

That sentence is supported by: an all-taxa calibrated model, BirdNET audio in the same
life list, per-family completion, and regional scoping. Three of the four exist in the
repo today.

## What would have to be true for it to earn

1. **iOS**, or accept roughly the smaller half of the paying market.
2. A monetisation shape that does not require dark patterns, because the product's whole
   claim is honesty. Most likely: free core, paid region packs — which has the nice
   property that the thing that costs recurring work is the thing that is charged for.
3. Region packs beyond Denmark, which is the recurring cost above.
4. Distribution. Still zero. The app has never been anywhere a stranger could find it.

## Open question, unresolved

Every route that earns real money here adds recurring work — region packs, iOS, support,
store presence. The constraint that has governed this project is *passive*. These two
cannot both hold. That trade has now appeared in the sideline hunt, in the wildlife-app
discussion and here, three times in three contexts, and it has not been decided.
