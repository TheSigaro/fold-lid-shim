#!/usr/bin/env python3
"""Keep raw magnetometer, hinge, interrupt and every fifth gyro event, in arrival order."""
import argparse
import csv


def trim(source, target):
    gyro = 0
    with open(source, newline="", encoding="utf-8") as src, open(target, "w", newline="", encoding="utf-8") as dst:
        writer = csv.writer(dst, delimiter=";", lineterminator="\n")
        for row in csv.reader(src, delimiter=";"):
            if len(row) < 4:
                continue
            if row[2] == "4":
                gyro += 1
                if (gyro - 1) % 5:
                    continue
            elif row[2] not in {"14", "36", "65579", "header", "state", "mark"}:
                continue
            writer.writerow(row)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("target")
    args = parser.parse_args()
    trim(args.source, args.target)
