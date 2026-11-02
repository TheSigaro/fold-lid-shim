#!/usr/bin/env python3
"""Correlate a Hall capture with firmware logs using standard-library replay."""
import argparse
import bisect
import csv
from decimal import Decimal
from collections import Counter
import statistics
import re
import sys

# Do not leave bytecode alongside the unchanged replay tool.
sys.dont_write_bytecode = True
import magnet_replay_report as replay

NS = 1_000_000_000
NAMES = {0: "CLOSED", 1: "TENT", 2: "HALF_OPENED", 3: "OPENED"}


def epoch_ns(value):
    return int(Decimal(value) * NS)


def table(headers, rows):
    escape = lambda value: str(value).replace("|", "\\|")
    print("| " + " | ".join(map(escape, headers)) + " |")
    print("| " + " | ".join(["---"] * len(headers)) + " |")
    for row in rows:
        print("| " + " | ".join(map(escape, row)) + " |")
    print()


def stats(values):
    if not values:
        return "нет"
    return " / ".join(f"{x:.3f}" for x in
                      (min(values), statistics.median(values), max(values)))


def bracket(samples, t):
    i = bisect.bisect_right([s[0] for s in samples], t)
    return (samples[i-1] if i else None,
            samples[i] if i < len(samples) else None)


def nearest(samples, t, limit=None):
    before, after = bracket(samples, t)
    s = min((x for x in (before, after) if x is not None),
            key=lambda x: abs(x[0]-t), default=None)
    return s if s and (limit is None or abs(s[0]-t) <= limit) else None


def cell(sample, t):
    if sample is None:
        return "нет"
    return f"{sample[1]} ({(sample[0]-t)/1e6:+.3f})"


def load_csv(path):
    samples = {"2": [], "14": [], "36": []}
    states, counts = [], Counter()
    header = end = None
    with open(path, encoding="utf-8", newline="") as stream:
        for r in csv.reader(stream, delimiter=";"):
            counts[r[2]] += 1
            end = max(end or 0, int(r[0]))
            if r[2] == "header":
                header = int(r[0])
            elif r[2] == "state":
                states.append((int(r[0]), int(r[3])))
            elif r[2] in samples:
                v = float(r[4]) if r[2] == "36" else replay.norm(tuple(map(float, r[4:7])))
                samples[r[2]].append((int(r[1]), v, int(r[0])))
    for series in samples.values():
        series.sort()
    states.sort()
    if header is None or not states:
        raise ValueError("CSV must contain a header and state callbacks")
    return header, end, states, samples, counts


def load_log(path):
    fusion, hinge, inputs, policies, updates, wakes, offsets = [], [], [], [], [], [], []
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            m = re.match(r"\s*(\d+\.\d+)", line)
            if not m:
                continue
            epoch = epoch_ns(m[1])
            m = re.search(r"lid_angle_fusion ts=(\d+) ns value \[\s*(-?\d+)/\s*(-?\d+)/\s*(-?\d+)\]", line)
            if m:
                t, a, b, c = map(int, m.groups())
                fusion.append((t, b, a, c))
                offsets.append(epoch-t)
            m = re.search(r"hinge_angle ts=(\d+) ns value\s*(-?\d+)/\s*(-?\d+)/\s*(-?\d+)", line)
            if m:
                t, coarse, angle, flag = map(int, m.groups())
                hinge.append((t, coarse, angle, flag))
                offsets.append(epoch-t)
            m = re.search(r"state changed, angle = ([\d.]+), state = (\d+)", line)
            if m:
                inputs.append((epoch, float(m[1]), int(m[2])))
            m = re.search(r"newState=(-?\d+), lastState=(-?\d+)", line)
            if m:
                policies.append((epoch, int(m[1]), int(m[2])))
            if "updateFoldingSensorListenerLw," in line and "notifyLidSwitchChanged" in line:
                updates.append((epoch, "true" in line.split("foldOpen=")[1].split(",")[0]))
            if "wakeAndUnFoldedFromLidSwitch triggered" in line:
                wakes.append((epoch, "wake"))
    if not offsets:
        raise ValueError("No timestamped sensor lines in log")
    offset = min(offsets)
    convert = lambda series: sorted((t-offset, *v) for t, *v in series)
    return (offset, offsets, sorted(fusion), sorted(hinge), convert(inputs),
            convert(policies), convert(updates), convert(wakes))


def match_events(truth, events):
    # One-to-one nearest assignment, with a fixed 1.5 s acceptance window.
    edges = sorted((abs(et-t), i, j) for i, (closed, t) in enumerate(truth)
                   for j, (ec, et, *_) in enumerate(events)
                   if ec == closed and abs(et-t) <= 1_500_000_000)
    matched, used = {}, set()
    for _, i, j in edges:
        if i not in matched and j not in used:
            matched[i] = events[j]
            used.add(j)
    return matched, [e for j, e in enumerate(events) if j not in used]


