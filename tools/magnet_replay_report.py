#!/usr/bin/env python3
"""Independent numeric report for the trimmed fixture (not a substitute for Kotlin tests)."""
import argparse
import csv
import math
from collections import deque


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def scale(a, k):
    return tuple(x * k for x in a)


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def norm(a):
    return math.sqrt(dot(a, a))


def rotate(v, w, dt):
    speed = norm(w)
    if speed < 1e-9 or dt <= 0:
        return v
    axis = scale(w, 1 / speed)
    a = -speed * dt
    x, y, z = axis
    vx, vy, vz = v
    cross = (y * vz - z * vy, z * vx - x * vz, x * vy - y * vx)
    return add(add(scale(v, math.cos(a)), scale(cross, math.sin(a))),
               scale(axis, dot(axis, v) * (1 - math.cos(a))))


def fit(points, validate=True):
    if len(points) < 30:
        return None
    span = tuple(max(p[i] for p in points) - min(p[i] for p in points) for i in range(3))
    if validate and min(span) < 40:
        return None
    a = [[0.0] * 5 for _ in range(4)]
    for p in points:
        row = [2 * x for x in p] + [1]
        for i in range(4):
            for j in range(4):
                a[i][j] += row[i] * row[j]
            a[i][4] += row[i] * dot(p, p)
    for i in range(4):
        pivot = max(range(i, 4), key=lambda j: abs(a[j][i]))
        a[i], a[pivot] = a[pivot], a[i]
        d = a[i][i]
        if abs(d) < 1e-8:
            return None
        for j in range(i, 5):
            a[i][j] /= d
        for k in range(4):
            if k != i:
                factor = a[k][i]
                for j in range(i, 5):
                    a[k][j] -= factor * a[i][j]
    h = tuple(a[i][4] for i in range(3))
    radius2 = a[3][4] + dot(h, h)
    if radius2 < 0:
        return None
    radius = math.sqrt(radius2)
    rms = math.sqrt(sum((norm(sub(p, h)) - radius) ** 2 for p in points) / len(points))
    if validate and (not 35 <= radius <= 65 or rms > 5):
        return None
    return h, rms, radius, span


