"""Stage 4's pure helpers — the parts that decide *which* photographs a candidate model sees.

The head that shipped came out of a scratch script, which is how `head.npz` joined the taxonomy
(§28) and the reference index (§37) as a committed artefact with no committed builder. These
tests cover the three judgements inside the builder that are easy to get subtly wrong and
impossible to notice afterwards: how observations are counted, which taxa clear a threshold, and
which test photographs may be compared across thresholds.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from lifelist_train.bridge import document
from lifelist_train.cli.train import common_mask, observation_counts, prepare, taxa_above

RECORDS = {
    9761484: {
        "scientific_name": "Anas platyrhynchos", "rank": "species", "status": "ACCEPTED",
        "lineage": {"class": 212, "family": 2986, "genus": 2498118},
        "lineage_names": {"class": "Aves", "family": "Anatidae", "genus": "Anas"},
        "vernacular_en": "Mallard", "vernacular_da": "Gråand",
    },
    8214667: {
        "scientific_name": "Anas crecca", "rank": "species", "status": "ACCEPTED",
        "lineage": {"class": 212, "family": 2986, "genus": 2498118},
        "lineage_names": {"class": "Aves", "family": "Anatidae", "genus": "Anas"},
        "vernacular_en": "Teal", "vernacular_da": None,
    },
}
BRIDGE = document({6930: 9761484, 6937: 8214667}, [], RECORDS)


def embedded(rows):
    return pd.DataFrame(
        [
            {
                "photo_id": p, "taxon_id": t, "observation_uuid": o,
                "embedding": np.zeros(4, dtype=np.float32),
            }
            for p, t, o in rows
        ]
    )


# -- counting evidence, not pixels ------------------------------------------

def test_ten_photos_of_one_beetle_are_one_observation():
    counts = observation_counts(embedded([(i, 6930, "obs-a") for i in range(10)]))

    assert counts == {6930: 1}


def test_observations_are_counted_from_the_embeddings_themselves():
    # Exact below the photo cap: `sample_photos` takes one photograph from every observation
    # before it takes a second from any, so the cap only bites above itself.
    frame = embedded([(1, 6930, "a"), (2, 6930, "a"), (3, 6930, "b"), (4, 6937, "c")])

    assert observation_counts(frame) == {6930: 2, 6937: 1}


def test_taxa_above_is_inclusive_and_sorted():
    assert taxa_above({6937: 20, 6930: 19, 5: 50}, 20) == [5, 6937]


# -- what a threshold actually selects --------------------------------------

def test_prepare_keeps_only_the_taxa_asked_for_and_labels_them_by_leaf():
    frame = embedded([(1, 6930, "a"), (2, 6937, "b")])
    taxonomy, rows = prepare(frame, BRIDGE, [6930])

    assert taxonomy.n_taxa == 1
    assert list(rows["photo_id"]) == [1]
    assert list(rows["leaf"]) == [0]


def test_a_photo_of_a_taxon_outside_the_bridge_is_dropped_not_mislabelled():
    frame = embedded([(1, 6930, "a"), (2, 999999, "b")])
    _, rows = prepare(frame, BRIDGE, [6930, 999999])

    assert list(rows["photo_id"]) == [1]


# -- comparing two models fairly --------------------------------------------

def test_the_shared_mask_keeps_photos_whose_true_taxon_is_in_every_vocabulary():
    # The only column that can be read down the table: each candidate is answering the same
    # questions about the same organisms, and its extra classes are just extra ways to be wrong.
    taxonomy, _ = prepare(embedded([(1, 6930, "a"), (2, 6937, "b")]), BRIDGE, [6930, 6937])
    labels = np.array([taxonomy.node(9761484).leaf_index, taxonomy.node(8214667).leaf_index])

    mask = common_mask(taxonomy, labels, {9761484})

    assert list(mask) == [True, False]


def test_an_empty_shared_vocabulary_masks_everything_rather_than_raising():
    taxonomy, _ = prepare(embedded([(1, 6930, "a")]), BRIDGE, [6930])

    assert not common_mask(taxonomy, np.array([0]), set()).any()