def policy_match(policies, t, prev, state):
    if prev is None:
        return None
    # The firmware can announce policy state 1 while the public callback is 2.
    if prev == 0 and state != 0:
        candidates = [p for p in policies if p[2] == 0 and p[1] in (1, 2)]
    else:
        candidates = [p for p in policies if p[1] == state and p[2] == prev and p[1] != p[2]]
        if not nearest(candidates, t, 1_500_000_000):
            candidates = [p for p in policies if p[1] == state]
    return nearest(candidates, t, 1_500_000_000)


def run(csv_path, log_path):
    header, end, states, sensors, counts = load_csv(csv_path)
    offset, offsets, fusion, log_hinge, inputs, policies, updates, wakes = load_log(log_path)
    baseline = replay.replay(csv_path)
    features = sorted((f["t"], f) for f in baseline[4])
    current = baseline[3]
    transitions = [(t, states[i-1][1] if i else None, state)
                   for i, (t, state) in enumerate(states)]
    truth = [(state == 0, t) for t, prev, state in transitions
             if prev is not None and (state == 0 or prev == 0) and state != prev]
    hinge_events = []
    previous = None
    for t, value, received in sensors["36"]:
        closed = value <= 10 if previous is None or not previous else value < 15
        if previous is not None and closed != previous:
            hinge_events.append((closed, t, received))
        previous = closed
    hinge_received = [(c, received, t) for c, t, received in hinge_events]
    mag_received = {t: received for t, _, received in sensors["14"]}
    current_received = [(c, mag_received[t], score) for c, t, score in current]
    hm, hf = match_events(truth, hinge_events)
    hrm, hrf = match_events(truth, hinge_received)
    mm, mf = match_events(truth, current)
    mrm, mrf = match_events(truth, current_received)

    print("# Сопоставление живого Холла, угла и магнитометра")
    print()
    print("## Метод и часы")
    print()
    print(f"CSV: {sum(counts.values())} строк, {len(states)} callback состояния; "
          f"длительность по receivedNs {((end-header)/NS):.3f} с, а не заявленные 90 с. "
          f"Закрытий {sum(c for c, _ in truth)}, раскрытий {sum(not c for c, _ in truth)}. "
          "Первая строка 0 - начальное состояние, не измеренный переход Холла.")
    print(f"Отсчёты: hinge {counts['36']}, магнитометр type 2 {counts['2']}, "
          f"type 14 {counts['14']}; fusion {len(fusion)}, hinge в логе {len(log_hinge)}. "
          f"Средняя частота магнитометра по длительности {counts['14']/((end-header)/NS):.2f} Гц.")
    print(f"epoch = boottime + {Decimal(offset)/NS:.9f} с. Минимум epoch строки минус ts "
          f"по {len(offsets)} событиям, верхняя оценка смещения. "
          f"Разброс этих разностей сверх минимума (мс, min/median/max): "
          f"{stats([(x-offset)/1e6 for x in offsets])}. "
          f"Отличие от 1793617468.379 с: {(offset-epoch_ns('1793617468.379'))/1e6:+.6f} мс.")
    pairs = {(t, int(v)) for t, v, _ in sensors['36']}
    print(f"Точное совпадение timestamp и значения hinge CSV/лог: "
          f"{sum((t, v) in pairs for t, v, *_ in log_hinge)}/{len(log_hinge)}. "
          "Epoch разбирается десятичной арифметикой до наносекунд. "
          "Неизвестная минимальная задержка логирования остаётся систематической погрешностью.")
    print("Все t в таблицах - секунды от header.receivedNs; Δ в мс = время сигнала минус "
          "время callback CSV. Плюс означает позже, минус означает раньше. "
          "Сенсорные времена - eventTimestampNs; callback состояния - receivedNs. "
          "Для решения приложения отдельно приведено время доставки. "
          "Магнитные значения и score - последние доступные по времени измерения, без интерполяции.")
    print("Реплей импортирован из tools/magnet_replay_report.py без изменений. "
          "Он использует type 14 и гироскоп type 4; type 2 приведён как отдельное измерение. "
          "score - проекция изменения B-H за 150 мс с компенсацией вращения на направление "
          "(62,-25,1).unit(), в мкТл. Модуль B сам по себе не является порогом решения. "
          "По умолчанию C/O=20/18, quiet-open=35 при gyro<0.5, pre-rise<=10, "
          "perpendicular<=0.65|score|, opening-hold=700 мс, phantom-hold=500 мс, "
          "foreign-field>129 и hold=10 с. Успешных калибровок: " + str(len(baseline[6])) + ".")
    print(f"Начальная hard-iron H=(67,-30.7,11.9), сохранённая калибровка телефона неизвестна. "
          f"Максимум |B14-H|={max(f['field_norm'] for f in baseline[4]):.3f} мкТл, "
          "ниже 129: foreign-field hold в этой записи не активируется, "
          "поэтому его уменьшение само по себе не улучшает результат.")
    print("Реплей сохраняет порядок CSV, защитные условия и временные метки событий. "
          "Это решения детектора и простого hinge-правила 10/15, а не полная эмуляция службы: "
          "экранные broadcasts, preview watchdog, rollback, bounce, binder и пользовательские "
          "настройки не записаны. Ранний порог 45 градусов не добавляет разрешения датчику 0/90/180.")
    print()

    print("## Все callback состояния")
    print()
    print("В ячейках сенсоров: значение (Δ мс). Input: угол/state (Δ). "
          "B2/B14/score: мкТл (возраст последнего отсчёта, Δ). "
          "P: строка политики (lastState -> newState, Δ) в пределах 1.5 с; "
          "для раскрытий ищется выход политики из 0, который может дать 1 при callback=2. Для угловых переходов сначала ищется тот же from/to, затем тот же newState, включая повторное решение. U: ближайший lid-switch update с нужным "
          "foldOpen в пределах 1.5 с, W: ближайший wake в пределах 1.5 с, "
          "только для переходов Холла. Соседние fusion могут принадлежать другому циклу: "
          "датчик отключается в закрытом состоянии.")
    rows, policy_for = [], {}
    for number, (t, prev, state) in enumerate(transitions, 1):
        fb, fa = bracket(fusion, t)
        hb, ha = bracket(sensors['36'], t)
        inp = nearest(inputs, t, 500_000_000)
        b2, _ = bracket(sensors['2'], t)
        b14, _ = bracket(sensors['14'], t)
        feature, _ = bracket(features, t)
        p = policy_match(policies, t, prev, state)
        policy_for[t] = p
        hall = prev is not None and state != prev and (state == 0 or prev == 0)
        u = nearest([u for u in updates if u[1] == (state != 0)], t, 1_500_000_000) if hall else None
        w = nearest(wakes, t, 1_500_000_000) if hall else None
        magnetic = lambda sample: "нет" if sample is None else f"{sample[1]:.2f} ({(sample[0]-t)/1e6:+.3f})"
        rows.append([number, f"{(t-header)/NS:.6f}", f"{'?' if prev is None else prev} -> {state}",
                     cell(fb, t), cell(fa, t),
                     cell((inp[0], f"{inp[1]:g}/{inp[2]}") if inp else None, t),
                     cell(hb, t), cell(ha, t), magnetic(b2), magnetic(b14),
                     magnetic((feature[0], feature[1]['score']) if feature else None),
                     "нет" if u is None else f"{(u[0]-t)/1e6:+.3f}",
                     "нет" if w is None else f"{(w[0]-t)/1e6:+.3f}",
                     "нет" if p is None else f"{p[2]} -> {p[1]} ({(p[0]-t)/1e6:+.3f})"])
    table(["№", "t, с", "from -> to", "fusion до", "fusion после", "Input угол/state", "hinge до", "hinge после", "B2", "B14", "score", "U Δ", "W Δ", "P Δ"], rows)

    print("## Холл: углы и границы наблюдения")
    print()
    hall_rows, angles, gaps = [], {True: [], False: []}, {True: [], False: []}
    for i, (closed, t) in enumerate(truth):
        fb, fa = bracket(fusion, t)
        f = fb if closed else fa
        if f:
            angles[closed].append(f[1]); gaps[closed].append(abs(f[0]-t)/1e6)
        h = hm.get(i)
        hall_rows.append(["закрытие" if closed else "раскрытие", f"{(t-header)/NS:.6f}",
                          f[1] if f else "нет", f"{abs(f[0]-t)/1e6:.3f}" if f else "нет",
                          "нет" if h is None else f"{(h[1]-t)/1e6:+.3f}"])
    table(["направление", "t Холла", "fusion до закрытия / после раскрытия", "интервал до/после, мс", "hinge 0/90 Δ, мс"], hall_rows)
    table(["направление", "угол min/median/max, град", "интервал fusion min/median/max, мс"],
          [["закрытие" if c else "раскрытие", stats(angles[c]), stats(gaps[c])] for c in (True, False)])
    print("Угол относительно строки решения политики (P), до доставки callback CSV:")
    policy_angle_rows, policy_angles = [], {True: [], False: []}
    for c, t in truth:
        p = policy_for[t]
        fb, fa = bracket(fusion, p[0]) if p else (None, None)
        f = fb if c else fa
        if f:
            policy_angles[c].append(f[1])
        policy_angle_rows.append(['закрытие' if c else 'раскрытие', f'{(t-header)/NS:.6f}',
                                  f[1] if f else 'нет', f'{(f[0]-p[0])/1e6:+.3f}' if f else 'нет',
                                  f'{(t-p[0])/1e6:.3f}' if p else 'нет'])
    table(['направление', 'CSV t', 'fusion до/после P, град', 'fusion - P, мс', 'CSV - P, мс'], policy_angle_rows)
    table(['направление', 'угол относительно P min/median/max, град'],
          [['закрытие' if c else 'раскрытие', stats(policy_angles[c])] for c in (True, False)])
    print("Первый fusion после физического раскрытия может быть ДО callback CSV. "
          "Поэтому таблица относительно P лучше ограничивает угол открытия; "
          "сама P тоже записана после физического события и не заменяет SW_LID timestamp.")
    print("Это ближайшие измеренные углы, не точные углы порога Холла. "
          "Последний fusion перед закрытием даёт верхнюю границу угла при монотонном движении; "
          "первый после раскрытия также обычно выше угла срабатывания. "
          "Задержки отключения/регистрации fusion и callback не позволяют восстановить "
          "угол Холла интерполяцией. Особенно нельзя принять большой первый угол после "
          "быстрого раскрытия за порог Холла.")
    print()

    print("## Реплей текущих порогов")
    print()
    print("Сопоставление событий с Холлом взаимно однозначное, одинаковое направление, "
          "окно +/-1.5 с, пары выбираются по минимальному расстоянию. "
          "Пропуски и лишние события учитываются отдельно, поэтому статистика задержек "
          "только для найденных пар не означает полное покрытие. "
          "Время получения магнитного события - receivedNs его запускающего отсчёта; "
          "это нижняя оценка времени решения без очереди службы.")
    event_rows = []
    for i, (c, t) in enumerate(truth):
        def event_cell(mapping):
            e = mapping.get(i)
            return "пропуск" if e is None else f"{(e[1]-header)/NS:.6f} ({(e[1]-t)/1e6:+.3f})"
        event_rows.append(["закрытие" if c else "раскрытие", f"{(t-header)/NS:.6f}",
                           event_cell(hm), event_cell(hrm), event_cell(mm), event_cell(mrm),
                           "нет" if i not in mm else f"{mm[i][2]:.3f}"])
    table(["направление", "Холл t", "hinge t (Δ)", "hinge received (Δ)", "магнит t (Δ)", "магнит received (Δ)", "score события"], event_rows)
    summary_rows = []
    for label, mapping, extra in [('hinge event', hm, hf), ('hinge received', hrm, hrf),
                                  ('магнит event', mm, mf), ('магнит received', mrm, mrf)]:
        for c in (True, False):
            delays = [(mapping[i][1]-t)/1e6 for i, (closed, t) in enumerate(truth) if c == closed and i in mapping]
            summary_rows.append([label, "закрытие" if c else "раскрытие", len(delays),
                                 sum(closed == c for closed, _ in truth)-len(delays),
                                 sum(e[0] == c for e in extra), stats(delays)])
    table(["сигнал", "направление", "найдено", "пропуск", "лишних", "Δ min/median/max, мс"], summary_rows)
    for label, extra in [('hinge event', hf), ('hinge received', hrf), ('магнит event', mf), ('магнит received', mrf)]:
        if extra:
            print(label + ": лишние события " + ", ".join(f"{'C' if c else 'O'}@{(t-header)/NS:.6f}" for c, t, *_ in extra) + ".")
    print()

    print("Магнитные значения в моменты Холла (последний отсчёт, мкТл):")
    magnetic_rows = []
    for c in (True, False):
        ts = [t for closed, t in truth if closed == c]
        b2s = [bracket(sensors['2'], t)[0][1] for t in ts if bracket(sensors['2'], t)[0]]
        b14s = [bracket(sensors['14'], t)[0][1] for t in ts if bracket(sensors['14'], t)[0]]
        scores = [bracket(features, t)[0][1]['score'] for t in ts if bracket(features, t)[0]]
        magnetic_rows.append(['закрытие' if c else 'раскрытие', stats(b2s), stats(b14s), stats(scores)])
    table(['направление', '|B2| min/median/max', '|B14| min/median/max', 'score min/median/max'], magnetic_rows)
    print("Диагностика пропусков магнитного реплея: лучший score нужного знака в окне "
          "+/-600 мс от Холла. Причины взяты из существующего реплея, "
          "они относятся к выбранному отсчёту, а не ко всему окну.")
    diagnostic_rows = []
    for i, (c, t) in enumerate(truth):
        if i in mm:
            continue
        candidates = [f for ft, f in features if abs(ft-t) <= 600_000_000 and (f['score'] > 0 if c else f['score'] < 0)]
        f = max(candidates, key=lambda f: abs(f['score']), default=None)
        diagnostic_rows.append(['закрытие' if c else 'раскрытие', f'{(t-header)/NS:.6f}',
                                f"{(f['t']-t)/1e6:+.3f}" if f else 'нет',
                                f"{f['score']:.3f}" if f else 'нет',
                                replay.rejection_reasons(f, c, 20 if c else 18, header) if f else 'нет отсчётов'])
    table(['направление', 'Холл t', 'score t - Холл, мс', 'score', 'блокировки'], diagnostic_rows)
    print("В первых трёх медленных закрытиях hinge0 приходит раньше Холла и сбрасывает "
          "openedSinceClosed: большой score после hinge0 уже не может запустить closing. "
          "Перед закрытием 44.883395 с телефон не достигал hinge180 после предыдущего "
          "раскрытия, поэтому closing запрещён даже при большом score. "
          "Понижение порога помогает успеть ДО hinge0, но не снимает запрет после "
          "неполного раскрытия.")


    print("## Перебор порогов магнитометра")
    print()
    print("Полный повтор реплея для каждого варианта: все защиты сохранены. "
          "C=O=T, quiet-open=35; дополнительно текущие 20/18 и hold=3 с. "
          "Ложное событие здесь - лишнее по сопоставлению с Холлом, а не доказанная "
          "ошибка на постороннем магните. Критерий выбора: минимум пропусков при нуле лишних, "
          "затем минимум среднего положительного отставания, затем среднего |Δ|. "
          "Ранние события не штрафуются как задержка, но их опережение видно в таблице.")
    runs = [("20/18", 10, current)]
    for threshold in (5, 6, 7, 8, 9, 10, 12, 15, 18, 20, 22, 25, 30, 35):
        runs.append((f"{threshold}/{threshold}", 10, replay.replay(csv_path, threshold)[3]))
    for threshold in (None, 10, 18, 22):
        label = "20/18" if threshold is None else f"{threshold}/{threshold}"
        runs.append((label, 3, replay.replay(csv_path, threshold, hold=3*NS)[3]))
    sweep_rows, ranked = [], []
    for label, hold, events in runs:
        mapping, extra = match_events(truth, events)
        ds = [(mapping[i][1]-t)/1e6 for i, (_, t) in enumerate(truth) if i in mapping]
        misses = len(truth)-len(mapping)
        late = statistics.mean(max(0, d) for d in ds) if ds else float('inf')
        absolute = statistics.mean(abs(d) for d in ds) if ds else float('inf')
        if not extra:
            ranked.append(((misses, late, absolute), label, hold, mapping))
        sweep_rows.append([label, hold, len(events), misses, len(extra),
                           stats([(mapping[i][1]-t)/1e6 for i, (c, t) in enumerate(truth) if c and i in mapping]),
                           stats([(mapping[i][1]-t)/1e6 for i, (c, t) in enumerate(truth) if not c and i in mapping]),
                           f"{late:.3f}"])
    table(["C/O, мкТл", "hold, с", "событий", "пропуск", "лишних", "закрытие Δ min/median/max", "раскрытие Δ min/median/max", "среднее max(0,Δ), мс"], sweep_rows)
    if ranked:
        _, label, hold, best = min(ranked, key=lambda r: r[0])
        print(f"Лучший из проверенных вариантов по заданному критерию: C/O={label}, hold={hold} с; "
              f"покрытие {len(best)}/{len(truth)}. Это оптимум только проверенной сетки на этой записи.")
        table(["направление", "Холл t", "лучший магнит t", "Δ, мс"],
              [["закрытие" if c else "раскрытие", f"{(t-header)/NS:.6f}",
                f"{(best[i][1]-header)/NS:.6f}" if i in best else "пропуск",
                f"{(best[i][1]-t)/1e6:+.3f}" if i in best else "нет"] for i, (c, t) in enumerate(truth)])
    print()

    print("## Поля fusion и InputManager")
    print()
    field_rows = []
    for a in sorted({f[2] for f in fusion}):
        fs = [f for f in fusion if f[2] == a]
        state_counts = Counter()
        for f in fs:
            before, _ = bracket(states, f[0])
            state_counts[before[1] if before else '?'] += 1
        field_rows.append([a, len(fs), stats([f[1] for f in fs]),
                           ', '.join(map(str, sorted({f[3] for f in fs}))), str(dict(state_counts))])
    table(["a", "число", "b угол min/median/max", "c", "последний CSV state: число"], field_rows)
    table(["Input state", "число", "угол min/median/max"],
          [[s, sum(v[2] == s for v in inputs), stats([v[1] for v in inputs if v[2] == s])]
           for s in sorted({v[2] for v in inputs})])
    c_rows = []
    for c in sorted({f[3] for f in fusion}):
        fs = [f for f in fusion if f[3] == c]
        directions = Counter()
        firmware = Counter()
        for i, f in enumerate(fusion):
            if f[3] != c:
                continue
            if i:
                directions['рост' if f[1] > fusion[i-1][1] else 'спад' if f[1] < fusion[i-1][1] else 'равно'] += 1
            state, _ = bracket(states, f[0])
            firmware[state[1] if state else '?'] += 1
        c_rows.append([c, len(fs), stats([f[1] for f in fs]), str(dict(directions)), str(dict(firmware))])
    table(['c', 'число', 'b min/median/max', 'направление b относительно предыдущего', 'последний CSV state: число'], c_rows)
    input_changes = {}
    for before, after in zip(inputs, inputs[1:]):
        if before[2] != after[2]:
            input_changes.setdefault((before[2], after[2]), []).append(after[1])
    table(['Input from -> to', 'число', 'угол min/median/max'],
          [[f'{a} -> {b}', len(v), stats(v)] for (a, b), v in sorted(input_changes.items())])
    print("a выглядит как 4 зоны около 10/80/160 градусов с пересечением диапазонов "
          "при движении в разные стороны. c=2 встречается только на спаде угла, "
          "c=1 преимущественно на росте, c=3 в обоих направлениях. "
          "c не совпадает ни с a, ни с публичным state; корреляция с направлением "
          "не доказывает, что это код направления.")
    print("b согласуется с углом. a - дискретная угловая категория, не DeviceStateManager state: "
          "её диапазоны и пересечения приведены выше. c принимает 1/2/3, его семантику "
          "(качество, статус или режим) по этой записи определить нельзя. "
          "Input state - своя грубая классификация угла: 0 у малого угла, 1 в середине, "
          "2 при большом угле; это не CLOSED/TENT/HALF_OPENED/OPENED. "
          "Наблюдаемые границы зависят от направления и гистерезиса; без кода Samsung "
          "нельзя присвоить полям официальные имена.")
    print()

    print("## Порядок обработки SW_LID")
    print()
    sw_rows, ud, wd = [], [], []
    for c, t in truth:
        p = policy_for[t]
        u = nearest([u for u in updates if u[1] == (not c)], p[0] if p else t, 1_500_000_000)
        w = nearest(wakes, p[0] if p else t, 1_500_000_000) if not c else None
        du = (u[0]-p[0])/1e6 if u and p else None
        dw = (w[0]-p[0])/1e6 if w and p else None
        if du is not None: ud.append(du)
        if dw is not None: wd.append(dw)
        sw_rows.append(["закрытие" if c else "раскрытие", f"{(t-header)/NS:.6f}",
                        f"{(p[0]-header)/NS:.6f}" if p else "нет",
                        f"{du:+.3f}" if du is not None else "нет",
                        f"{dw:+.3f}" if dw is not None else "нет"])
    table(["направление", "CSV t", "newState t", "update - newState, мс", "wake - newState, мс"], sw_rows)
    print(f"update-newState min/median/max: {stats(ud)} мс; "
          f"wake-newState: {stats(wd)} мс. "
          "Положительные значения означают, что строка обработки записана ПОСЛЕ решения "
          "newState. Строка с именем обработчика не является временем физического SW_LID. "
          "wake сопоставляется в окне 1.5 с и может относиться к более позднему угловому "
          "решению; причинность по имени строки не устанавливается. "
          "update от onAwakeStateChanged исключены, учитывается caller notifyLidSwitchChanged.")
    print()

    print("## Темп движения и остановки")
    print()
    print("Показатели описательные: при закрытии интервал от последнего hinge90 до Холла, "
          "при раскрытии от Холла до первого hinge90. До 300 мс - быстрое, "
          "300-1000 мс - среднее, более 1000 мс - медленное/возможная остановка. "
          "Это операционные группы, а не подтверждённая разметка действий пользователя.")
    motion_rows, groups = [], {}
    for i, (c, t) in enumerate(truth):
        candidates = [h for h in sensors['36'] if h[1] == 90 and (h[0] <= t if c else h[0] >= t)]
        h = (candidates[-1] if c else candidates[0]) if candidates else None
        duration = abs(h[0]-t)/1e6 if h else None
        group = 'нет' if duration is None else ('быстрое' if duration < 300 else 'среднее' if duration <= 1000 else 'медленное/остановка?')
        groups.setdefault((c, group), []).append(i)
        motion_rows.append(['закрытие' if c else 'раскрытие', f'{(t-header)/NS:.6f}',
                            f'{duration:.3f}' if duration is not None else 'нет', group])
    table(['направление', 'Холл t', 'интервал hinge90-Холл, мс', 'группа'], motion_rows)
    table(['направление', 'группа', 'число', 'hinge Δ min/median/max', 'магнит Δ min/median/max'],
          [['закрытие' if c else 'раскрытие', group, len(indices),
            stats([(hm[i][1]-truth[i][1])/1e6 for i in indices if i in hm]),
            stats([(mm[i][1]-truth[i][1])/1e6 for i in indices if i in mm])]
           for (c, group), indices in groups.items()])
    print("Остановка в полураскрытом положении не отделяется надёжно от медленного движения "
          "одним дискретным hinge. Для проверки нужны синхронные метки действий и непрерывный угол.")
    print()

    # Derive the answers from the same matches used by the detailed tables.
    def delays_for(mapping, closed):
        return [(mapping[i][1]-t)/1e6 for i, (c, t) in enumerate(truth)
                if c == closed and i in mapping]

    close_count = sum(c for c, _ in truth)
    early_hinge = [-d for d in delays_for(hm, True) if d < 0]
    early_received = [-d for d in delays_for(hrm, True) if d < 0]
    late_hinge = [d for d in delays_for(hm, True) if d >= 0]
    missed_closes = [i for i, (c, _) in enumerate(truth) if c and i not in mm]
    reset_misses = partial_misses = 0
    for i in missed_closes:
        t = truth[i][1]
        last_open = max((ot for c, ot in truth if not c and ot < t), default=header)
        full_open = any(last_open <= ht < t and value == 180
                        for ht, value, _ in sensors['36'])
        if not full_open:
            partial_misses += 1
        elif i in hm and hm[i][1] < t:
            reset_misses += 1
    best_run = min(ranked, key=lambda r: r[0]) if ranked else None

    print("## Выводы и рекомендуемые настройки")
    print()
    print("Hinge: DEFAULT_CLOSE_DEG=10 и DEFAULT_OPEN_DEG=15 не менять. "
          "Любые C в [0,90) и O в (0,90] дают те же решения на 0/90/180, "
          "поэтому настройкой этих порогов время переключения исправить нельзя. "
          f"По таблице реплея в {len(early_hinge)} из {close_count} закрытий hinge0 "
          f"опережает Холл на {stats(early_hinge)} мс (min/median/max), "
          f"по receivedNs опережение составляет {stats(early_received)} мс. "
          "Для evaluateDirect это означает, что правило угла уже запрашивает CLOSED "
          "при ещё открытом по Холлу телефоне: возможен преждевременный перевод экранов. "
          "Это вывод для правила без остальных блокировок службы. "
          f"В остальных найденных закрытиях Δ hinge0 равна {stats(late_hinge)} мс. "
          "Перенос порога закрытия на 90 только приблизит преждевременное закрытие, "
          "включая остановки в полураскрытом положении. Фиксированную задержку по этой "
          "записи также не рекомендовать: знак и величина ошибки зависят от движения. "
          "Точнее повторить Холл можно только с дополнительным сигналом или изменением "
          "логики подтверждения, что выходит за настройку порогов. Код приложения не менять.")
    print()
    print(f"Магнитометр: при текущих C/O=20/18 пропущено {len(missed_closes)} "
          f"из {close_count} закрытий. В {reset_misses} пропусках после полного раскрытия "
          "hinge0 приходит раньше Холла и сбрасывает openedSinceClosed до достаточного "
          f"магнитного импульса; ещё в {partial_misses} случаях после предыдущего "
          "раскрытия нет hinge180, поэтому closing запрещён. "
          f"В диагностике выбранных максимумов score блокировка no hinge180 указана "
          f"в {sum('no hinge180' in row[-1] for row in diagnostic_rows)} строках, "
          f"недостижение порога - в {sum('threshold ' in row[-1] for row in diagnostic_rows)}. "
          "Как оговорено у таблицы диагностики, это причины для выбранного отсчёта, "
          "а не доказательство блокировки каждого отсчёта окна.")
    if best_run:
        _, best_label, best_hold, best_mapping = best_run
        best_leads = [-d for d in delays_for(best_mapping, True) if d < 0]
        print(f"Лучший по критерию покрытия и отставания вариант проверенной сетки "
              f"C/O={best_label}, hold={best_hold} с находит {len(best_mapping)}/{len(truth)} "
              f"событий без лишних по правилу сопоставления. Но {len(best_leads)} закрытий "
              f"происходят РАНЬШЕ Холла на {stats(best_leads)} мс (min/median/max). "
              "В приложении это означает преждевременный запрос CLOSED и переключение "
              "экранов до физического срабатывания Холла. Отсутствие лишних пар в окне "
              "+/-1.5 с не означает совпадение момента переключения. "
              "Конкретная рекомендация: не заменять рабочие 20/18 на этот вариант "
              "ради имитации Холла. Использовать его только как экспериментальный "
              "кандидат для следующей записи. Оставить 20/18, quiet-open=35, "
              "phantom-hold=500 мс и foreign-field hold=10 с до проверки подтверждения "
              "закрытия и отрицательных контрольных записей. "
              "При этом 20/18 не объявляются решением пропущенных закрытий: "
              "их ограничения требуют отдельной проверки логики gate, "
              "а не только снижения порога.")
    print()

    print("## Ответы на вопросы")
    print()
    print("### 1. Что означают a, c и InputManager state?")
    print()
    print(f"Поле a принимает {min(f[2] for f in fusion)}-{max(f[2] for f in fusion)}. "
          "Диапазоны b для каждого a (min/median/max, град): " + "; ".join(
              f"a={a}: {stats([f[1] for f in fusion if f[2] == a])}"
              for a in sorted({f[2] for f in fusion})) + ". "
          "Это угловые зоны с пересечением диапазонов, а не публичные состояния "
          "CLOSED/TENT/HALF_OPENED/OPENED. Связь с CSV state приведена в таблице полей.")
    print(f"Поле c принимает {min(f[3] for f in fusion)}-{max(f[3] for f in fusion)}. "
          "Диапазоны b для каждого c (min/median/max, град): " + "; ".join(
              f"c={c}: {stats([f[1] for f in fusion if f[3] == c])}"
              for c in sorted({f[3] for f in fusion})) + ". "
          "Таблица c показывает связь с направлением движения и CSV state, "
          "но однозначно назвать это качеством, режимом или направлением нельзя.")
    print(f"InputManager state принимает {min(v[2] for v in inputs)}-"
          f"{max(v[2] for v in inputs)}. Углы (min/median/max, град): " + "; ".join(
              f"state={state}: {stats([v[1] for v in inputs if v[2] == state])}"
              for state in sorted({v[2] for v in inputs})) + ". "
          "Это грубые угловые состояния с гистерезисом; state=0 не является "
          "моментом срабатывания Холла. Числа переходов и связь с углами "
          "даны в таблице Input from -> to.")
    print()

    print("### 2. При каких углах переключается Холл и когда приходит hinge?")
    print()
    print("Все тройки ниже - min/median/max. Fusion до закрытия или после раскрытия "
          "ограничивает наблюдаемый угол; точный физический порог не измерен.")
    print()
    table(["направление", "fusion относительно P, град", "fusion относительно CSV, град",
           "hinge event - CSV, мс", "hinge received - CSV, мс"],
          [["закрытие, hinge0" if c else "раскрытие, hinge90", stats(policy_angles[c]),
            stats(angles[c]), stats(delays_for(hm, c)), stats(delays_for(hrm, c))]
           for c in (True, False)])
    print(f"До закрытия hinge0 приходит в {len(early_hinge)} из {close_count} случаев, "
          f"опережение {stats(early_hinge)} мс. "
          f"В {len(late_hinge)} случаях он приходит не раньше закрытия, "
          f"отставание {stats(late_hinge)} мс. "
          f"После раскрытия hinge90: {stats(delays_for(hm, False))} мс по измерению "
          f"и {stats(delays_for(hrm, False))} мс по доставке. "
          "Различие углов относительно P и CSV связано с тем, что первые fusion "
          "иногда доставлены раньше callback CSV. См. таблицу углов относительно P.")
    print()

    print("### 3. Каковы задержки магнитометра и лучшие пороги?")
    print()
    print("Текущие C/O=20/18; Δ<0 - опережение, Δ>0 - отставание. "
          "Тройки - min/median/max, мс; пропуски в статистику Δ не входят.")
    print()
    table(["направление", "найдено", "пропущено", "лишних", "event Δ, мс", "received Δ, мс"],
          [["закрытие" if c else "раскрытие", len(delays_for(mm, c)),
            sum(tc == c for tc, _ in truth)-len(delays_for(mm, c)),
            sum(e[0] == c for e in mf), stats(delays_for(mm, c)), stats(delays_for(mrm, c))]
           for c in (True, False)])
    if best_run:
        _, best_label, best_hold, best_mapping = best_run
        best_event_count = next(len(events) for label, hold, events in runs
                                if label == best_label and hold == best_hold)
        print(f"Лучший из проверенных по заданному критерию: C/O={best_label}, "
              f"hold={best_hold} с, найдено {len(best_mapping)}/{len(truth)}, "
              f"пропущено {len(truth)-len(best_mapping)}, "
              f"лишних {best_event_count-len(best_mapping)}. "
              f"Закрытие Δ: {stats(delays_for(best_mapping, True))} мс; "
              f"раскрытие Δ: {stats(delays_for(best_mapping, False))} мс. "
              "Таблицы перебора и лучшего реплея доказывают улучшение покрытия, "
              "но одновременно раннее закрытие. Рабочая рекомендация и причины "
              "пропусков 20/18 приведены выше: преждевременное переключение "
              "не устраняется снижением порога.")
    print()

    print("### 4. Стоят ли строки обработки SW_LID раньше newState?")
    print()
    if ud and wd and min(ud) > 0 and min(wd) > 0:
        print(f"Нет, позже: update на {stats(ud)} мс, wake на {stats(wd)} мс "
              f"(min/median/max). Сопоставлено update: {len(ud)}, wake: {len(wd)}. "
              "Это порядок диагностических строк, а не физических событий. "
              "Wake выбран в пределах 1.5 с и может относиться к следующему "
              "угловому решению; отсутствие строки не заменяется нулевой задержкой.")
    else:
        print(f"Однозначного порядка для всех пар нет либо пары отсутствуют. "
              f"update - newState: {stats(ud)} мс ({len(ud)} пар); "
              f"wake - newState: {stats(wd)} мс ({len(wd)} пар).")
    print()

    print("### 5. Чего не хватает для надёжных выводов?")
    print()
    print(f"Запись длится {(end-header)/NS:.3f} с, начинается уже CLOSED; "
          f"измерено {close_count} закрытий и {sum(not c for c, _ in truth)} раскрытий. "
          "Есть пакетная доставка hinge и задержка callback. Неизвестны точные "
          "физические времена Холла, настройки службы, сохранённая калибровка, "
          "истинный непрерывный угол и пользовательские метки темпа. "
          "Медленных движений с длинной остановкой у порога и отрицательных "
          "контрольных сценариев недостаточно для надёжной настройки. "
          "Нужны несколько повторов по 20-30 циклов: отдельно медленно у 0-30 градусов, "
          "быстро, с паузами 5-15 с на 5/10/20/45/90 градусах, без полного раскрытия "
          "между циклами, в разных ориентациях. Добавить синхронные SW_LID/input-event "
          "timestamps, видео/непрерывный угол, метки действий, screen broadcasts и "
          "замеры задержки регистрации fusion. Для оценки ложных событий записать "
          "неподвижный закрытый телефон, перенос и вращение, посторонние магниты и "
          "зарядку без складываний. Приложение и существующий реплей не изменены.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv_path')
    parser.add_argument('log_path')
    args = parser.parse_args()
    run(args.csv_path, args.log_path)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
