# Проверки FoldLidShim

## Как запустить всё

Нужны JDK 17 и Android SDK: platform android-36 и build-tools 36.0.0 согласно `app/build.gradle.kts` (compileSdk/targetSdk 36, minSdk 35, Java/Kotlin JVM 17).

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
shellcheck build.sh
```

Отчёт JUnit: `app/build/reports/tests/testDebugUnitTest/index.html`, XML результатов: `app/build/test-results/testDebugUnitTest/`. Отчёт lint: `app/build/reports/lint-results-debug.html`, XML: `app/build/reports/lint-results-debug.xml`. APK Gradle: `app/build/outputs/apk/debug/app-debug.apk`.

Сборка через Docker включает модульные тесты и lint, затем assembleDebug. Скрипт вызывает `docker build --target apk --output out .`; результат цели `apk` - `out/app-debug.apk`.

```sh
./build.sh
adb shell dumpsys device_state | grep 'Override Request active'
adb install -r out/app-debug.apk
```

Устанавливать только на раскрытый телефон без активного запроса CLOSED: проверка выше должна показать `false`. Установка убивает процесс, но его запрос может остаться от мёртвого pid, и новый процесс не сможет его снять. Причина и восстановление описаны в [Проверках и сборке](README.md#проверки-и-сборка) и [Остановке и логах](README.md#остановка-и-логи).

## Модульные тесты

Исходники: `app/src/test/java/dev/pavlov/foldlidshim/`. Ниже перечислен 101 метод с `@Test` из 12 классов, каждый ровно одной строкой таблицы. Это проверки логики на JVM; они не подтверждают работу прошивки, экранов и доставки датчиков во сне.

Пометка «Деталь вне README» означает, что раздел README даёт контекст, но конкретное утверждение теста там не сформулировано. Для DirectPolicy важно ограничение уровня: это простая политика CLOSED/CANCEL, а путь OPENED проверяет OverridePlanner.

### ConfirmButtonPickerTest (8)

Фикстура `tree` моделирует дерево диалога Samsung: заголовок, пояснение, «Отмена» и основная кнопка с индексом 6; параметры меняют язык и ID кнопки.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `realSamsungTree` | Русское дерево Samsung даёт основную кнопку с индексом 6. | [Как работает переключение](README.md#как-работает-переключение) |
| `englishTitleIdentifiedByViewId` | Английское дерево с ID заголовка даёт кнопку 6. | [Как работает переключение](README.md#как-работает-переключение) |
| `fallbackWithoutButton1` | При отсутствии button1 выбирается последняя непустая кнопка, индекс 6. | [Как работает переключение](README.md#как-работает-переключение) |
| `legacyTitleWithoutViewId` | Русский заголовок без viewId распознаётся и даёт кнопку 6. | [Как работает переключение](README.md#как-работает-переключение) |
| `legacyEnglishWithoutIds` | Английский заголовок Switch screens? без ID заголовка и основной кнопки даёт кнопку 6. | [Как работает переключение](README.md#как-работает-переключение) |
| `foreignAlertWithButton1` | Чужой заголовок Delete file? без rear-display ID не позволяет выбрать даже button1. | [Как работает переключение](README.md#как-работает-переключение) |
| `onlyCancelAndEmptyButtons` | Для обоих языков только отмена или отмена с пустой кнопкой не дают подтверждения. | [Как работает переключение](README.md#как-работает-переключение) |
| `legacyWrapperClickableText` | Старый rear-display wrapper позволяет выбрать кликабельный TextView с индексом 1. | [Как работает переключение](README.md#как-работает-переключение) |

### DirectPolicyTest (6)

Общих фикстур нет; `decide` получает желание закрытия, текущее состояние и признак своего CLOSED. Деталь вне README: эта простая политика допускает CLOSED при 4/5, тогда как README описывает правило службы не оспаривать чужие 4/5; тест не проверяет весь путь службы.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `closedAlreadyCurrent` | При желании закрытия и текущем 0 без своего запроса команда NONE. | [Как работает переключение](README.md#как-работает-переключение) |
| `closedOwnRequestIsNotRepeated` | При своём CLOSED желание закрытия даёт NONE для неизвестного состояния и состояний 0-5. | [Как работает переключение](README.md#как-работает-переключение) |
| `closingRequestsClosedUnlessAlreadyClosed` | При желании закрытия без своего запроса неизвестное состояние или 1-5 дают CLOSED. | [Как работает переключение](README.md#как-работает-переключение) |
| `openingCancelsOwnClosedEvenIfCurrentIsOpenOrUnknown` | При желании раскрытия свой CLOSED снимается командой CANCEL при любом состоянии, включая неизвестное. | [Как работает переключение](README.md#как-работает-переключение) |
| `openingAlreadyOpen` | Раскрытие без своего запроса при 2/3 даёт NONE. | [Как работает переключение](README.md#как-работает-переключение) |
| `openingNeverContestsForeignOverrides` | Раскрытие без своего запроса при неизвестном состоянии, 0/1/4/5 даёт NONE на уровне DirectPolicy, без запроса OPENED. | [Как работает переключение](README.md#как-работает-переключение) |

### DirectMotionTest (11)

`sample` переводит миллисекунды в наносекунды, использует close=10°, open=15°, early=45° и по умолчанию включает ранний запрос.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `fastClosingRequestsAt45AndKeepsClosedWhileDescending` | Быстрое снижение 180 -> 90 -> 45° закрывает на 45°, удерживает на 30/0° и открывает на 15°. | [Как работает переключение](README.md#как-работает-переключение) |
| `slowClosingWaitsForCloseThreshold` | Медленные отсчёты с секундным интервалом не закрывают до достижения 10°. | [Как работает переключение](README.md#как-работает-переключение) |
| `flexStopDoesNotClose` | Остановка и колебания около 90° после 180/120° не закрывают телефон. | [Как работает переключение](README.md#как-работает-переключение) |
| `jitterUsesHysteresis` | После 10° колебания 9-14° сохраняют закрытие, а 15° снимает его. | [Как работает переключение](README.md#как-работает-переключение) |
| `closingAfterPauseNeedsFreshVelocity` | После паузы 1800 мс угол 45° не закрывает, но свежая скорость до 30° разрешает закрытие. | [Как работает переключение](README.md#как-работает-переключение) |
| `failedClosedWaitsForDirectionChange` | После failed закрытие остаётся отмечено ошибочным на 8°, раскрытие 20° сбрасывает ошибку и разрешает новое закрытие. | [Как работает переключение](README.md#как-работает-переключение) |
| `earlyThresholdJitterDoesNotRepeatedlyClose` | После раннего закрытия на 45° рост до 45.2° открывает, а колебания 44.8-45.1° не создают новых закрытий. | [Как работает переключение](README.md#как-работает-переключение) |
| `disabledEarlyAndStaleTimestamp` | При выключенном раннем запросе 40° не закрывает, старый timestamp с 0° игнорируется, свежие 10° закрывают. | [Как работает переключение](README.md#как-работает-переключение) |
| `retryStopsOnConfirmationAndBelowOpen` | Повтор cancel разрешён сразу и через 1000 мс, прекращается после потери владения или выхода из зоны раскрытия. | [Как работает переключение](README.md#как-работает-переключение) |
| `secondStickyRestartWithinTenMinutesTrips` | Второй sticky-перезапуск через 600000 мс включает предохранитель, через 600001 мс или при времени из будущего не включает. | [Как работает переключение](README.md#как-работает-переключение) |
| `activeClosedNeverLaunchesSecondActivity` | Свой активный CLOSED даёт NONE при неизвестном состоянии и 0/2/3, исключая вторую команду закрытия. | [Как работает переключение](README.md#как-работает-переключение) |

### HallDiagnosisTest (5)

В каждом тесте создаётся отдельная история диагноза; observe задаёт состояние без собственного запроса, onAngle - реальный угол.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `noEvidenceIsUnknown` | Без углов, в том числе после состояния 0 и NaN, диагноз остаётся UNKNOWN. | [Диагноз Холла и что делает костыль](README.md#диагноз-холла-и-что-делает-костыль) |
| `closedNeedsZeroAngleAndFullMinute` | При старте в 1000 мс и постоянном 0° диагноз STUCK_CLOSED появляется ровно в 61000 мс, а 20° возвращает UNKNOWN и обновляет максимум. | [Диагноз Холла и что делает костыль](README.md#диагноз-холла-и-что-делает-костыль) |
| `stuckOpenNeedsAngleMovement` | Неизменное состояние 2/3 требует движения угла для STUCK_OPEN, а последующий согласованный 0 даёт WORKING. | [Диагноз Холла и что делает костыль](README.md#диагноз-холла-и-что-делает-костыль) |
| `recoveryAndDisagreement` | После STUCK_CLOSED согласованное раскрытие даёт WORKING, рассогласование возвращает UNKNOWN, а согласованный 0 снова даёт WORKING. | [Диагноз Холла и что делает костыль](README.md#диагноз-холла-и-что-делает-костыль) |
| `concurrentAndTentDoNotProveOpen` | Состояния 1/4/5 даже при движении 0 -> 180° не доказывают открытый Холл и дают UNKNOWN. | [Диагноз Холла и что делает костыль](README.md#диагноз-холла-и-что-делает-костыль) |

### HingeAngleMathTest (5)

`noInvert` отключает инверсию трёх осей; используются синтетические векторы, допуск угла 0.001°. Все строки описывают детали вне README: в нём упомянуты источник accel и инверсия, но не формула и обработка некорректных векторов.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `oppositeVectorsAreClosed` | Противоположные векторы (0,0,9.8) и (0,0,-9.8) дают закрытый угол 0°. | [Главный экран](README.md#главный-экран) |
| `perpendicularVectorsAreHalfOpen` | Перпендикулярные векторы разной длины дают 90°. | [Главный экран](README.md#главный-экран) |
| `alignedVectorsAreFlatRegardlessOfMagnitude` | Сонаправленные векторы с отношением длин 1:2 дают 180°. | [Главный экран](README.md#главный-экран) |
| `axisInversionCorrectsSubWithoutMutatingInputs` | Инверсия x/z исправляет sub до 180° без изменения входа, а инверсия каждой отдельной совпадающей оси даёт 0°. | [Главный экран](README.md#главный-экран) |
| `zeroAndInvalidVectorsReturnNaN` | Нулевой вектор с любой стороны, NaN и бесконечность дают NaN. | [Главный экран](README.md#главный-экран) |

### HingeBounceTest (5)

`sample` передаёт одинаковые признаки закрытия для угла 0°, время в миллисекундах и необязательное магнитное раскрытие.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `returnAfter198msIgnoresBounce` | После запроса и открытия на 119 мс возврат к 0° на 317 мс игнорирует дребезг длительностью 198 мс и очищает дедлайн. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `singleOpeningSampleExpiresWithoutMoreSamplesOrRearming` | Единственный кандидат раскрытия на 119 мс удерживается до 419 мс, затем отпускается без повторного взведения. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `magneticOpeningBypassesHold` | Магнитное раскрытие на 110 мс снимает удержание hinge и очищает дедлайн. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `outsideWindowAndWithoutRequestNoHold` | Без запроса или ровно через 1500 мс после него раскрытие не удерживается. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `openingStartedInsideWindowKeepsFull300ms` | Кандидат на 1499 мс получает полные 300 мс удержания до 1799 мс. | [Залипание «закрыто»](README.md#залипание-закрыто) |

### SensorRecordingLogicTest (3)

Используются списки RecordingSensor, временная смена Locale на Germany с восстановлением и отдельные бюджеты времени/байтов.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `selectsEligibleTypesAndPrefersFirstNonWakeUp` | Из списка выбираются только доступные continuous/on-change типы, первый non-wakeup предпочтён wakeup, защищённые и одноразовые исключаются. | [Запись датчиков](README.md#запись-датчиков) |
| `formatsEveryValueWithoutLocaleAndEscapesCsvFields` | При немецкой локали сохраняются все шесть чисел с точкой, CSV-кавычки экранируются, а header содержит время и модель. | [Запись датчиков](README.md#запись-датчиков) |
| `timeLimitIncludesBoundaryAndSizeCountsUtf8BeforeWriting` | Бюджет истекает точно на границе 90 секунд, учитывает UTF-8 до записи и не увеличивает счётчик при отказе по размеру (деталь вне README: порядок учёта байтов и отказа). | [Запись датчиков](README.md#запись-датчиков) |

### SleepPolicyTest (7)

Каждый тест использует новый SleepPolicy; времена заданы в миллисекундах, desired сообщает желание, интерактивность и владение.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `sleepCancellationBlocksAndSpuriousWakeLocks` | Отмена во сне блокирует CLOSED, пробуждение до 1500 мс после отмены требует LOCK, а ровно через 1500 мс разрешает REQUEST_CLOSED. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `screenOffGraceIncludesOneSecond` | Отмена при интерактивном экране считается связанной со сном включительно до 1000 мс после screenOff. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `normalCancellationDoesNotBlock` | Обычная отмена при интерактивном экране не блокирует CLOSED. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `sleepingCloseIsAllowedAndOpeningWakesOnlyOnTransition` | Начальное открытие не будит, закрытие во сне разрешено, а следующий переход в открытие будит только один раз. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `ownedRequestUsesCancelAndOpeningClearsSleepHistory` | Раскрытие со своим запросом не требует отдельного WAKE, очищает историю отмены во сне и не создаёт команд при последующем screenOn. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `ordinaryWakeAfterSleepingCloseRequestsClosed` | После закрытия во сне обычное пробуждение без своего запроса разрешает REQUEST_CLOSED. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `preservedRequestSleepsAndWakesWithoutReplacementOrLock` | Сохранённый свой CLOSED переживает screenOff и screenOn без нового запроса, блокировки и записи sleepCancelAt. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |

### ThresholdValidationTest (3)

Общих фикстур нет; все строки - детали вне README: раздел настроек упоминает пороги, но не эти правила валидации.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `acceptsEndpointsAndStrictlyOrderedThresholds` | Конечные пороги в диапазоне 0-180° принимаются при строгом close < open, включая крайние значения. | [Главный экран](README.md#главный-экран) |
| `rejectsOutsideEqualAndReversedThresholds` | Пороги вне 0-180°, равные и переставленные пороги отклоняются. | [Главный экран](README.md#главный-экран) |
| `rejectsNonFiniteValues` | NaN и обе бесконечности отклоняются в любом из двух порогов. | [Главный экран](README.md#главный-экран) |

### MagnetFoldDetectorTest (21)

`Capture` собирает времена closing/opening; `feed` создаёт поле H + M*amount, `vector` добавляет поперечную составляющую. Время помощников в миллисекундах переводится в наносекунды; параметры по умолчанию берутся из MagnetParams. Таблица сначала описывает окно и калибровку, затем предварительные решения и защиту от ложных событий.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `stationaryJumpAndOpeningHaveRearmAndHold` | Скачок поля в Flex выдаёт одно closing на 200 мс, а после hinge 0 opening запрещён до выдержки и появляется на 1110 мс. | [Магнитометр](README.md#магнитометр) |
| `reboundAfterOpeningCannotCloseBeforeHinge180` | После opening отскок поля не выдаёт closing до нового hinge 180 и перехода в 90°, после которых closing разрешён. | [Магнитометр](README.md#магнитометр) |
| `pureRotationIsCompensated` | При чистом вращении с гироскопом 4 рад/с модуль score не превышает 5 и событий нет. | [Магнитометр](README.md#магнитометр) |
| `gapAndOutOfOrderSamplesCannotMakeAJump` | Разрыв более 200 мс, нарушение порядка и отсчёт старше секунды не создают скачка, после разрыва score равен 0. | [Магнитометр](README.md#магнитометр) |
| `openHingeAndExpiredFlexBlockClosing` | Открытый hinge 180° и Flex 90° старше 10 с блокируют closing. | [Магнитометр](README.md#магнитометр) |
| `calibrationFitsSphereAndRejectsPoorCoverage` | Сфера радиуса 42 с центром H восстанавливается с ошибкой меньше 1e-6, плохое покрытие и утроенный радиус отвергаются. | [Калибровка H](README.md#калибровка-h) |
| `scheduledCalibrationAcceptsCoveredSphereEvenAfterMovementStops` | После покрытия сферы и остановки движения плановая калибровка принимает ровно один H и радиус 42 без событий складывания. | [Калибровка H](README.md#калибровка-h) |
| `preliminarySamplesUseConfiguredMotionPolicyWithoutChangingHingeHistory` | Предварительный угол использует пользовательский open=15/120°, но не меняет реальное closed и историю скорости hinge. | [Магнитометр](README.md#магнитометр) |
| `preliminaryPolicyDeduplicatesOwnedRequestAndWatchdogRestoresHinge` | Предварительное closing даёт CLOSED без владения и NONE при владении, watchdog возвращает CANCEL по реальному hinge, а hinge 180 немедленно отвергает closing. | [Магнитометр](README.md#магнитометр) |
| `watchdogConfirmsOrRollsBackAndBlocksForThirtySeconds` | Closing истекает ровно через 1500 мс с блокировкой 30 с, подтверждение hinge отменяет таймаут, а неподтверждённое opening увеличивает falseCount. | [Магнитометр](README.md#магнитометр) |
| `directionFilterRejectsBothSignsAndKeepsAlignedLidMotion` | Для обоих знаков скачка поперечное отношение 0.64 допускает событие, а 0.66 отвергает. | [Магнитометр](README.md#магнитометр) |
| `foreignFieldQuarantinesItsRemovalAndRecoversAfterQuietInterval` | Поле 140 мкТл блокирует своё удаление и ранние opening, после тихого интервала opening появляется на 12200 мс. | [Магнитометр](README.md#магнитометр) |
| `rollbackRearmsBothDirectionsWithoutAnotherConfirmingHinge` | После inhibitUntil обе стороны могут снова выдать событие без нового подтверждающего hinge, но до конца запрета повторов нет. | [Магнитометр](README.md#магнитометр) |
| `initialCachedHingeSeedsStateButLaterStaleHingeIsIgnored` | Первый кэшированный hinge 0 задаёт состояние даже после свежего gyro, следующий старый hinge 180 игнорируется и opening остаётся возможным. | [Магнитометр](README.md#магнитометр) |
| `openingWatchdogUsesEightHundredMillisecondsClosingKeepsFifteenHundred` | Opening истекает ровно через 800 мс, closing через 1500 мс, обе стороны запрещаются на следующие 30 с. | [Магнитометр](README.md#магнитометр) |
| `phantomClosingBlocksFastRemovalButAllowsOpeningAfterOneSecond` | Фантомный рост поля при закрытом hinge блокирует падение через 100 мс даже с поперечной составляющей, но допускает opening при падении через 1000 мс. | [Магнитометр](README.md#магнитометр) |
| `closingSettlingBeforeOpeningHoldIsNotPhantom` | Рост до истечения 700 мс после hinge 0 не считается фантомом и допускает opening на 800 мс. | [Магнитометр](README.md#магнитометр) |
| `openingRejectsOnlyRiseBeforeWindow` | Рост перед окном на 15 мкТл запрещает opening, нулевой рост и падение на 15 мкТл допускают его на 1650 мс. | [Магнитометр](README.md#магнитометр) |
| `phantomDuringQuarantineExtendsItEvenWhenPreviewIsBlocked` | Новый фантом внутри карантина продлевает запрет даже при заблокированном preview, и opening появляется только на 3800 мс. | [Магнитометр](README.md#магнитометр) |
| `quietPhoneNeedsLargerOpeningButMissingOrMovingGyroUsesNormalThreshold` | При gyro 0.1 падение 25 мкТл не открывает, 40 открывает, а gyro 1.0 или отсутствие gyro допускают падение 25. | [Магнитометр](README.md#магнитометр) |
| `openingRequiresFullPreWindowHistoryAfterStartAndGap` | После старта и после разрыва без полной предыстории pre-window скачок поля не выдаёт opening. | [Магнитометр](README.md#магнитометр) |

### MagnetFoldDetectorReplayTest (4)

Ресурсы `app/src/test/resources/sensors-{1,2,3,4}-trim.csv` читаются в порядке строк: type 4 -> onGyro, type 14 -> onMag, type 36 -> onHinge. Event хранит направление, timestamp и score. Переходы hinge служат эталоном, одноимённое событие сопоставляется в пределах 1.5 с; базовые опережения заданы в самих тестах. Это реплей реальных записей, без маскировки ложных событий сторожем preview.

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `realRecordingLeadsHingeWithoutFalseEvents` | Запись 1 даёт ровно 7 сигналов для 8 целевых переходов, без ложных событий, с остатком вращения <22, заданными опережениями и итоговой сферой rms=2.89/R=42.26 при отсутствии плановых принятых калибровок. | [Магнитометр](README.md#магнитометр) |
| `closedRecordingHasNoEventsEvenAfterForeignFieldAndPartialOpenings` | Запись 2 с внешним полем и частичными раскрытиями не даёт событий при одинаковых close/open порогах 18, 20 и 22. | [Магнитометр](README.md#магнитометр) |
| `laptopMovementDoesNotOpenClosedPhone` | Запись 3 при порогах 18/20/22 даёт только closing около 6.16 с и opening около 86.52 с, без событий у ноутбука на 20-35 с и без ухудшения базового опережения более чем на 30 мс. | [Магнитометр](README.md#магнитометр) |
| `scenarioRecordingPreservesV13CoverageWithoutLaptopEvents` | Запись 4 содержит 18 переходов и сохраняет минимум 14/13/13/14 событий для 18/18, 20/20, 22/22, 20/18, без ложных событий и событий на 59-79 с, с ухудшением заданных опережений не более 30 мс. | [Магнитометр](README.md#магнитометр) |

### OverridePlannerTest (23)

Общих фикстур нет: каждый сценарий создаёт OverridePlanner, plan получает желание, системное состояние, своё состояние или null, интерактивность и время в миллисекундах. Именованные параметры уточняют свежесть свидетельства, зоны hinge, подтверждение открытия и доступность запроса. Для удобства строки сгруппированы ниже по смыслу.

#### Живой Холл и свежесть решения

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `workingHallNeedsNoRequestAndLogsOncePerClosure` | При системном 0 без владения закрытие даёт NONE и один лог на эпизод, следующий эпизод снова логируется. | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |
| `recoveredHallKeepsEitherOwnedRequestUntilOppositeDirection` | Восстановившийся Холл не снимает свой CLOSED/OPENED до противоположного направления, которое даёт CANCEL. | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |
| `systemOpeningInvalidatesStickyClosedForEveryOpenStateAndScreenMode` | Выход системы из 0 в любое 1/2/3 при любом режиме экрана сбрасывает закрытое решение, игнорирует свидетельства не позже перехода и разрешает CLOSED только по более свежему. | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |
| `systemOpeningWithOwnedRequestDoesNotInvalidateDecision` | При своём запросе выход системы из 0 не записывает сброс решения, после потери владения CLOSED остаётся разрешён (деталь вне README о сохранении решения при своём запросе). | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |
| `firmwareOpeningNeverWakesEvenWhenConfirmationArrivesLater` | Системное раскрытие из 0 не даёт WAKE даже при более позднем магнитном подтверждении, независимо от желания на момент перехода. | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |
| `earlySystemObservationSignalsPendingClosedCancellationOnce` | Ранний observeSystem сообщает о необходимости снять ожидающий CLOSED один раз на выход из 0, следующий plan логирует сброс один раз, а новый цикл снова разрешает сигнал. | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |

#### Закрытие и выдержка во сне

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `stuckOpenRequestsClosedImmediately` | При включённом экране и системном 2 закрытие немедленно даёт REQUEST_CLOSED, а наличие своего 0 подавляет повтор. | [Как работает переключение](README.md#как-работает-переключение) |
| `sleepingFoldWaitsBeforeRequestingClosedWithRelockEvenWithoutScreenEvents` | Закрытие во сне запускает дедлайн 1010 мс от отсчёта 10 мс, до него даёт NONE, на нём REQUEST_CLOSED_AND_RELOCK даже без screen events. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `sleepingFoldFollowsHallBeforeDeadlineWithoutLaunchingActivity` | Если Холл сообщает 0 до дедлайна закрытия во сне, запрос не отправляется, дедлайн очищается и логируется следование системе. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `openingClearsSleepingClosedDeadlineAndNextClosureStartsNewGrace` | Раскрытие даже без подтверждения очищает дедлайн закрытия во сне, следующее закрытие получает новую полную выдержку. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `userWakingDuringGraceRequestsClosedImmediatelyWithoutRelock` | Пробуждение пользователем во время выдержки немедленно даёт REQUEST_CLOSED без повторной блокировки и очищает дедлайн. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `freshSleepingCloseAfterSystemOpeningGetsFullGrace` | Свежее закрытие во сне после системного раскрытия получает полные 1000 мс, после дедлайна возвращается REQUEST_CLOSED_AND_RELOCK до появления владения. | [Когда датчик Холла работает](README.md#когда-датчик-холла-работает) |

#### Залипание «закрыто» и recovery

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `stuckClosedWaitsExactly700AndRefusalWaitsForDirectionChange` | Открытие при системном 0 даёт REQUEST_OPENED ровно через 700 мс, отказ не повторяется до смены направления и новой выдержки. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `recoveryMustFinishBeforeOpenedAndKeepsFirstSampleTime` | Pending recovery задерживает OPENED, сохраняя время первого отсчёта, поэтому после завершения recovery запрос разрешён без новой выдержки. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `busyActivityDoesNotConsumeOpenedAttempt` | При requestsAllowed=false на дедлайне OPENED не отправляется и попытка сохраняется до разрешения запросов (деталь вне README). | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `graceRestartsWhenHingeLeavesOpenZoneOrSystemOpens` | Выход hinge из открытой зоны или выход системы из 0 сбрасывает выдержку, и новые 700 мс отсчитываются заново. | [Залипание «закрыто»](README.md#залипание-закрыто) |
| `magnetOpeningCanCancelButCannotRequestOpenedBeforeRealHinge` | Магнитное открытие может снять свой CLOSED, но без реального hinge в openedZone не запускает OPENED и его дедлайн. | [Магнитометр](README.md#магнитометр) |

#### Подтверждение раскрытия и пробуждение

| Имя теста | Условия и ожидаемое решение | Правило README |
| --- | --- | --- |
| `confirmedOpeningOwnClosedCancelsEvenWhileAsleep` | Подтверждённое раскрытие со своим CLOSED даёт CANCEL даже во сне. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `unownedSleepingOpeningWakesOnlyOnceAndInitialOpenDoesNotWake` | Начальное открытие во сне не будит, переход закрыто -> подтверждённо открыто без владения даёт WAKE только один раз. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `sleepingOwnClosedWaitsForConfirmationAndFalseOpeningDoesNothing` | Во сне свой CLOSED сохраняется при неподтверждённых и ложных раскрытиях с однократным логом ожидания, подтверждение даёт CANCEL. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `sleepingUnownedOpeningKeepsWakeUntilConfirmation` | Без владения раскрытие во сне сохраняет ожидание до подтверждения, затем даёт WAKE с однократным логом. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `falseUnownedOpeningAndUserWakeConsumeNoWakeAction` | Ложное раскрытие не даёт WAKE, а самостоятельное пробуждение пользователя поглощает ожидающий WAKE и не оставляет его на следующий сон. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |
| `interactiveOpeningStillCancelsOnFirstUnconfirmedEvent` | При интерактивном экране первое неподтверждённое раскрытие со своим CLOSED сразу даёт CANCEL. | [Сон закрытого телефона](README.md#сон-закрытого-телефона) |

## Реплей записей датчиков

### Новая запись и общий формат

По разделу [Запись датчиков](README.md#запись-датчиков): нажмите «Запись датчиков», сверните приложение, несколько раз сложите и раскройте телефон; «Метка» добавляет якорь. «Стоп, осталось N с» завершает запись вручную, автоматическая остановка происходит через 90 с или при 30 МБ. Новый старт заменяет `files/sensors.csv`. После остановки UI показывает размер, число и типы зарегистрированных датчиков и ошибки регистрации. Запись работает отдельной foreground-службой с временным partial wake lock и не включает службу складывания. При остановке освобождаются слушатели, поток, wake lock и служба; после убийства процесса запись не возобновляется, буферизованные строки могут потеряться.

Для debug APK:

```sh
adb shell run-as dev.pavlov.foldlidshim cat files/sensors.csv > sensors.csv
python3 tools/sensors_report.py sensors.csv
```

Нужен Python 3.10+. Все четыре скрипта используют UTF-8 CSV с разделителем `;`, десятичной точкой и стандартным CSV-экранированием текстовых полей:

```text
elapsedNs;timestampNs;type;name;v0;v1;v2;...
elapsedNs;0;header;<unix-ms старта>;<Build.MODEL>
elapsedNs;0;state;<committed state>
elapsedNs;0;mark;<текст>
```

Первая колонка - время получения elapsedRealtimeNanos (в отчётах также называется receivedNs); вторая - SensorEvent.timestamp. Для state/mark время берётся из первой колонки, для измерений из второй. Сохраняются все значения, включая bias некалиброванных датчиков; модули используют первые три. Магнитное поле в мкТл, ускорение в м/с², гироскоп в рад/с, скаляры в единицах датчика. Ноль относительного времени отчётов соответствует header.elapsedNs.

Все скрипты принимают стандартный `-h` / `--help`. Аргументы ниже сверены с argparse. Примеры README для sensors_report и magnet_replay_report совпадают с кодом, но не перечисляют весь интерфейс: `--economy` там не описан, hall_capture_report и trim_sensor_recording там не документированы.

### magnet_replay_report.py

Назначение: повторить обработку type 14, гироскопа type 4 и hinge type 36 на записи, сравнить события с переходами hinge, исследовать фильтры, калибровку и длину карантина сильного поля. Нужны header и записи этих сенсоров в исходном порядке; type 65579 используется для диагностических меток движения.

Аргументы:

| Аргумент | Значение |
| --- | --- |
| `fixture` | Необязательный позиционный CSV; если нет `--hold-sweep`, печатает подробный отчёт для него, затем перебор выдержек. |
| `--hold-sweep CSV [CSV ...]` | Один или несколько CSV для перебора выдержек и порогов; при наличии имеет приоритет над fixture. |
| `--event-free-phase CSV START END` | Повторяемый параметр, три значения: файл и границы фазы без событий в секундах от header; используется при выборе выдержки. |
| `--sweep-json PATH` | Дополнительно записывает измерения перебора в JSON. |
| `--economy` | Флаг допущения, что экран интерактивен всю запись; записи не содержат экранных broadcasts. |

Без fixture и --hold-sweep argparse завершает запуск ошибкой. Примеры из [Магнитометра](README.md#магнитометр) и [Длины карантина](README.md#длина-карантина-перебор-после-сильного-поля):

```sh
python3 tools/magnet_replay_report.py app/src/test/resources/sensors-1-trim.csv
python3 tools/magnet_replay_report.py --hold-sweep app/src/test/resources/sensors-{1,2,3,4}-trim.csv --event-free-phase app/src/test/resources/sensors-4-trim.csv 59 79 --sweep-json /tmp/hold-sweep.json
```

Вывод содержит параметры, события и признаки кандидатов, причины блокировки, опережения hinge, ложные события, остаточный score вне переходов, попытки калибровки, таблицы перебора порогов и выдержек, выбранную выдержку и нарушения фаз без событий. Положительный leadMs означает опережение hinge. Пропуск перехода, ложное событие или событие в запрещённой фазе ограничивают пригодность параметров. Выбор выдержки справедлив только для переданных записей. Строгий фильтр сфер дан как диагностика; watchdog preview, rollback и его запрет не моделируются, датчики реплеятся непрерывно. Успешное завершение само по себе не означает отсутствие ложных событий: нужно читать отчёт.

### hall_capture_report.py

Назначение: сопоставить запись при живом Холле с журналом прошивки, оценить задержки доставки hinge и магнитных решений относительно callback состояния, рассмотреть угловые данные и перебор порогов.

Аргументы: два обязательных позиционных пути `csv_path log_path`, дополнительных опций кроме помощи нет.

```sh
python3 tools/hall_capture_report.py sensors.csv firmware.log
```

Входной CSV того же формата должен содержать header и state callbacks, сенсоры 2/14/36; импортированный магнитный реплей использует также type 4. Второй файл - текстовый лог с epoch-временем в начале строк и timestamped строками `lid_angle_fusion ts=... ns value [...]` или `hinge_angle ts=... ns value ...`. Скрипт также разбирает строки Input, политики `newState/lastState`, lid-switch update и wake. При отсутствии header/state или timestamped строк датчиков отчёт не строится.

Печатает Markdown-таблицы всех callback, соседних fusion/hinge, поля и score, задержек, углов, сопоставлений с политикой, перебора параметров и ответы о пределах наблюдения. Часы совмещаются по минимуму epoch минус timestamp сенсора; неизвестная минимальная задержка лога остаётся систематической погрешностью. Δ в мс - время сигнала минус время callback CSV: минус означает раньше, плюс позже. Измерение и доставка показаны отдельно. Начальный state не считается переходом. Сопоставление одноимённых переходов выполняется один к одному в пределах 1.5 с; пропуски не превращаются в нулевые задержки. Это проверка реального покрытия и задержек реплея, но не измерение точного физического порога Холла и не полная эмуляция службы.

### sensors_report.py

Назначение: первичный осмотр записи и временных окрестностей якорей, без запуска детектора складывания.

| Аргумент | Значение |
| --- | --- |
| `file` | Обязательный путь CSV описанного выше формата. |
| `--anchor-threshold FLOAT` | Порог всплеска ускорения в м/с², по умолчанию 6.0; конечное неотрицательное число. |
| `--window-ms INT` | Радиус окна вокруг якоря в мс, по умолчанию 1000; положительное целое. |
| `--step-ms INT` | Шаг таблицы в мс, по умолчанию 50; положительное целое. |

```sh
python3 tools/sensors_report.py sensors.csv --anchor-threshold 6 --window-ms 1000 --step-ms 50
```

Печатает число событий и средний период каждого сенсора, статистику модуля и осей магнитометра, якоря изменений hinge, состояний, меток и всплесков ускорения, затем таблицы окон. В каждой точке окна выбран последний отсчёт не позже неё, `n/a` означает отсутствие предшествующего измерения. По выводу проверяют наличие данных, частоты и последовательность событий перед реплеем; сам скрипт не выносит решения CLOSED и не доказывает корректность детектора.

### trim_sensor_recording.py

Назначение: подготовить компактную фикстуру из исходной записи, сохраняя порядок доставки строк.

Аргументы: обязательные позиционные пути `source target`, дополнительных опций кроме помощи нет.

```sh
python3 tools/trim_sensor_recording.py sensors.csv /tmp/sensors-trim.csv
```

Вход - общий CSV выше. Сохраняются type 14, 36, 65579, header, state, mark и каждый пятый отсчёт type 4, начиная с первого; строки короче четырёх полей и остальные типы отбрасываются. Результат записывается в target, скрипт ничего не печатает. Проверять нужно содержимое выходного файла и реплей по нему; подготовка фикстуры сама не проверяет решения.

Таким образом, magnet_replay_report и hall_capture_report проверяют детектор на реальных измерениях вместо выдуманных чисел, а sensors_report и trim_sensor_recording помогают проверить и подготовить эти измерения. Вместе с JVM-реплеями это воспроизводимая проверка записей, но не замена сценариям на телефоне.

## Ручные проверки на устройстве

Ниже приведены результаты проверок 02.11.2026 при работающем датчике Холла, сборка после коммита `053368f`. Это зафиксированные наблюдения, а не результат нового прогона при подготовке документа.

Лог приложения имеет формат `unixMs elapsedNs=N сообщение`:

```sh
adb shell run-as dev.pavlov.foldlidshim tail -40 files/timeline.log
```

Пробуждения и засыпания:

```sh
adb shell logcat -d -v monotonic | grep -E "PowerManagerService: (Waking up|Going to sleep)"
```

Причина `reason=application ... dev.pavlov.foldlidshim` означает, что экран разбудило приложение; такого быть не должно.

Состояние прошивки:

```sh
adb shell cmd device_state print-state
adb shell dumpsys device_state | grep -E "mCommittedState|mBaseState|mOverrideState"
```

### Флаг 2 переживает сон

Выключить службу, раскрыть телефон. Нажать «Закрыть принудительно», подтвердить диалог, нажать кнопку питания, подождать 50 с. Ожидается: dumpsys показывает mOverrideState CLOSED при mBaseState OPENED, строки `sleep cancel` нет. Кнопка питания будит внешний экран; тап по внешнему экрану также будит (`reason=tap`, `sec_touchscreen2`). «Открыть принудительно» снимает запрос, состояние 3. При выключенной службе диалог Samsung подтверждает пользователь: автоподтверждение работает только при включённом переключателе службы.

### Складывание при включённом экране

Ожидается последовательность:

```text
decision CLOSED
startActivity action=closed
requestState send state=0 generation=N flags=2
rear display dialog seen
rear display confirm click
device state=0
request callback onRequestActivated
```

Когда датчик Холла сам даёт 0:

```text
request canceled after activation, system state=0
request callback onRequestCanceled
device state=0
system already CLOSED, no request
```

Второго `decision CLOSED` и второй StateActivity быть не должно. Замер: внешний экран включается через 85-120 мс после решения; при медленном складывании решение опережает Холл на 0.6 с, при быстром Холл снимает запрос через 37-113 мс после активации.

### Раскрытие при включённом экране

Ожидается `device state=1` или `2`, затем `system left CLOSED, decision reset`, далее `device state=3`. Строки `decision CLOSED` нет.

### Складывание спящего телефона

Нажать кнопку питания, затем сложить. Ожидается `closed while asleep: waiting for system`, затем `device state=0` и `system already CLOSED, no request`. StateActivity не запускается, в logcat нет пробуждения с `reason=application`.

### Раскрытие спящего телефона

Ожидается `magnet opening`, `opening unconfirmed: waiting`, `device state=2`, `system left CLOSED, decision reset`, `magnet opening confirmed`, `device state=3`. Пробуждение в logcat с `reason=unfold_device`.

### Тап по внешнему экрану

Тап по внешнему экрану закрытого заблокированного телефона будит с `reason=tap` и `sec_touchscreen2`.

### Симуляция залипания «закрыто»

На раскрытом телефоне:

```sh
adb shell cmd device_state state 0
```

Ожидается через 700 мс `lid stuck closed: request OPENED`, `startActivity action=opened`, `requestState send state=3`, затем `OPENED refused: SecurityException` и `activity result OPENED success=false`. Прошивка отклоняет OPENED приложению и во сне, и при разблокированном экране: приложениям доступен только CLOSED.

Возврат:

```sh
adb shell cmd device_state state reset
```

После него `device state=3` и `system left CLOSED, decision reset`.

### Отмена неактивированного запроса

Если система сняла запрос до подтверждения диалога, ожидается `request canceled before activation, system state=0` и отсутствие повторных `requestState send` каждую секунду. На устройстве при автоподтверждении диалога воспроизвести не удалось: диалог подтверждается за 1 мс.

## Что не проверено

- Сценарии с залипшим в «открыто» датчиком Холла: складывание спящего телефона (выдержка 1 с, StateActivity включает экран, запрос CLOSED, подтверждение диалога, повторная блокировка, строки `closed while asleep: request CLOSED` и `relock after closed`), пробуждение и снятие запроса при раскрытии спящего телефона (WAKE и CANCEL по подтверждению, ожидаемая задержка 250-500 мс против живого датчика). Проверяются только на телефоне с неисправным датчиком: `cmd device_state base-state 3` держит подмену лишь до первого физического изменения крышки и для имитации не годится.
- Залипание «закрыто» приложением не лечится: OPENED отклонён, `hinge_angle` при залипшем «закрыто» всегда 0. Только adb: `cmd device_state state 3`, потом `state reset`.
- Расход батареи за сутки не замерялся; команды приведены в [Расходе ресурсов](README.md#расход-ресурсов).
