# Отчёт v10

## Изменения и безопасность

Все изменения оставлены в рабочей копии, без коммита и создания .git. Удалённый перед передачей AdbKeyPemTest.kt не восстанавливался.

- `app/src/main/java/dev/pavlov/foldlidshim/DirectStateController.kt:289`: `setFlags.invoke(builder, 0)`. Флаги 1 и 8 не устанавливаются. Удалены requestOpen и DirectPolicy.Command.OPENED.
- `DirectPolicy.kt`: таблица прямого режима приведена в README. CLOSED только если нет нашего запроса и текущее состояние не 0; открытие только CANCEL своего CLOSED, иначе NONE при любом состоянии.
- `DirectMotion.kt`: чистые решения по SensorEvent.timestamp, немедленные пороги, раннее закрытие <=45° при >=90°/с, настройка включена по умолчанию. Скорость считается по соседним валидным отсчётам с интервалом до 500 мс; устаревшие отметки игнорируются. Пауза не порождает раннее закрытие. При продолжении движения новое быстрое снижение вновь допускает ранний запрос. Гистерезис сохраняет раннее CLOSED на нисходящих отсчётах выше open.
- `CancelRetry`: подтверждённое снятие прекращает повторы, отказ/таймаут сохраняет необходимость снятия; интервал >=1000 мс, при возвращении ниже open повторы прекращаются.
- `RestartGuard` и Prefs: два null-intent старта за 600000 мс блокируют службу с enabled=false до ручного включения. История сохраняется между процессами. Изменение системных часов может изменить длину этого окна.
- `StateActivity.kt`: запрос и cancel исполняются только при resumed и фокусе. Нет задержек перед командой. Результат отправляется в onDestroy после завершения окна. Убраны 300 мс перед finish и опрос каждые 50 мс. На callback реагирует listener.
- `FoldService.kt`: direct обходит StartupGate и FoldDecider, не ждёт три отсчёта, не запускает reconcileTick и не создаёт wake lock. Эти классы и путь согласования Shizuku сохранены.
- `ShizukuBridge.kt:155`: добавлен только барьер direct и освобождение прежнего соединения при переходе в direct. Это закрывает поздний binder callback, который иначе мог заново биндить из direct. Команды, разбор состояний и согласование режима Shizuku не менялись.
- `MainActivity.kt`, `FoldTileService.kt`: ранняя настройка, просмотр журнала, статус предохранителя/недоступного OPENED, плитка direct только снимает запрос. Ручное снятие не вызывает немедленного автоматического повторного закрытия.
- `AngleSource.kt`, `Timeline.kt`: timestamp датчика и время получения, ограниченный журнал. Формат, предел 64 КБ и команда run-as описаны в README.

Не изменены ShizukuAutoStarter.kt, adb/, AdbClient*, BinderNudgeActivity, ReconcilePolicy, Dockerfile.

## Все места снятия

Единственный прямой вызов DirectStateController.cancel находится в StateActivity для ACTION_OPEN, где OPEN означает снятие, а не requestState(3).

| Причина | Путь |
| --- | --- |
| Раскрытие | onAngle -> DirectMotion -> FoldService.evaluateDirect:90 -> applyState(false) -> resumed StateActivity |
| Повтор после отказа/таймаута | retryCancel -> evaluateDirect -> CancelRetry, не чаще раза в секунду, до onRequestCanceled |
| Выключение | FoldService.stop:636 -> ACTION_STOP -> stopAfterCancel -> тот же CANCEL; stopSelf после подтверждения |
| Смена режима | FoldService.handleReload:266 -> reloadAfterCancel -> CANCEL; переключение после подтверждения |
| Ручное открытие | MainActivity.forceState(false) -> StateActivity.launch(false) -> cancel |
| Плитка direct | StateActivity ACTION_OPEN -> cancel |
| Конец ручного теста | однократное StateActivity.launch(false) через 3 секунды |
| Восстановление sticky | при старте состояние 0, нет собственного запроса, угол >=open: один CANCEL; отказ виден в статусе |

