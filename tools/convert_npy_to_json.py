import argparse
import json

import numpy as np


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inp", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    labels = np.load(args.inp, allow_pickle=True).tolist()

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(labels, fh, ensure_ascii=False, indent=2)

    print("Wrote labels JSON ->", args.out)


if __name__ == "__main__":
    main()

