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


def transport(v, start, end, gyro):
    samples = [(t, g) for t, g in gyro if start <= t <= end]
    if not samples:
        return v
    older = [g for t, g in gyro if t <= start]
    omega = older[-1] if older else samples[0][1]
    time = start
    for t, g in samples:
        v = rotate(v, omega, (t - time) / 1e9)
        omega, time = g, t
    return rotate(v, omega, (end - time) / 1e9)


PHANTOM_HOLD_NS = 500_000_000
QUIET_GYRO = 0.5
QUIET_OPEN_THRESHOLD = 35


def replay(path, threshold=None, filtered=True, safeguards=True, phantom_hold_ns=PHANTOM_HOLD_NS):
    window_ms = 150
    close_threshold = 20 if threshold is None else threshold; open_threshold = 18 if threshold is None else threshold
    h = (67.0, -30.7, 11.9)
    direction = scale((62, -25, 1), 1 / norm((62, -25, 1)))
    mag, gyro, calibration = deque(), deque(), deque(maxlen=2000)
    hinge = ninety = closed_at = origin = None
    last_sample = last_fit = None
    did_close = did_open = opened_since_closed = False
    latest = -10**30
    events, transitions, fits, attempts = [], [], [], []
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f, delimiter=";"))
    origin = next(int(r[1]) for r in rows if r[2] == "36")
    header = next(int(r[0]) for r in rows if r[2] == "header")
    features = []
    last_hinge = -1
    disturbed_until = phantom_until = -10**30
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.reader(f, delimiter=";"):
            t, kind = int(r[1]), r[2]
            if kind not in {"4", "14", "36"} or (t < latest - 1_000_000_000 and not (kind == "36" and hinge is None)):
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
                continue
            v = tuple(map(float, r[4:7]))
            if kind == "4":
                if gyro and t <= gyro[-1][0]:
                    continue
                gyro.append((t, v))
                while gyro and gyro[0][0] < t - 650_000_000:
                    gyro.popleft()
                continue
            if mag and t <= mag[-1][0]:
                continue
            if mag and t - mag[-1][0] > 200_000_000:
                mag.clear()
                gyro.clear()
            if norm(sub(v, h)) > 42 + 67 + 20:
                disturbed_until = t + 30_000_000_000
            if hinge == 180 and (not filtered or t >= disturbed_until):
                if last_fit is None:
                    last_fit = t
                if (last_sample is None or t - last_sample >= 100_000_000) and (not calibration or norm(sub(v, calibration[-1])) >= 3):
                    last_sample = t
                    calibration.append(v)
                if t - last_fit >= 30_000_000_000:
                    last_fit = t
                    attempts.append(((t - header) / 1e9, len(calibration), fit(calibration, validate=False)))
                    result = fit(calibration)
                    if result:
                        h = result[0]
                        fits.append(((t - header) / 1e9, len(calibration), result))
                        mag.clear()
                        gyro.clear()
            mag.append((t, v))
            target = t - window_ms * 1_000_000
            while len(mag) > 1 and mag[1][0] <= t - 650_000_000:
                mag.popleft()
            anchors = [(mt, b) for mt, b in mag if mt <= target]
            if not anchors:
                continue
            anchor, b = anchors[-1]
            earth = transport(sub(b, h), anchor, t, gyro)
            older = [(mt, b) for mt, b in mag if mt <= anchor - 300_000_000]
            pre_rise = None
            if older:
                ot, ob = older[-1]
                pre_rise = dot(sub(sub(b, h), transport(sub(ob, h), ot, anchor, gyro)), direction)
            delta = sub(sub(v, h), earth)
            score = dot(delta, direction)
            perpendicular = norm(sub(delta, scale(direction, score)))
            before, after = sub(b, h), sub(v, h)
            m0 = scale(direction, 67)
            radii = (norm(before), norm(sub(before, m0)), norm(after), norm(sub(after, m0)))
            plausible = perpendicular <= .65 * abs(score) and t >= disturbed_until
            close_gate = (hinge is not None and hinge <= 90 and ninety is not None
                          and 0 <= t - ninety <= 10_000_000_000 and opened_since_closed)
            open_gate = hinge == 0 and closed_at is not None and t - closed_at >= 700_000_000
            pre_ok = pre_rise is not None and pre_rise <= 10
            max_gyro = max((norm(g) for gt, g in gyro if target <= gt <= t), default=None)
            quiet = max_gyro is not None and max_gyro < QUIET_GYRO
            candidate_threshold = QUIET_OPEN_THRESHOLD if safeguards and quiet else open_threshold
            quiet_rejected = (open_gate and not did_open and pre_ok and plausible
                              and t >= phantom_until and score <= -open_threshold
                              and quiet and score > -QUIET_OPEN_THRESHOLD)
            phantom = open_gate and not did_open and score >= close_threshold
            if phantom:
                phantom_until = t + phantom_hold_ns
                if filtered and safeguards:
                    disturbed_until = max(disturbed_until, phantom_until)
            plausible = plausible and (not safeguards or t >= phantom_until)
            features.append(dict(pre_rise=pre_rise, phantom=phantom, max_gyro=max_gyro,
                                 open_threshold=candidate_threshold, quiet_rejected=quiet_rejected,
                                 recent_phantom=t < phantom_until, phantom_until=phantom_until,
                                 disturbed_until=disturbed_until, t=t, seconds=(t-header)/1e9, score=score, delta=delta,
                                 angle=math.degrees(math.atan2(perpendicular, abs(score))),
                                 perpendicular=perpendicular, radii=radii,
                                 projection=dot(after, direction), before_projection=dot(before, direction), hinge=hinge,
                                 plausible=plausible, directional=perpendicular <= .65 * abs(score),
                                 eligible=(score > 0 and close_gate) or (score < 0 and open_gate and (not safeguards or (pre_ok and (not quiet or score <= -QUIET_OPEN_THRESHOLD))))))
            if filtered and not plausible:
                continue
            if score >= close_threshold and close_gate and not did_close:
                did_close, did_open, opened_since_closed, closed_at = True, False, False, t
                events.append((True, t, score))
            elif score <= -(candidate_threshold if filtered else open_threshold) and open_gate and not did_open and (not filtered or not safeguards or pre_ok):
                did_open, did_close = True, False
                events.append((False, t, score))
    return origin, header, transitions, events, features, attempts, fits