Binder-возврат cancel не очищает hasRequest. Владение снимает только onRequestCanceled. Для sticky погибшего процесса callback здесь отсутствует, поэтому выполняется одна попытка, ошибка показывается, повторного перехвата фокуса нет. Если состояние успело стать 4/5 или базовым открытым, восстановительная попытка отменяется.

При неожиданном убийстве/force-stop Android не гарантирует onDestroy или возможность запустить активити. Запрос либо снимет система при смерти процесса, либо сохранит sticky. Этот случай покрывается восстановительной попыткой при следующем разрешённом старте, а не попыткой вызвать запрещённый cancel из фоновой службы.

## Этапы задержки

Новых измерений без телефона нет. Следующие оценки отделяют известные измерения из задания от ожидаемых порядков величин.

| Этап | Оценка | Почему остаётся |
| --- | --- | --- |
| Физическое движение и доставка датчика | неизвестно; раньше весь путь до запроса занимал 864-1415 мс | Samsung sensor/HAL; ранний порог уменьшает требуемое физическое движение |
| SensorEvent -> callback на worker | ожидается менее нескольких мс без нагрузки, не измерено | нужен системный on-change callback; timestampNs/receivedNs позволят измерить |
| Проверка порогов/скорости/политики | ожидается <1 мс, не измерено | исключает повтор, ошибочный Flex-запрос и запрещённый OPENED; без таймера |
| startActivity -> onCreate -> resumed/фокус | обычно десятки мс, отдельно не измерено | сервер требует top pid и IMPORTANCE_FOREGROUND; foreground service не подходит |
| Reflection -> requestState binder | ожидается единицы мс, не измерено | единственный доступный API без CONTROL_DEVICE_STATE; классы/менеджер инициализируются и проверяются |
| requestState -> создание системного диалога/событие | отдельно не измерено; прежний общий request/диалог/commit 68-134 мс | обязательный RDM education без привилегии |
| AccessibilityEvent -> проверка заголовка и button1 -> клик | ожидается единицы мс, не измерено | нужен безопасный выбор именно нашего диалога; notificationTimeout теперь 0 вместо 50 мс |
| Первый распознанный диалог | дополнительно один обход дерева, не измерено | сохранён прежний единственный диагностический лог; далее основной путь по ID |
| Запасной поиск без ID | один обход небольшого дерева, не измерено | совместимость с прежними заголовками/разметкой |
| Клик -> commit -> переключение дисплеев | отдельно клик/commit не измерено; после commit ранее около 100 мс | системное управление дисплеями; приложение его не ускоряет |

В очереди журнала нет ожидания перед request/cancel. Logcat и постановка записи выполняются только на значимых событиях, не на каждом отсчёте. Оценки не являются гарантией времени Android под нагрузкой.

## Двойной запуск

В исходном v9 StateActivity отправляла report сразу после request, до finish, затем ждала минимум 300 мс и опрашивала directConfirmationPending. FoldService.completeDirectAction очищала pendingAction и синхронно вызывала evaluate. Это оставляло старую активити живой после освобождения сервисного pending-gate и допускало новый запуск до её finish. Интервал 120-185 мс из задания попадает в это окно.

Изменено:
- `StateActivity.kt:170`: результат отправляется из onDestroy; опрос и задержка finish удалены.
- `FoldService.kt:466`: direct больше не зависит от onApplied/resetConfirmed/holdSatisfied старого FoldDecider. Стабильный CLOSED подавляется фактическим hasClosedRequest и DirectPolicy.
- `FoldService.kt:494`: пока действие ожидается, новый запуск не создаётся; перед началом отмены старый незапущенный CLOSED инвалидируется через gate.
- `DirectMotionTest.activeClosedNeverLaunchesSecondActivity`: политика при активном CLOSED возвращает NONE даже при запаздывающем текущем состоянии 2/3/unknown.

