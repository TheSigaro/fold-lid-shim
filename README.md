# FoldLidShim

Костыль для Galaxy Z Fold7 (SM-F966B, Android 16) с мёртвым датчиком Холла: система не засчитывает закрытие, SW_LID не срабатывает. Приложение следит за углом шарнира (`Sensor.TYPE_HINGE_ANGLE`) и при закрытии переводит телефон в состояние CLOSED, а при раскрытии снимает override.

## Режимы работы

### Прямой режим (по умолчанию)

Использует скрытый API `DeviceStateManager` напрямую через рефлексию. Не требует adb, Shizuku или root. Процесс приложения должен быть на переднем плане.

**Шаги настройки на телефоне:**

1. Установите APK: `adb install -r out/app-debug.apk`.
2. Откройте FoldLidShim, разрешите уведомления (запрос появляется при старте).
3. Нажмите "Поверх других приложений" и разрешите (SYSTEM_ALERT_WINDOW) - нужно для запуска StateActivity из фоуground-службы.
4. Нажмите "Оптимизация батареи" и разрешите приложению работать без ограничений.
5. Убедитесь, что выбран режим "Прямой (по умолчанию)" в настройках.
6. Включите переключатель "Служба включена".

### Shizuku (запасной)

Использует Shizuku для выполнения команд `cmd device_state` от имени uid shell. Требует adb или беспроводную отладку для запуска Shizuku.

**Шаги настройки:**

1. Установите Shizuku из Play Store (пакет `moe.shizuku.privileged.api`).
2. Запустите Shizuku через adb или беспроводную отладку.
3. В FoldLidShim переключитесь на режим "Shizuku".
4. Нажмите "Запросить разрешение Shizuku" и подтвердите.
5. Разрешите уведомления, нажмите "Оптимизация батареи".
6. Включите переключатель "Служба включена".

## Логика работы

Угол <= 10 градусов без задержки (holdMs = 0) - запрос состояния CLOSED (один раз), угол >= 15 градусов - отмена override (один раз), между порогами ничего не происходит. Пороги меняются на главном экране.

## Тесты и сборка

JUnit 4 подключён через version catalog. Тесты в `app/src/test/` работают с чистой Kotlin-логикой без моков Android. Для Gradle нужны JDK 17 и Android SDK 36:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

В фермерской песочнице команда запускается через подготовленную обёртку:

```bash
farm-check -C . --net -- /cache/android/with-android ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
farm-check -C . -- shellcheck build.sh
```

Dockerfile выполняет `testDebugUnitTest lintDebug` перед `assembleDebug`. Ошибка тестов или lint прерывает сборку. Финальная стадия `apk` копирует только APK, без отчётов lint.

Проверяемые правила `FoldDeciderTest`:

- `closesAtInclusiveThreshold` - включительный порог закрытия, отсутствие повторов до и после подтверждения.
- `opensAtInclusiveThresholdAndDoesNotRepeat` - включительный порог открытия, отсутствие повторов и лишнего стартового сброса после успеха.
- `middleZoneNeverRequestsACommand` - промежуточная зона до и после закрытия.
- `noiseYieldsExactlyOneCloseBeforeAndAfterAcknowledgement` - шум 9, 11, 9, 11 даёт одну команду.
- `closeOpenCloseProducesThreeCommands` - полный цикл из трёх команд.
- `oppositeAngleWhilePendingIsReconciledAfterResult` - противоположный угол во время ожидания direct-результата.
- `holdRequiresFullDurationAndMiddleZoneCancelsIt` - удержание 400 мс, отмена промежуточным углом, повтор после ошибки.
- `failedCloseAndOpenRetryOnNextReading` - ошибки обоих направлений разрешают повтор.
- `startupResetWaitsForSuccessSuppressesPendingDuplicatesAndRetriesFailure` - страховка старта, подавление ожидающего сброса, повтор после ошибки.
- `resetRearmsStartupSafetyAndClearsPendingCommand` - reset возвращает стартовую страховку и снимает ожидание.
- `settingsReloadPreservesActiveAndPendingCommands` - смена настроек сохраняет override, ожидающую команду и подтверждённый сброс.

`ThresholdValidationTest`: `acceptsEndpointsAndStrictlyOrderedThresholds` проверяет допустимые границы 0 и 180 и близкие пороги; `rejectsOutsideEqualAndReversedThresholds` проверяет выход за границы, равенство и обратный порядок; `rejectsNonFiniteValues` проверяет NaN и бесконечности.

В `FoldService` ветвления по порогам и флаги `holdPending`/`resetConfirmed` заменены вызовом `FoldDecider.onAngle`. Таймер удержания использует `nextEvaluationMs`; результаты Shizuku и direct (включая ошибки запуска и таймауты) передаются в `onApplied`. Активный `DirectStateController.hasRequest` подавляет только повторное закрытие, сохраняя возможность открытия. Перезагрузка порогов через `configure` сохраняет состояние; при смене режима после отмены старого override вызывается `reset`. Wake lock освобождается после события, если удержание не требуется.

### Сборка APK в Docker

Нужен только Docker, Android SDK на хосте не нужен:

```
./build.sh
```

Готовый debug APK (подпись debug) окажется в `out/app-debug.apk`. Установка: `adb install -r out/app-debug.apk`.

## После перезагрузки

Если служба была включена, она стартует сама (BOOT_COMPLETED). В прямом режиме служба работает автономно без дополнительных действий. В режиме Shizuku после перезагрузки Shizuku сам не запускается (при запуске через adb), поэтому служба показывает в уведомлении "Ожидание Shizuku" и работает без падений. Запустите Shizuku заново, приложение подхватит его автоматически.

## Как отключить

- Выключите переключатель "Служба включена": при остановке служба снимает свой override.
- Если override завис (например, процесс убит), снимите его вручную: `adb shell cmd device_state state reset`.

## Логи

`adb logcat -s FoldLidShim` - каждое изменение состояния и каждая команда с кодом возврата и выводом.