def report(path, window_ms=150, close_threshold=22, open_threshold=22, economy=False):
    h = (67.0, -30.7, 11.9)
    direction = scale((62, -25, 1), 1 / norm((62, -25, 1)))
    mag, gyro, calibration = deque(), deque(), deque(maxlen=2000)
    hinge = ninety = closed_at = origin = None
    last_sample = last_fit = None
    did_close = did_open = opened_since_closed = False
    latest = -10**30
    events, transitions, scores, fits, attempts = [], [], [], [], []
    with open(path, newline="", encoding="utf-8") as f:
        origin = next(int(r[1]) for r in csv.reader(f, delimiter=";") if r[2] == "36")
    last_hinge = -1
    power = not economy
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.reader(f, delimiter=";"):
            t, kind = int(r[1]), r[2]
            if kind not in {"4", "14", "36"} or t < latest - 1_000_000_000:
                continue
            latest = max(t, latest)
            if kind == "36":
                if t <= last_hinge:
                    continue
                last_hinge = t
                a = float(r[4])
                if origin is None:
                    origin = t
                if hinge is not None and a == 0:
                    transitions.append((True, t))
                if hinge == 0 and a >= 90:
                    transitions.append((False, t))
                if a == 90:
                    ninety = t
                if a == 0 and hinge != 0:
                    closed_at = t
                if a == 0:
                    opened_since_closed = False
                if a >= 90:
                    did_open = False
                if a == 180:
                    did_close, opened_since_closed = False, True
                hinge = a
                if economy:
                    power = a <= 90
                    if not power:
                        mag.clear()
                        gyro.clear()
                continue
            if not power:
                continue
            v = tuple(map(float, r[4:7]))
            if kind == "4":
                if gyro and t <= gyro[-1][0]:
                    continue
                gyro.append((t, v))
                while gyro and gyro[0][0] < t - 500_000_000:
                    gyro.popleft()
                continue
            if mag and t <= mag[-1][0]:
                continue
            if mag and t - mag[-1][0] > 200_000_000:
                mag.clear()
                gyro.clear()
            if hinge == 180:
                if last_fit is None:
                    last_fit = t
                if (last_sample is None or t - last_sample >= 100_000_000) and (not calibration or norm(sub(v, calibration[-1])) >= 3):
                    last_sample = t
                    calibration.append(v)
                if t - last_fit >= 30_000_000_000:
                    last_fit = t
                    attempts.append(((t - origin) / 1e9, len(calibration), fit(calibration, validate=False)))
                    result = fit(calibration)
                    if result:
                        h = result[0]
                        fits.append(((t - origin) / 1e9, len(calibration), result))
                        mag.clear()
                        gyro.clear()
            mag.append((t, v))
            target = t - window_ms * 1_000_000
            while len(mag) > 1 and mag[1][0] <= target:
                mag.popleft()
            anchor, b = mag[0]
            if anchor > target:
                continue
            earth, time = sub(b, h), anchor
            samples = [(gt, g) for gt, g in gyro if anchor <= gt <= t]
            if samples:
                older = [g for gt, g in gyro if gt <= anchor]
                omega = older[-1] if older else samples[0][1]
                for gt, g in samples:
                    earth = rotate(earth, omega, (gt - time) / 1e9)
                    omega, time = g, gt
                earth = rotate(earth, omega, (t - time) / 1e9)
            score = dot(sub(sub(v, h), earth), direction)
            seconds = (t - origin) / 1e9
            scores.append((seconds, score, hinge))
            if score >= close_threshold and hinge is not None and hinge <= 90 and ninety is not None and 0 <= t - ninety <= 10_000_000_000 and not did_close and opened_since_closed:
                did_close, did_open, opened_since_closed, closed_at = True, False, False, t
                events.append((True, t, score))
            elif score <= -open_threshold and hinge == 0 and closed_at is not None and t - closed_at >= 700_000_000 and not did_open:
                did_open, did_close = True, False
                events.append((False, t, score))
    print(f"W={window_ms}ms close={close_threshold} open={open_threshold} economy={economy}")
    leads = []
    for close, t in transitions:
        if (t - origin) / 1e9 <= 9:
            continue
        matches = [(et, s) for ec, et, s in events if ec == close and abs(et - t) <= 1_500_000_000]
        event = min(matches) if matches else None
        lead = (t - event[0]) / 1e6 if event else None
        leads.append((close, lead))
        print(f"hinge={(t-origin)/1e9:.3f} {'closing' if close else 'opening'} leadMs={lead} score={event[1] if event else None}")
    false = [(c, (t-origin)/1e9, s) for c, t, s in events if not any(c == tc and abs(t-tt) <= 1_500_000_000 for tc, tt in transitions)]
    print("false events:", false)
    for lo, hi in [(28.2, 36.6), (43.6, 47.0)]:
        values = [(abs(s), t, s) for t, s, _ in scores if lo <= t <= hi]
        print(f"open rotation {lo}..{hi}: maxAbs/time/score={max(values) if values else None}")
    calm = [(abs(s), t, s) for t, s, _ in scores if all(abs(t-(ht-origin)/1e9) > 1.5 for _, ht in transitions)]
    print("outside transition windows maxAbs/time/score:", max(calm) if calm else None)
    print("calibration attempts (time, count, H/rms/R/span):", attempts)
    print("all collected open samples:", len(calibration), fit(calibration, validate=False))
    print("calibrations:", fits)
    print("events:", [(c, round((t-origin)/1e9, 4), round(s, 3)) for c,t,s in events])
    assert not false, false
    closes = [lead for close, lead in leads if close]
    assert closes[0] is None or closes[0] >= 0, closes
    assert all(lead is not None and lead >= 20 for lead in closes[1:]), closes
    opens = [lead for close, lead in leads if not close]
    assert sum(lead is not None and lead >= 100 for lead in opens) >= 3, opens
    assert all(lead is not None and lead >= 0 for lead in opens), opens


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture")
    parser.add_argument("--economy", action="store_true", help="Disable high-rate sensors at hinge 180")
    args = parser.parse_args()
    report(args.fixture, economy=args.economy)