Точная причинная цепочка двух наблюдавшихся запусков не доказана: исходный FoldDecider также должен был подавлять одинаковое успешно применённое решение. По утраченному журналу нельзя установить дополнительный триггер сброса/смены решения. Устранено найденное окно и заменён учёт direct; отсутствие двойных запусков необходимо подтвердить новым timeline на устройстве.

## Что живёт в direct

| Объект/задача | В обычном покое | Обоснование |
| --- | --- | --- |
| Main looper | да | Android callbacks и UI |
| FoldLidShim-worker HandlerThread | спит | сериализация датчика, решений и результатов |
| hinge listener on-change | да | единственный источник немедленной реакции, samplingPeriod=0, без wake lock |
| Второй hinge listener | только с альтернативным управляющим источником | диагностика настоящего шарнира |
| DeviceStateCallback | зарегистрирован, main executor | актуальное состояние без опроса |
| ShimState listener службы | да | отмена по callback и публикация состояния; снимается в onDestroy |
| Accessibility callback | зарегистрирован системой | только typeWindowStateChanged от SystemUI; allowed=false возвращает сразу |
| Журнал IO executor | поток умирает через 5 секунд | bounded queue 64, файл <=65536 байт |
| retryCancel postDelayed(1000) | нет | только незавершённое снятие на раскрытии/остановке/смене режима |
| pendingTimeout postDelayed(3000) | нет | только ожидающая StateActivity; не задерживает отправку |
| StateActivity focusTimeout postDelayed(3000) | нет | только живое окно без выполненной команды |
| Controller confirmation timeout postDelayed(5000) | нет | однократная страховка ожидающего диалога |
| UI tick postDelayed(200) | нет | только раскрытая диагностика и resumed |
| UI тест postDelayed(3000) | нет | только явная кнопка теста, гарантирует попытку снять тестовый CLOSED |
| UI/tile listeners | только пока экран/плитка видимы | снимаются в onStop/onStopListening/onDestroy |
| Tile executor | объекта без потока достаточно в direct | поток создаётся лишь задачей Shizuku; shutdownNow в onDestroy |
| ShizukuAutoStarter, reconcileTick, удержание FoldDecider | нет | direct их не запускает |
| :nudge | нет | код не менялся, только существующий автозапуск |

Постоянных таймеров в direct в покое нет. Таймер 30 секунд и holdRunnable доступны только ветке Shizuku. Команды для batterystats/meminfo/procstats и трактовка полей добавлены в README.

## Итоговый accessibility XML

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:packageNames="com.android.systemui"
    android:canRetrieveWindowContent="true"
    android:accessibilityFlags="flagReportViewIds"
    android:notificationTimeout="0"
    android:description="@string/accessibility_description" />
