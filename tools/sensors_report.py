#!/usr/bin/env python3
"""Text report for FoldLidShim sensors.csv. Requires Python 3.10+."""
import argparse
import bisect
import csv
import math
from collections import defaultdict
from dataclasses import dataclass


@dataclass
class Sample:
    time: int
    values: tuple[float, ...]


VECTOR_TYPES = {1: "accelerometer", 2: "magnetic_field", 4: "gyroscope",
                14: "magnetic_field_uncalibrated"}
SCALAR_TYPES = {5: "light", 6: "pressure", 8: "proximity"}


def channel(kind, name):
    if kind in VECTOR_TYPES:
        return VECTOR_TYPES[kind], True
    if kind in SCALAR_TYPES:
        return SCALAR_TYPES[kind], False
    lower = name.lower().replace("_", " ").replace(".", " ")
    if "light" in lower and "cct" in lower:
        return "light_cct", False
    if "light" in lower and "ir" in lower.split():
        return "light_ir", False
    return None


def magnitude(values):
    return math.sqrt(sum(v * v for v in values[:3])) if len(values) >= 3 else None


def load(path):
    sensors = defaultdict(list)
    anchors = []
    start = None
    with open(path, encoding="utf-8", newline="") as source:
        for row in csv.reader(source, delimiter=";"):
            if not row:
                continue
            if len(row) < 4:
                raise ValueError(f"incomplete CSV row: {row!r}")
            received, stamp = int(row[0]), int(row[1])
            start = received if start is None else start
            if row[2] == "header":
                start = received
            elif row[2] in ("state", "mark"):
                anchors.append((received, f"{row[2]} {row[3]}"))
            else:
                values = tuple(float(v) for v in row[4:])
                if not values or not all(math.isfinite(v) for v in values):
                    continue
                sensors[(int(row[2]), row[3])].append(Sample(stamp, values))
    for samples in sensors.values():
        samples.sort(key=lambda sample: sample.time)
    return sensors, anchors, start or 0


def add_anchors(sensors, anchors, threshold):
    spikes = []
    for (kind, name), samples in sensors.items():
        if kind == 36:
            previous = None
            for sample in samples:
                if sample.values[0] != previous:
                    anchors.append((sample.time, f"hinge_angle {sample.values[0]:g}"))
                    previous = sample.values[0]
        if kind in (1, 10):
            for sample in samples:
                norm = magnitude(sample.values)
                if norm is None:
                    continue
                strength = norm if kind == 10 else abs(norm - 9.81)
                if strength > threshold:
                    spikes.append((sample.time, f"acceleration spike {name}: {strength:.3f} m/s^2"))
    previous = None
    for time, label in sorted(spikes):
        if previous is None or time - previous >= 300_000_000:
            anchors.append((time, label))
            previous = time
    anchors.sort()


def extrema(label, values):
    low, high = min(values), max(values)
    print(f"  {label}: min={low:.3f} max={high:.3f} range={high - low:.3f}")


def report(sensors, anchors, start, window, step):
    print("Sensors (period uses SensorEvent.timestamp):")
    for (kind, name), samples in sorted(sensors.items()):
        period = ((samples[-1].time - samples[0].time) / (len(samples) - 1) / 1e6
                  if len(samples) > 1 else None)
        period_text = f"{period:.3f}" if period is not None else "n/a"
        print(f"  type={kind} name={name!r} events={len(samples)} mean_period_ms={period_text}")
    print("Magnetometer statistics:")
    for (kind, name), samples in sorted(sensors.items()):
        if kind not in (2, 14):
            continue
        vectors = [s.values[:3] for s in samples if len(s.values) >= 3]
        if not vectors:
            continue
        print(f"  type={kind} name={name!r} (uT, first three values)")
        extrema("magnitude", [magnitude(v) for v in vectors])
        for axis, label in enumerate("xyz"):
            extrema(label, [v[axis] for v in vectors])
    columns = []
    for (kind, name), samples in sorted(sensors.items()):
        info = channel(kind, name)
        if info is None:
            continue
        label, vector = info
        samples = [s for s in samples if not vector or len(s.values) >= 3]
        columns.append((f"{label}[{kind}:{name}]", vector, samples, [s.time for s in samples]))
    print("Anchors (seconds from header elapsedNs):")
    for time, label in anchors:
        print(f"  {(time - start) / 1e9:+.6f}s ns={time} {label}")
    for time, label in anchors:
        print(f"\nAnchor {(time - start) / 1e9:+.6f}s {label}")
        print("offset_ms\t" + "\t".join(c[0] for c in columns))
        for offset in range(-window, window + 1, step):
            values = []
            target = time + offset * 1_000_000
            for _, vector, samples, times in columns:
                index = bisect.bisect_right(times, target) - 1
                if index < 0:
                    values.append("n/a")
                else:
                    sample = samples[index]
                    value = magnitude(sample.values) if vector else sample.values[0]
                    values.append(f"{value:.3f}")
            print(f"{offset:+d}\t" + "\t".join(values))


def positive(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def threshold(value):
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise argparse.ArgumentTypeError("must be finite and non-negative")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", help="path to sensors.csv")
    parser.add_argument("--anchor-threshold", type=threshold, default=6.0,
                        help="acceleration spike threshold in m/s^2 (default: 6)")
    parser.add_argument("--window-ms", type=positive, default=1000,
                        help="anchor window radius in ms (default: 1000)")
    parser.add_argument("--step-ms", type=positive, default=50,
                        help="table step in ms (default: 50)")
    args = parser.parse_args()
    try:
        sensors, anchors, start = load(args.file)
        add_anchors(sensors, anchors, args.anchor_threshold)
        report(sensors, anchors, start, args.window_ms, args.step_ms)
    except (OSError, ValueError, csv.Error) as error:
        parser.exit(1, f"error: {error}\n")


if __name__ == "__main__":
    main()