def matched(events, closed, t):
    return next(((et, score) for c, et, score in events
                 if c == closed and abs(et - t) <= 1_500_000_000), None)


def maximum(features):
    f = max(features, key=lambda f: abs(f["score"]), default=None)
    return "none" if f is None else f"{abs(f['score']):.3f} at header+{f['seconds']:.3f}s (s={f['score']:.3f})"


def feature_row(label, f):
    values = [f"{f['seconds']:.3f}", f"{f['score']:.2f}",
              "(" + ",".join(f"{v:.2f}" for v in f["delta"]) + ")",
              f"{f['angle']:.2f}", f"{f['perpendicular']:.2f}"]
    values += [f"{v:.2f}" for v in f["radii"]]
    values += [f"{f['before_projection']:.2f} -> {f['projection']:.2f}"]
    values += ["missing" if f["pre_rise"] is None else f"{f['pre_rise']:.2f}",
               "missing" if f["max_gyro"] is None else f"{f['max_gyro']:.3f}",
               str(f["recent_phantom"])]
    print("| " + " | ".join([label] + values) + " |")


def report(path):
    origin, header, transitions, baseline, raw, attempts, fits = replay(path, 22, False, False)
    _, _, _, events, features, _, _ = replay(path)
    outside = lambda f: all(abs(f["t"] - t) > 1_500_000_000 for _, t in transitions)
    print("Time is relative to header.receivedNs; hinge origin offset=%.6fs" % ((origin-header)/1e9))
    print("W=150ms; T_close=20 T_open=18; k=0.65; R=42; m0=67; foreign field limit=129uT; quiet interval=30s")
    print(f"phantom hold={PHANTOM_HOLD_NS / 1e9:g}s; no opening latch; pre-window rise <=10uT; quiet gyro <0.5rad/s uses opening=35uT")
    print("Recordings have no screen broadcasts; sensors are replayed continuously.")
    print("Baseline v11 events:", [(c, round((t-header)/1e9, 4), round(s, 3)) for c,t,s in baseline])
    print("Features at baseline v11 trigger, otherwise max |s| in 600ms before hinge:")
    print("| event | header+s | s | delta | angle to signed M (deg) | perpendicular | before B-H | before B-H-m0 | after B-H | after B-H-m0 | projection before -> after | pre-window rise | max gyro (rad/s) | recent phantom |")
    print("| " + " | ".join(["---"] * 14) + " |")
    for c, t in transitions:
        match = matched(baseline, c, t)
        f = next((f for f in raw if match and f["t"] == match[0]), None)
        if f is None:
            f = max((f for f in raw if t-600_000_000 <= f["t"] <= t),
                    key=lambda f: abs(f["score"]), default=None)
        if f:
            feature_row(("closing" if c else "opening") + f" hinge+{(t-header)/1e9:.3f}", f)
    for c, t, _ in baseline:
        if not any(c == tc and abs(t-tt) <= 1_500_000_000 for tc, tt in transitions):
            feature_row("v11 false " + ("closing" if c else "opening"),
                        next(f for f in raw if f["t"] == t))
    if not transitions:
        with open(path, newline="", encoding="utf-8") as recording:
            markers = [(int(r[1])-header)/1e9 for r in csv.reader(recording, delimiter=";")
                       if r[2] == "65579"]
        print("interrupt_gyro movement markers (header+s):", [round(t,3) for t in markers])
    print("Strict sphere filter (+/-20) is diagnostic only; it rejects real events.")
    for closed, t, score in baseline:
        f = next(f for f in raw if f["t"] == t)
        r = f["radii"]
        before, after = (r[0], r[3]) if closed else (r[1], r[2])
        print(f"  sphere error at {'closing' if closed else 'opening'} +{f['seconds']:.3f}: "
              f"before={before-42:+.2f} after={after-42:+.2f} uT")
    print("Threshold sweep (no preview watchdog/cooldown masks later candidates):")
    failures = []
    for threshold in (18,20,22):
        _, _, _, current, fs, _, _ = replay(path, threshold)
        false = [(c, (t-header)/1e9, s) for c,t,s in current
                 if not any(c == tc and abs(t-tt) <= 1_500_000_000 for tc,tt in transitions)]
        print(f"T={threshold}: events={len(current)} false={false}")
        print("Opening gyro and applied threshold:",
              [(round(f["seconds"], 6), round(f["max_gyro"], 3) if f["max_gyro"] is not None else None,
                f["open_threshold"]) for f in fs if any(not c and t == f["t"] for c,t,_ in current)])
        previous_run = replay(path, threshold, safeguards=False)
        previous = previous_run[3]
        print("v12 trigger features:")
        for c, t, _ in previous:
            feature_row(f"v12 T={threshold} " + ("closing" if c else "opening"),
                        next(f for f in previous_run[4] if f["t"] == t))
        for c,t in transitions:
            old, new = matched(previous,c,t), matched(current,c,t)
            lead = lambda e: round((t-e[0])/1e6, 3) if e else None
            print(f"  hinge+{(t-header)/1e9:.3f} {'closing' if c else 'opening'} leadMs old={lead(old)} new={lead(new)}")
            if old:
                if new is None or t-new[0] < t-old[0]-30_000_000:
                    failures.append(f"T={threshold}: missing or delayed transition at {(t-header)/1e9:.6f}")
        if false:
            failures.append(f"T={threshold}: false events {false}")
        expected = sum(any(c == tc and abs(t-tt) <= 1_500_000_000 for tc, tt in transitions)
                       for c, t, _ in previous)
        if len(current) != expected:
            failures.append(f"T={threshold}: expected {expected} events, got {len(current)}")
    phantoms = [f for f in features if f["phantom"]]
    episodes = []
    for f in phantoms:
        if not episodes or f["t"] - episodes[-1][-1]["t"] > 200_000_000:
            episodes.append([])
        episodes[-1].append(f)
    print("Phantom episodes (first, last, phantom until, combined quarantine until header+s):",
          [(round(e[0]["seconds"], 6), round(e[-1]["seconds"], 6),
            round((e[-1]["phantom_until"]-header)/1e9, 6),
            round((e[-1]["disturbed_until"]-header)/1e9, 6)) for e in episodes])
    print("Quiet rejected candidates (header+s, s, max gyro):",
          [(round(f["seconds"], 6), round(f["score"], 3), round(f["max_gyro"], 3))
           for f in features if f["quiet_rejected"]])
    print("events:", [(c, round((t-header)/1e9,4), round(s,3)) for c,t,s in events])
    print("outside transition windows raw max |s|:", maximum(f for f in features if outside(f)))
    print("outside after direction + foreign-field filter, before hinge gates:",
          maximum(f for f in features if outside(f) and f["plausible"]))
    valid = [f for f in features if outside(f) and f["plausible"] and f["eligible"]]
    print("outside after filters AND hinge gates (without didOpen/didClose):", maximum(valid))
    # Preserve the 5 uT margin where v12 had it. Other recordings must not worsen
    # their subthreshold residual; actual false events are checked separately above.
    prior_features = replay(path, safeguards=False)[4]
    prior_residual = max((abs(f["score"]) for f in prior_features
                          if outside(f) and f["plausible"] and f["eligible"] and abs(f["score"]) < 18),
                         default=0.0)
    residual_limit = max(18-5, prior_residual)
    print(f"Outside-transition residual limit={residual_limit:.3f}uT (v12 subthreshold residual={prior_residual:.3f})")
    if any(abs(f["score"]) > residual_limit + 1e-9 for f in valid):
        failures.append("Outside-transition residual worsened beyond v12 or the 5 uT margin")
    print("calibration attempts (header+s, count, H/rms/R/span):", attempts)
    print("calibrations:", fits)
    assert not failures, "\n".join(failures)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture")
    parser.add_argument("--economy", action="store_true",
                        help="Assume an interactive screen; fixtures contain no screen broadcasts")
    args = parser.parse_args()
    if args.economy:
        print("Screen economy: interactive screen assumed throughout this recording.")
    report(args.fixture)