```

typeWindowStateChanged нужен для появления диалога. typeWindowContentChanged удалён: основной сценарий требует события появления окна, постоянные изменения содержимого не нужны. На устройстве нужно проверить, что Samsung отдаёт заголовок/кнопку уже при этом событии. flagReportViewIds оставлен для безопасного распознавания и button1. canRetrieveWindowContent нужен для поиска кнопки и запасного пути. flagRetrieveInteractiveWindows убран вместе с getWindows; используется rootInActiveWindow/event.source. feedbackGeneric - объявление вида сервиса, не добавляет голос/вибрацию. notificationTimeout=0 убирает пакетирование с задержкой 50 мс. Пакет, generation/windowId и ожидающее подтверждение проверяются перед кликом. Полного чтения других приложений и их логирования нет.

## Штатные приложения

| Сценарий | Действие direct |
| --- | --- |
| Сложен, наш CLOSED активен | NONE, фокус не перехватывается повторно; CLOSED переживает поздние 2/3 |
| Раскрытие | немедленный CANCEL; затем Samsung выбирает базовые 2/3 по углу; Flex без движения не создаёт CLOSED |
| Камера показывает внешний экран при раскрытии, чужой 4/5 | без нашего CLOSED всегда NONE; никакого OPENED/активити |
| Камера при сложенном телефоне, наш CLOSED ещё активен/приостановлен | NONE; не повторяем собственный запрос |
| Камера при сложенном телефоне, собственного запроса нет | таблица CLOSED: допустим один запрос закрытия по текущему решению; после исключения повторов нет до смены направления |
| Выключение службы | CANCEL в foreground-контексте, повторы при отказе, остановка после callback |
| Смена режима | сначала тот же CANCEL, затем ресурсы следующего режима |
| Смерть процесса | система обычно снимает, sticky может сохраниться; новый процесс при 0 и раскрытии один раз пробует снять, отказ виден |
| Два аварийных sticky-перезапуска за 10 минут | enabled=false, ручное включение обязательно |

## Тесты и проверки

Новые JUnit в DirectMotionTest: быстрое закрытие и сохранение раннего CLOSED; медленное закрытие; Flex/остановка на 90°; дрожание 9-15° и около раннего порога 45°; закрытие с остановкой и продолжением; исключение CLOSED до смены направления; выключенная ранняя настройка и устаревшее время; повтор cancel через секунду, остановка после подтверждения/ниже open; границы десятиминутного предохранителя; подавление повторного CLOSED при активном запросе.

DirectPolicyTest переписан: открытие при null/0/1/4/5 теперь NONE, остальные проверки сохранены. ConfirmButtonPickerTest переписан на точное дерево Samsung с FrameLayout, ScrollView, TextView, buttonPanel, button2, button1; добавлены отсутствие button1, чужой AlertDialog, английский заголовок по ID; сохранены проверки legacy wrapper, пустой кнопки и только отмены.

- `farm-check -C . --net -- /cache/android/with-android ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` - код 0: testDebugUnitTest, lintDebug и assembleDebug пройдены, BUILD SUCCESSFUL.
- `farm-check -C . -- shellcheck build.sh` - код 0.
- XML-ресурсы разобраны: notificationTimeout=0, единственный тип события и флаг службы спецвозможностей, setFlags(0), requestOpen отсутствует.
- `git diff --check` - код 0.

## Проверить на устройстве

- Быстро раскрывать сразу после CLOSED, многократно: нет system_server_crash, callback отмены приходит.
- Отказ/потеря фокуса/таймаут отмены: повторы не чаще раза в секунду, прекращение при подтверждении и возврате ниже open.
- Выключение, смена режима, принудительное открытие и плитка: запрос снят; ручное снятие не закрывается заново.
- Восстановление sticky после смерти процесса: примет ли система cancel от нового pid; при отказе корректный статус и отсутствие повторного перехвата фокуса.
- Два null-intent старта: выключение и сохранение предохранителя после перезапуска процесса; ручное включение очищает.
- Закрытый заблокированный телефон: resumed/фокус, setShowWhenLocked, setTurnScreenOn и возможность клика.
- Диалог Samsung доступен на typeWindowStateChanged без contentChanged; русская/английская локаль, ручной отказ, два быстрых разных запроса.
- По timeline измерить каждый этап, проверить ранний порог на реальном SensorEvent.timestamp, медленное складывание, Flex и дрожание.
- По timeline/ActivityTaskManager подтвердить отсутствие второго StateActivity и новых отдельных fullscreen-задач на каждом действии; смена ориентации и запуск с boot без MainActivity.
- Камера в раскрытом 4/5 и сложенном состоянии; не отбирается ли фокус без необходимого действия.
- Долгий покой: CPU, wakeups, PSS, потоки, отсутствие :nudge и подключения Shizuku в direct, в том числе после смены режима.
- Кольцевое заполнение timeline.log, просмотр UI и run-as; сохранность последних строк после аварии.
