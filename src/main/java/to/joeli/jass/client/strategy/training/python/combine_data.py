"""
Pools all CBOR data from all episodes into a single train/test/val split
written to episode 0000. Run from the python/ directory.

Usage: python3 combine_data.py
"""
import os
import random
import shutil
from pathlib import Path

from util import base_path, zero_pad

SEED = 42
TRAIN_FRAC = 0.80
TEST_FRAC  = 0.10
# val gets the remainder

NETWORK_TYPES = ['cards/', 'score/']
SPLITS = ['train', 'test', 'val']


def collect_pairs(net):
    """Return list of (feature_path, target_path) across all episodes and splits."""
    episodes_dir = Path(base_path()) / 'episodes'
    pairs = []
    for ep_dir in sorted(episodes_dir.iterdir()):
        if not ep_dir.is_dir():
            continue
        if ep_dir.name == '0000':
            continue  # skip output destination to avoid reading files we're about to overwrite
        for split in SPLITS:
            feat_dir = ep_dir / net / split / 'features'
            tgt_dir  = ep_dir / net / split / 'targets'
            if not feat_dir.exists():
                continue
            for feat_file in sorted(feat_dir.glob('*.cbor')):
                tgt_file = tgt_dir / feat_file.name
                if tgt_file.exists():
                    pairs.append((feat_file, tgt_file))
                else:
                    print(f'WARNING: no matching target for {feat_file}')
    return pairs


def write_split(pairs, net, split_name, start_index):
    ep0 = Path(base_path()) / 'episodes' / '0000' / net
    feat_out = ep0 / split_name / 'features'
    tgt_out  = ep0 / split_name / 'targets'
    feat_out.mkdir(parents=True, exist_ok=True)
    tgt_out.mkdir(parents=True, exist_ok=True)

    # Clear existing files so we start clean
    for f in feat_out.glob('*.cbor'):
        f.unlink()
    for f in tgt_out.glob('*.cbor'):
        f.unlink()

    for i, (feat_src, tgt_src) in enumerate(pairs, start=start_index):
        name = f'{i:04d}.cbor'
        shutil.copy2(feat_src, feat_out / name)
        shutil.copy2(tgt_src,  tgt_out  / name)

    return len(pairs)


def combine(net):
    pairs = collect_pairs(net)
    random.seed(SEED)
    random.shuffle(pairs)

    n = len(pairs)
    n_train = round(n * TRAIN_FRAC)
    n_test  = round(n * TEST_FRAC)
    n_val   = n - n_train - n_test

    train_pairs = pairs[:n_train]
    test_pairs  = pairs[n_train:n_train + n_test]
    val_pairs   = pairs[n_train + n_test:]

    print(f'{net}: {n} total → train={n_train}, test={n_test}, val={n_val}')

    write_split(train_pairs, net, 'train', 1)
    write_split(test_pairs,  net, 'test',  1)
    write_split(val_pairs,   net, 'val',   1)


if __name__ == '__main__':
    for net in NETWORK_TYPES:
        combine(net)
    print('Done. Run: python3 train.py 0000 cards/')
