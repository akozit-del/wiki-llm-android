# Недельный обзор

Что вышло за неделю в малых открытых моделях, в Hexagon-бэкенде llama.cpp и в
данных ZIM — и стоит ли что-то менять у нас. Обзор и рекомендация, не спринт:
код тут не трогается. Новые записи сверху.

Текущие числа для сравнения — в `benchmark/LATEST.md`, ограничения платформы —
в `CLAUDE.md`.

---

## Неделя до 2026-10-25

Окно — 10-18…10-25. Тихая неделя: новых плотных моделей ≤4B нет, русских тоже
нет, ZIM не обновлялся. Единственная модель в нашем классе — NVIDIA
Nemotron-3.5-Bolt-E4B, но это дообученная Gemma 4 E4B, то есть кандидат
только для S26, причём там Gemma 4 E4B у нас самих ещё не мерена. В Hexagon-бэкенде
вышло 10 коммитов. Нашего падения на v73 ни один не касается напрямую, но
переписаны ровно те части, где оно сидит: перепаковка весов (#30901) и
слияние операций/батчинг (#30843). Кроме того, открыт PR #30934, который
чинит повторный вход в `flush_pending` — функцию, в которой у нас
вызывается `ggml_abort`.

### Что вышло

| Что | Размер / тип | Дата | На телефон? | Почему |
|---|---|---|---|---|
| [NVIDIA-Nemotron-3.5-Bolt-E4B](https://huggingface.co/nvidia/NVIDIA-Nemotron-3.5-Bolt-E4B-BF16) ([GGUF ggml-org](https://huggingface.co/ggml-org/NVIDIA-Nemotron-3.5-Bolt-E4B-GGUF), [Q4_K_M от NVIDIA](https://huggingface.co/nvidia/NVIDIA-Nemotron-3.5-Bolt-E4B-4GB)) | 8B всего / 4B эффективных, архитектура Gemma-4-E4B (дообучение `gemma-4-E4B-it`), `ru` в списке языков, OpenMDW-1.1 | анонс 10-15, веса и GGUF 10-23 | **Надо мерить, только на S26 и после самой Gemma 4 E4B** | Архитектура та же, что у Gemma 4 E4B, а её Q4_0 весит 4.6 ГБ: на S23 не влезет (~2.5 ГБ под модель). Q4_0 нет ни у NVIDIA (только Q4_K_M), ни у ggml-org (BF16/Q8_0), так что квантовать придётся самим из BF16 GGUF. NVIDIA заявляет улучшение «input grounding», а для RAG это ровно то, что нужно. Но меряли это не на русском, и сравнивать имеет смысл только с базовой Gemma 4 E4B, которую мы тоже не прогоняли |
| [tencent/WeVisOne-VL-2B / 4B](https://huggingface.co/tencent/WeVisOne-VL-4B) | 2B / 4B, vision-language | 10-22 | Нет | Модели для зрения, не текстовые генераторы для RAG |
| Прочее | LFM2.5-24B-A2B-DSpark (Liquid, 10-19), granite-switch-4.1 8B/30B GGUF (IBM, 10-22), Recursive Flint 39.8B-A2.5B MoE для кода (GGUF ggml-org 10-22), MiniMax-M3.1-Flash, Ornith-1.5-122B | 10-19…10-23 | Нет | Всё MoE или больше бюджета в разы. У Flint 2.5B активных параметров, но 39.8B весов в памяти |
| Русские малые модели | — | 10-18…10-25 | — | По HF API у Vikhrmodels, RefalMachine, t-tech, ai-sage, yandex, IlyaGusev за неделю ничего нового, поиск по HF и веб-поиск тоже пустые. Qwen 4 — по-прежнему анонс без весов |

### Рантайм и данные

llama.cpp: мастер на 10-25 — **b11871** (`b3fa6995`). Hexagon-коммиты после
10-18:
- [#30901](https://github.com/ggml-org/llama.cpp/pull/30901) `0efe8e4c`, 10-21:
  перепаковка весов (tiling) векторизована на NEON и распараллелена,
  результат побитово тот же. Это быстрее загрузка, ускорения на токен нет. Для
  нас важно другое: на v73 у нас всё сломалось на коммите, который ввёл
  перепаковку (#28994), а этот код переписан уже второй раз после #30635.
- [#30843](https://github.com/ggml-org/llama.cpp/pull/30843) `643b0cda`, 10-23
  (max-krasnyansky): рефакторинг слияния операций и диспатча. Добавлены новые
  слияния (GELU+MUL, цепочки BINARY_NX, RMS_NORM+MUL, SSM_CONV+SILU), упрощён
  батчинг операций, большой scratch FA перенесён со стека потока в DDR. Для
  Qwen3.5 на S26 полезен SSM_CONV+SILU. Для нашего v73-падения важно, что
  переписан батчинг, а DSP у нас умирает именно на границе батчей.
- [#31055](https://github.com/ggml-org/llama.cpp/pull/31055) `78a8ae14`, 10-24:
  разбиение HMX Flash Attention по командам KV-голов, когда `n_kv_heads` не
  делится на число ядер. На gemma-4-E4B prefill +15 %, на Qwen3.5-0.8B +4.7 %.
  Флаг `GGML_HEXAGON_FA_SPLIT`.
- [#30731](https://github.com/ggml-org/llama.cpp/pull/30731) `31288c9c`, 10-19:
  улучшения FA для длинного контекста и SWA (был открыт в прошлом обзоре,
  теперь влит); [#30218](https://github.com/ggml-org/llama.cpp/pull/30218)
  `fe8841a7`, 10-19 — multistream FA.
- [#30854](https://github.com/ggml-org/llama.cpp/pull/30854) `84663953`, 10-20:
  snapdragon-toolchain v0.8 и **поддержка v85** (следующее поколение после
  v81). Если обновлять пин, CI может потребовать новый тулчейн.
- Мелкие: CEIL ([#31006](https://github.com/ggml-org/llama.cpp/pull/31006)),
  SGN ([#30346](https://github.com/ggml-org/llama.cpp/pull/30346)), фикс
  запроса VTCM ([#30655](https://github.com/ggml-org/llama.cpp/pull/30655)),
  `MUL_MAT_ID -1` ([#26631](https://github.com/ggml-org/llama.cpp/pull/26631)).
- **Открытые и для нас важные:**
  - [#30934](https://github.com/ggml-org/llama.cpp/pull/30934) (10-21):
    гонки в батчинге операций и в кэше узлов графа. Если в одной сессии два
    контекста, путь отказа в `flush_batch` повторно входит в `flush_pending`
    (assert по `response-seq`). Наш `ggml_abort` тоже в `flush_pending`, но
    падали мы с одним контекстом и mtp=0, так что совпадение скорее по месту,
    чем по причине. Если влиют до краш-зонда — бесплатная проверка.
  - [#30534](https://github.com/ggml-org/llama.cpp/pull/30534) (10-14):
    `GGML_HEXAGON_VMEM=0` неверно оценивает доступную память на v73. Причина —
    выравнивание IOVA до степени двойки в 32-битном DMA-окне. Это та же область,
    что и в [#30720](https://github.com/ggml-org/llama.cpp/issues/30720)
    (порча данных в больших маппингах на v73). #30720 так и висит без ответа.
  - [#30952](https://github.com/ggml-org/llama.cpp/pull/30952): 64-битный
    FastRPC-хендл обрезается при закрытии. Касается только закрытия сессии.
  - [#29740](https://github.com/ggml-org/llama.cpp/pull/29740): целочисленный
    HMX для v73 без FP16 HMX (бюджетные SoC с 4 МБ VTCM). S23 — это 8 Gen 2
    с полным HMX, так что нас не касается.
- MTP/speculative: в Hexagon за неделю ничего. Ядро Gated DeltaNet уже в
  апстриме (с 09-21), новых изменений по нему нет, кроме слияния SSM_CONV+SILU.

**Данные:** без изменений. Русская Википедия: последняя — `nopic_2026-10`
(10-14, 17 ГБ в листинге) и `maxi_2026-10` (10-12). Английская: `maxi_2026-08`,
`nopic_2026-06`. libkiwix 14.2.1 (05-11) и libzim 9.8.2 (08-13) — новых
релизов нет.

**Эмбеддеры/реранкеры:** новых малых за неделю не нашлось.

### Стоит ли что-то менять у нас

**1. Да, та же рекомендация, что 10-18: retrieval-probe на
`ru_all_nopic_2026-10`.** На этой неделе ничего не случилось, что поменяло бы
её цену или отдачу. Ответы станут на 9 месяцев свежее, модель для прогона не
нужна, ground truth от снапшота не зависит. Цена — около 1.5–2 ч, в основном
на перенос 17–18 ГБ на телефон. Риск — только потраченное время.

**2. Да: краш-зонд мастера на S23, но новой сборкой b11871, а не APK от
10-16.** За неделю переписали и перепаковку (#30901), и батчинг операций
(#30843), то есть обе подозрительные области. Поэтому зонд на свежем мастере
информативнее старого APK. Цена: одна CI-сборка (`llama_ref=b3fa6995`,
тулчейн обновился до v0.8, так что CI может потребовать правку) плюс один
вопрос через BenchmarkReceiver и logcat, около 15 минут на телефоне. Если
мастер выживет, гоним 13 вопросов на холодном S23. Если упадёт, отправляем
issue (черновик готов с 09-29) со ссылками на #30720, #30534 и #30934.

Не делать сейчас: Nemotron-3.5-Bolt-E4B. Её разумно мерить только после
базовой Gemma 4 E4B на S26, которая стоит в очереди с сентября. На S23 эта
архитектура не влезает в память.

### Что из прошлых рекомендаций сделано

Ничего. С 10-18 в `git log` только `af20add` (сам прошлый обзор). Probe на
`ru_all_nopic_2026-10` не прогонялся, краш-зонд APK от 10-16 на S23 не сделан,
issue в llama.cpp не отправлен. Последняя запись в `benchmark/LATEST.md`
по-прежнему от 09-29.

### Источники

- llama.cpp: [коммиты ggml-hexagon](https://github.com/ggml-org/llama.cpp/commits/master/ggml/src/ggml-hexagon), [релиз b11871](https://github.com/ggml-org/llama.cpp/releases/tag/b11871), [#30901](https://github.com/ggml-org/llama.cpp/pull/30901), [#30843](https://github.com/ggml-org/llama.cpp/pull/30843), [#31055](https://github.com/ggml-org/llama.cpp/pull/31055), [#30731](https://github.com/ggml-org/llama.cpp/pull/30731), [#30218](https://github.com/ggml-org/llama.cpp/pull/30218), [#30854](https://github.com/ggml-org/llama.cpp/pull/30854), [#31006](https://github.com/ggml-org/llama.cpp/pull/31006), [#30346](https://github.com/ggml-org/llama.cpp/pull/30346), [#30655](https://github.com/ggml-org/llama.cpp/pull/30655), [#26631](https://github.com/ggml-org/llama.cpp/pull/26631), [#30934](https://github.com/ggml-org/llama.cpp/pull/30934), [#30534](https://github.com/ggml-org/llama.cpp/pull/30534), [#30952](https://github.com/ggml-org/llama.cpp/pull/30952), [#29740](https://github.com/ggml-org/llama.cpp/pull/29740), [#30720](https://github.com/ggml-org/llama.cpp/issues/30720), [#29473](https://github.com/ggml-org/llama.cpp/issues/29473)
- Модели: [Nemotron-3.5-Bolt-E4B-BF16](https://huggingface.co/nvidia/NVIDIA-Nemotron-3.5-Bolt-E4B-BF16), [Bolt-E4B-4GB (Q4_K_M)](https://huggingface.co/nvidia/NVIDIA-Nemotron-3.5-Bolt-E4B-4GB), [ggml-org GGUF](https://huggingface.co/ggml-org/NVIDIA-Nemotron-3.5-Bolt-E4B-GGUF), [HPCwire, анонс 10-15](https://www.hpcwire.com/aiwire/2026/10/15/nvidia-expands-nemotron-family-with-highest-efficiency-super-vl-and-bolt-models/), [WeVisOne-VL-4B](https://huggingface.co/tencent/WeVisOne-VL-4B), [RecursiveSI/flint](https://huggingface.co/RecursiveSI/flint), [LFM2.5-24B-A2B-DSpark](https://huggingface.co/LiquidAI/LFM2.5-24B-A2B-DSpark)
- Qwen 4: [yottalabs — что известно](https://www.yottalabs.ai/post/qwen-4-release-date-what-is-known-how-to-prepare-2026), [aitoolsreview](https://aitoolsreview.co.uk/insights/qwen-4) — весов нет
- Данные: [download.kiwix.org/zim/wikipedia](https://download.kiwix.org/zim/wikipedia/), [libkiwix releases](https://github.com/kiwix/libkiwix/releases), [libzim releases](https://github.com/openzim/libzim/releases)

## Неделя до 2026-10-18

Окно — 10-11…10-18. Период 10-11…10-16 уже разобран в дополнении ниже,
здесь повторяется только то, что важно для вывода. Главная новость недели —
не модель, а данные: вышел **новый снапшот русской Википедии `2026-10`**,
первый после нашего `2026-01`. В рантайме Q1_0/Q2_0 стали нативными на NPU,
и это делает тернарную Qwen3.5-4B (Bonsai 2) запускаемой на Hexagon. Плюс
появилось второе свежее v73-issue с мусором на выходе.

### Что вышло

| Что | Размер / тип | Дата | На телефон? | Почему |
|---|---|---|---|---|
| [NVIDIA Nemotron-Labs Dory1.0-4B](https://huggingface.co/nvidia/NVIDIA-Nemotron-Labs-Dory1.0-4B-BF16) ([GGUF](https://huggingface.co/nvidia/NVIDIA-Nemotron-Labs-Dory1.0-4B-GGUF)) | 4B, looped nGPT (общий рекуррентный блок гоняется 2 раза, 72 слоя), SWA + full attention, OpenMDW-1.1 | 10-16 | **Нет** | Только английский, GGUF только Q5_K_M (3.7 ГБ). Цикл по слоям увеличивает вычисления на токен, а не уменьшает |
| [Ternary-Bonsai-2-4B (PrismML)](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf) | 4.2B, это Qwen3.5-4B с тернарными весами (гибрид 24 linear + 8 full attention), Apache 2.0 | веса 09-23, **NPU-ядра 10-17/10-18** | **Надо мерить, только на S26 и не сейчас** | Веса старые, новый факт — апстримный `Q2_0` (1.21 ГБ против ~2.5 ГБ Q4_0) теперь считается на HTP нативно, см. рантайм. На S23 нереально: гибрид + мастер падает. Качество тернарной модели на русском RAG никто не мерил, а сами авторы пишут, что выигрыш от меньшего DDR-трафика ещё не выбран |
| Прочее за 10-16…10-18 | DeepSeek-V4.1-Flash-1017, Muse-Spark-Open 816B, Beam-1.0 501B, Kolibri-1 GGUF, Hy-Memory-30B-A3B, Nemotron-3.5-Rerank-8B | 10-15…10-18 | Нет | Все на порядок больше бюджета. Реранкер 8B на месте mE5-small (118M) не нужен |
| Русские малые модели | — | 10-11…10-18 | — | По HF API у Vikhrmodels, RefalMachine, t-tech, ai-sage, yandex, IlyaGusev за неделю ничего нового. Веб-поиск тоже ничего не дал. Qwen 4 — по-прежнему без весов |

### Рантайм и данные

llama.cpp: мастер на 10-18 — **b11718**. Hexagon-коммиты с 10-16 (более
ранние перечислены в дополнении ниже):
- [#30122](https://github.com/ggml-org/llama.cpp/pull/30122) `797de63b`, 10-17:
  **Q1_0 и Q2_0 нативно на NPU.** MUL_MAT через HVX и в 2D-пути HMX, с
  tiled-раскладкой. Сборки v73/v75/v79/v81 проходят, проверено на SM8850/v81.
- [#30627](https://github.com/ggml-org/llama.cpp/pull/30627) `848bbda9`, 10-18:
  Hadamard MUL_MAT и GET_ROWS для Q1_0/Q2_0. Автор пишет, что после него
  Bonsai-2 Q1/Q2 «полностью работают», но выигрыш по DDR ещё требует
  оптимизации.
- [#30581](https://github.com/ggml-org/llama.cpp/pull/30581) `ceefd16d`, 10-16:
  исправлены sinks в HMX Flash Attention (было 63 падения test-backend-ops).
  Касается gpt-oss-подобных моделей, у наших моделей sinks нет.
- [#30435](https://github.com/ggml-org/llama.cpp/pull/30435) `27c7a769`, 10-16:
  softplus на HVX. Это часть гейта DeltaNet, так что Qwen3.5 на S26 получит
  немного.
- Открытые: [#30731](https://github.com/ggml-org/llama.cpp/pull/30731)
  (max-krasnyansky, 10-17) — FA пропускает замаскированные блоки и точнее
  переключает HMX/HVX. Декод выигрывает при больших ubatch (у нас 1024), но
  заметно в основном на контексте больше 8K.
  [#30655](https://github.com/ggml-org/llama.cpp/pull/30655) — запрос размера
  VTCM на автомобильных cDSP, к нам не относится.
- **[#30720](https://github.com/ggml-org/llama.cpp/issues/30720) (открыт,
  10-17): v73, SM7550 (Snapdragon 7 Gen 3).** Qwen2.5-1.5B Q4_0 при полном
  оффлоаде даёт детерминированный мусор, а при 1/29 слоях на HTP отвечает
  правильно. Мусор остаётся, даже если OPFILTER переносит **все** операции на
  CPU, а веса лежат в HTP-буферах. Автор подозревает порчу данных в больших
  маппингах (окно IOVA, `DMA_BIT_MASK(32)`, ссылается на #26123). Это не наш
  случай один в один: у нас на мастере не мусор, а падение DSP, и устройство
  другое (8 Gen 2). Но это уже третий отчёт о проблемах v73 после 09-16, вместе
  с #29473 и нашим. И это та же область, что и наш «мусор в 1 из 10» на пине.
  Ссылку стоит вписать в черновик issue.

**Данные: новый снапшот русской Википедии.**
[`wikipedia_ru_all_nopic_2026-10.zim`](https://download.kiwix.org/zim/wikipedia/wikipedia_ru_all_nopic_2026-10.zim)
выложен 10-14, его размер **18.4 ГБ** против 14.7 ГБ у нашего `2026-01`
(+26 %). `maxi_2026-10` выложен 10-12, 37.1 ГБ. Почему файл вырос на четверть
за 9 месяцев, неизвестно. Возможно, это рост контента, возможно, изменился
состав или сжатие. Проверять это надо на устройстве: дольше ли открывается
файл и не медленнее ли поиск. Английская Википедия не обновилась: maxi
2026-08, nopic 2026-06. libkiwix 14.2.1 (05-11) и libzim 9.8.2 (08-13) без
изменений.

### Стоит ли что-то менять у нас

**1. Да: прогнать retrieval-бенчмарк на `ru_all_nopic_2026-10`, и если
recall не просел — перейти на этот снапшот.** Ground truth в бенчмарке —
статья и поле инфобокса, а не значение. Поэтому `run_probe.sh` на новом файле
сравним с текущими recall@3 97 % / fast path 93–100 % без пересборки
приложения, модель для этого не нужна. Выигрыш — ответы на 9 месяцев свежее.
Заодно станет видно, жив ли редирект «Пенициллин → Бензилпенициллин» (`pr03`).
Цена: скачать 18.4 ГБ, скопировать на S23, где нужно столько же свободного
места, и один прогон probe на 32 вопроса. Всего часа 1.5–2, большая часть —
передача файла. Риск — только время: старый ZIM остаётся на месте.

**2. Остаётся с 10-11: краш-зонд мастера на S23 — APK уже собран.** CI-run
[38934391684](https://github.com/akozit-del/wiki-llm-android/actions/runs/38934391684)
от 10-16 (мастер `7182ef71`, в нём перепаковка #30635) прошёл зелёным, но на
телефоне не проверялся. Цена — один вопрос через BenchmarkReceiver и logcat,
около 15 минут. Если выживет — 13 вопросов на холодном S23. Если упадёт —
issue получает ещё одну строку («#29785, #30067 и #30635 не помогли») и
ссылку на #30720.

Не делать: Bonsai-2 Q2_0 на S26 — ядра вышли вчера, автор сам пишет, что
скорость ещё не выбрана, а Qwen3.5-4B Q4_0 на S26 и так 10.9 с на ход. Dory —
нет русского. Новый реранкер — retrieval не узкое место.

### Что из прошлых рекомендаций сделано

С прошлого обзора коммитов, кроме документов, не было: только `1fac57a`
(обзор до 10-11) и `56c58c9` (дополнение до 10-16). Рекомендацию 10-11
выполнили наполовину: сборку мастера `7182ef71` запустили в CI 10-16 (run
38934391684, success). Краш-зонд на S23 не сделан, прогон на S26 тоже — в
`benchmark/LATEST.md` последняя запись от 09-29. Issue в llama.cpp
по-прежнему не отправлен.

### Источники

- llama.cpp: [коммиты ggml-hexagon](https://github.com/ggml-org/llama.cpp/commits/master/ggml/src/ggml-hexagon), [релиз b11718](https://github.com/ggml-org/llama.cpp/releases/tag/b11718), [#30122](https://github.com/ggml-org/llama.cpp/pull/30122), [#30627](https://github.com/ggml-org/llama.cpp/pull/30627), [#30581](https://github.com/ggml-org/llama.cpp/pull/30581), [#30435](https://github.com/ggml-org/llama.cpp/pull/30435), [#30731](https://github.com/ggml-org/llama.cpp/pull/30731), [#30655](https://github.com/ggml-org/llama.cpp/pull/30655), [#30720](https://github.com/ggml-org/llama.cpp/issues/30720)
- Модели: [Dory1.0-4B](https://huggingface.co/nvidia/NVIDIA-Nemotron-Labs-Dory1.0-4B-BF16), [Dory GGUF](https://huggingface.co/nvidia/NVIDIA-Nemotron-Labs-Dory1.0-4B-GGUF), [Ternary-Bonsai-2-4B-gguf](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf), [список моделей HF API по авторам](https://huggingface.co/api/models?author=nvidia&sort=createdAt&direction=-1)
- Данные: [download.kiwix.org/zim/wikipedia](https://download.kiwix.org/zim/wikipedia/), [каталог OPDS](https://library.kiwix.org/catalog/v2/entries?lang=rus&category=wikipedia), [libkiwix releases](https://github.com/kiwix/libkiwix/releases), [libzim releases](https://github.com/openzim/libzim/releases)
- Поиск по русским моделям ничего не дал: [SiliconFlow, обзор 2026](https://www.siliconflow.com/ru/articles/best-open-source-llm-for-russian) (обзор без новых релизов)

## Дополнение до 2026-10-16 (интерактивно, обзор 10-11 уже есть ниже)

**Модели:** снова ничего для телефона. За 10-11…10-16 вышли LFM2.5-24B-A2B
(Liquid, 24B MoE — велико), granite-switch-4.1 8B/30B (IBM), MiniCPM-V-4.7-1B
(OpenBMB, 1B, зрение — не чат-модель для RAG), Microsoft UserRepGen-4B
(узкая задача). Закрытые: Claude Haiku 5.5, Mistral Large 4, Grok Voice 3.0.
Qwen 4 на 10-16 по-прежнему только анонс, весов нет. Русских малых — нет.

**Рантайм:** мастер `7182ef71` (10-16). В Hexagon после 10-10 — 7 коммитов,
главный для нас — [#30635](https://github.com/ggml-org/llama.cpp/pull/30635)
`b797b9ca` (10-16), **переделка перепаковки весов**: все 2D-веса теперь
переупаковываются в tiled-формат при загрузке, новый
`GGML_HEXAGON_RT_REPACK` (0/1/2). Перепаковку ввёл ровно тот коммит
#28994, после которого на S23 умирает DSP, — так что это уже третий
кандидат на «случайно починили» вместе с #29785 (гонка work-queue) и
#30067 (L2-флаши при копиях). Ни один из них v73/S23 не упоминает. Плюс
[#30423](https://github.com/ggml-org/llama.cpp/pull/30423) — настраиваемый
режим питания DCVS/HMX (10-13), [#30341](https://github.com/ggml-org/llama.cpp/pull/30341)
— ещё один раунд ускорения копий (10-12).

**Рекомендация:** та же, что 10-11, только сильнее — одна сборка мастера и
один краш-зонд на S23; если живёт, 13 вопросов на холодном S23 и тот же APK
на S26. Сборка `7182ef71` запущена в CI 10-16 заранее.

## Неделя до 2026-10-11

Обзора до 10-04 в файле нет (не запустился), поэтому окно этой записи — с 09-29,
когда закончился прошлый спринт. Моделей для нас снова нет. В рантайме за 12
дней в Hexagon-бэкенд легло 17 коммитов, и два из них бьют ровно в ту
область, где на S23 умирает DSP: очередь задач и L2-флаши при копиях.
Этого достаточно, чтобы ещё раз проверить мастер на падение. Чинили ли они
именно наш случай — никто не утверждал.

### Что вышло

| Что | Размер / тип | Дата | На телефон? | Почему |
|---|---|---|---|---|
| Ничего нового плотного ≤4B, в том числе русского | — | 09-29…10-11 | — | По HF API проверены Qwen, google, meta-llama, microsoft, HuggingFaceTB, openbmb, mistralai, allenai, ibm-granite, LiquidAI, deepseek-ai, nvidia, tencent, zai-org, а из русских Vikhrmodels, RefalMachine, t-tech, ai-sage, yandex, IlyaGusev: новых чат-весов в классе нет. QVikhr-3-4B остаётся рабочей моделью |
| [LiquidAI/d1-3B](https://huggingface.co/LiquidAI/d1-3B) ([GGUF](https://huggingface.co/LiquidAI/d1-3B-GGUF)) | 3B, на LFM2.5-VL-3B (гибрид conv + attention), лицензия lfm1.0, `ru` в списке языков | 10-05 / 10-06 | **Нет** | Это «decision model»: отдаёт калиброванные ответы на заданные вопросы за один проход, без генерации токенов. Не чат-модель и не генератор ответа. Плюс гибридная архитектура, а GGUF только в Q4_K_M/Q8_0/F16, без Q4_0 |
| [google/embeddinggemma-2](https://huggingface.co/google/embeddinggemma-2) ([GGUF ggml-org](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF)) | эмбеддер, 270M текстовая часть (130M трансформер + 140M эмбеддинги), 740M с модальностями; 100+ языков, MRL 128–768, Apache 2.0 | репо 09-14, публичный релиз и GGUF 10-06 | **Надо мерить, но не сейчас** | Кандидат на замену mE5-small (118M) в реранке. Однако текстовая часть больше в 2.3 раза, а словарь на 262k удлиняет загрузку. GGUF есть только BF16/Q8_0. Рерank у нас не узкое место (retrieval ~8 % хода, recall@3 97 %), так что выигрыш пока не на что тратить |
| [Mellum2.1 Thinking (JetBrains)](https://www.llm-releases.com/) | ~12B MoE, ~2.5B активных, код | 10-08 | Нет | Кодовая модель и MoE больше бюджета памяти |
| [Kolibri-1 (Aleph Alpha)](https://www.llm-releases.com/), [GLM-5.3-Flash](https://huggingface.co/ggml-org/GLM-5.3-Flash-GGUF), MiMo-V2.6-Pro | крупные MoE (Kolibri 78B) | 10-02…10-03 | Нет | Размер на порядок больше. У GLM-5.3-Flash GGUF двухчастный даже в Q2_K |

Вывод по моделям: пусто третью неделю подряд. Qwen 4 по-прежнему только
анонсирован (см. запись до 09-29).

### Рантайм и данные

llama.cpp: мастер на 10-10 — **b11552**. В `ggml/src/ggml-hexagon` с 09-29
вошло 17 коммитов. Ниже они по значимости для нас.

**Что может касаться падения на v73 (S23):**
- [#29785](https://github.com/ggml-org/llama.cpp/pull/29785) `3aa0ce9b`, 10-01:
  **гонка в work-queue исправлена по-настоящему.** Это «better fix» для #29606,
  чей черновик мы проверили 09-29 и который не помог. Причина в том, что
  `seqn` служил и futex-ом, и счётчиком публикации задачи, и одна задача могла
  выполниться дважды. Наше падение («батч #1 прошёл, DSP умер через 1 мс после
  старта батча #2») по форме похоже на такую гонку. Совпадение — гипотеза,
  а не факт.
- [#30067](https://github.com/ggml-org/llama.cpp/pull/30067) `c479922a`, 10-06:
  все CPY/CONCAT/CONT/DUP переведены на DMA/HVX. Это ответ на
  [#29977](https://github.com/ggml-org/llama.cpp/pull/29977) (закрыт без
  слияния): `dccleaninva` с шагом 128 байт оставлял устаревшие данные в L2
  после переиспользования DMA-буфера. Автор #29977 мерил это на v81 и
  Qwen3.5-4B, а у нас падает и Q4_0-QVikhr. Всё равно это второй кандидат.
- [#29473](https://github.com/ggml-org/llama.cpp/issues/29473) (открыт): на
  SM7750 (v73) нет FP16 HMX и всего 4 МБ VTCM. Мейнтейнер признал, что
  «v73» не равно «HMX FP16». Однако S23 (8 Gen 2) падает и с
  `GGML_HEXAGON_NHMX=0`, так что это другой случай. Решение для тех
  устройств — [#29740](https://github.com/ggml-org/llama.cpp/pull/29740)
  (целочисленный HMX для Q4_0, открыт).
- Новый факт: в [#29717](https://github.com/ggml-org/llama.cpp/pull/29717)
  (10-01) мастер **работает на другом v73**, IQ-9075: Llama-3.2-1B Q4_0
  1530 / 44.7 t/s, Qwen3.5-2B Q4_0 815 / 24.4. Значит, падение после #28994
  не общее для всего v73, а частное: S23, его прошивка cDSP или наш путь
  загрузки. Этот аргумент стоит вписать в issue.

**Скорость (в основном v75/v81):**
- [#29974](https://github.com/ggml-org/llama.cpp/pull/29974) `8345f333`, 10-05:
  Flash Attention режется по KV-головам между ядрами, плюс HMX-гейтинг для FA и
  F16-активации в HMX-матмуле. Prefill **до +58 %** (Qwen3-0.6B, S24U/v75).
  Для S26 на мастере это бесплатный плюс к и без того хорошему prefill.
- [#29971](https://github.com/ggml-org/llama.cpp/pull/29971) `5e03bdd8`, 10-06:
  SSM_CONV примерно в 2× быстрее на prefill (24–28 → 44–50 GB/s,
  test-backend-ops, v81). Это ядро Qwen3.5/LFM2, на S26 даст ещё немного.
- [#30126](https://github.com/ggml-org/llama.cpp/pull/30126) `9c2e0e49`, 10-08:
  `alloc_buffer_n`. Веса и KV собраны в один статический буфер, динамический
  по умолчанию уменьшен с 1 ГБ до 512 МБ, `GGML_HEXAGON_MBUF` теперь
  `dynamic[,static,total]`. Меньше маппингов на opbatch. На нашем лимите
  «~3.5 ГБ на сессию» может освободить место, но это не измерено.
- [#29717](https://github.com/ggml-org/llama.cpp/pull/29717) 10-01: Q2_K/Q3_K на
  NPU; [#30115](https://github.com/ggml-org/llama.cpp/pull/30115),
  [#30121](https://github.com/ggml-org/llama.cpp/pull/30121) 10-08: tiled
  GET_ROWS и ускоренная деквантизация Q6_K. Правило «нативный Q4_0» не
  меняется: K-кванты на NPU по-прежнему идут через перепаковку.
- Мелочи: GELU/GEGLU_ERF и pool ([#29631](https://github.com/ggml-org/llama.cpp/pull/29631),
  [#29995](https://github.com/ggml-org/llama.cpp/pull/29995)), F16-активации
  ([#29209](https://github.com/ggml-org/llama.cpp/pull/29209)), ALLREDUCE
  ([#29757](https://github.com/ggml-org/llama.cpp/pull/29757), multi-NPU, не наш случай),
  пересобранные HTP-skel ([#29828](https://github.com/ggml-org/llama.cpp/pull/29828)).

**Данные:** новых снапшотов русской Википедии нет. В каталоге Kiwix
`wikipedia_ru_all` nopic по-прежнему **2026-01** (наш), mini — 2026-07,
maxi — 2026-02. У английской: maxi 2026-08, nopic 2026-06; за неделю
выходили только `*_top_*` по другим языкам. libkiwix 14.2.1 (05-11) и libzim
9.8.2 (08-13) — без изменений.

### Стоит ли что-то менять у нас

**1. Да: один краш-зонд текущего мастера (≥ b11552) на S23. Если выживет —
тот же APK меряем на S26.** #29785 исправляет гонку очереди задач, #30067
переделывает L2-флаши при копиях, и обе правки попадают в окно «DSP умирает
на старте батча #2». Плюс есть доказательство, что мастер живёт на другом
v73 (IQ-9075). Цена — одна CI-сборка (`llama_ref=<master>`, `patchset=default`)
и один вопрос через BenchmarkReceiver: около 40 минут, риск нулевой. Если S23
выживет, два пина сводятся в один, а Qwen3.5-4B (0/13 мусора) становится
кандидатом и на S23. Тогда 13 модельных вопросов — на холодном телефоне,
не после пяти часов прогонов. Если не выживет, это готовый аргумент к issue
(«на IQ-9075 v73 живёт, на S23 нет; #29785 и #30067 не помогли»). Сам
черновик issue всё ещё ждёт «да» пользователя.

**2. Попутно тем же APK: обновить S26 и снять 13 вопросов.** #29974 (prefill
FA до +58 %) и #29971 (SSM_CONV ×2) должны ещё сократить 10.9 с у Qwen3.5-4B
на S26. Отдельной сборки не нужно, цена — один прогон (~30 мин).

Не делать: EmbeddingGemma 2 вместо mE5 (retrieval не узкое место, модель
в 2.3× больше), Q2_K/Q3_K (качество хуже Q4_0), d1-3B (не чат-модель).

### Что из прошлых рекомендаций сделано

Ничего: с 2026-09-29 коммитов в репозитории нет (последний — `608845e`,
S26-замер). Рекомендация «Qwen3.5-4B Q4_0 на мастере» выполнена ещё в тот же
день для S26 (4.6× на ход), а для S23 упёрлась в падение DSP. Issue в
llama.cpp с таблицей бисекта не отправлен. S26 по-прежнему на мастере
`fc07d781`, S23 — на main (`d222767c`).

### Источники

- [ggml-org/llama.cpp — коммиты ggml-hexagon](https://github.com/ggml-org/llama.cpp/commits/master/ggml/src/ggml-hexagon), [релиз b11552](https://github.com/ggml-org/llama.cpp/releases/tag/b11552)
- PR: [#29785](https://github.com/ggml-org/llama.cpp/pull/29785), [#30067](https://github.com/ggml-org/llama.cpp/pull/30067), [#29977](https://github.com/ggml-org/llama.cpp/pull/29977), [#29974](https://github.com/ggml-org/llama.cpp/pull/29974), [#29971](https://github.com/ggml-org/llama.cpp/pull/29971), [#30126](https://github.com/ggml-org/llama.cpp/pull/30126), [#29717](https://github.com/ggml-org/llama.cpp/pull/29717), [#30115](https://github.com/ggml-org/llama.cpp/pull/30115), [#30121](https://github.com/ggml-org/llama.cpp/pull/30121), [#29740](https://github.com/ggml-org/llama.cpp/pull/29740); issue [#29473](https://github.com/ggml-org/llama.cpp/issues/29473)
- Модели: [LiquidAI/d1-3B](https://huggingface.co/LiquidAI/d1-3B), [google/embeddinggemma-2](https://huggingface.co/google/embeddinggemma-2), [ggml-org/embeddinggemma-2-GGUF](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF), [ggml-org/GLM-5.3-Flash-GGUF](https://huggingface.co/ggml-org/GLM-5.3-Flash-GGUF), [llm-releases.com](https://www.llm-releases.com/)
- Данные: [Kiwix OPDS-каталог](https://library.kiwix.org/catalog/v2/entries?name=wikipedia_ru_all), [download.kiwix.org/zim/wikipedia](https://download.kiwix.org/zim/wikipedia/), [libkiwix releases](https://github.com/kiwix/libkiwix/releases), [libzim releases](https://github.com/openzim/libzim/releases)

## Неделя до 2026-09-29 (обзор 27-го не запустился — Mac спал; собран 29-го интерактивно)

Модели — снова пусто. Рантайм — за неделю Hexagon-бэкенд закрыл **все три
причины**, по которым Qwen3.5-4B был у нас «в 2 раза медленнее». Это меняет
ответ на вопрос «какую модель гонять», а не проценты.

### Что вышло

| Что | Размер / тип | Дата | На телефон? | Почему |
|---|---|---|---|---|
| Ничего нового ≤4B открытого | — | 09-21…09-29 | — | Ни одного релиза весов в классе; русскоязычных — тоже |
| Qwen 4 27B — **анонс**, не релиз (Apsara) | 27B | 09-22 | нет | Веса не опубликованы, даты нет; и 27B не наш размер |
| Claude Opus 5.5 / Sonnet 5.5, GPT-6 Luna/Sol, Grok 4.7, MiMo V2.6 | закрытые | 09-21…09-28 | нет | — |

### Рантайм и данные

llama.cpp, `ggml/src/ggml-hexagon`, 12 коммитов за неделю. По значимости для нас:

- [#29199](https://github.com/ggml-org/llama.cpp/pull/29199) `58367713`, 09-21 —
  **HMX-ядро GATED_DELTA_NET.** Qwen3.5-4B Q4_0 на Galaxy S26: prefill
  **615 → 1113 tok/s (1.8×)**, генерация без изменений. Тестировано на S26/S25/S24,
  IQ9, X2 Elite; про v73 (S23) отдельно не сказано — HMX у S23 есть (`hmx 1` в
  нашем логе загрузки), так что должно применяться, но не проверено.
- [#29123](https://github.com/ggml-org/llama.cpp/pull/29123) `4de09265`, 09-25 —
  **нативный Q5_K на HTP** (не перепаковка). Повод — в `unsloth/Qwen3.5-4B` «Q4_0»
  тензор `ssm_out` лежит в Q5_K и уезжал на CPU. Замер автора на Qwen3.5-4B:
  **212 / 11.2 → 375.9 / 12.2 tok/s** (pp / tg).
- [#28906](https://github.com/ggml-org/llama.cpp/pull/28906) 09-15, уже в прошлом
  обзоре — DMA-копии: **+30 % генерации на том же Qwen3.5-4B, 13.47 t/s на v81.**
- [#29197](https://github.com/ggml-org/llama.cpp/pull/29197) `0c3626ec`, 09-21 —
  64-битные маппинги буферов, **только v81+** (S26 — да, S23 — нет). Снимает
  map/unmap на каждом проходе для больших моделей (gemma-4-26B, gpt-oss-20b);
  для Gemma 4 E4B (4.6 ГБ Q4_0) на S26 — прямо в тему. Отключается
  `GGML_HEXAGON_DMA64=0`.
- [#29502](https://github.com/ggml-org/llama.cpp/pull/29502) `2b129ccf`, 09-27 —
  сэмплинг на NPU (ARGMAX/ARGSORT/TOP_K). **Автор сам пишет: заметного прироста
  нет**, это энергия и разгрузка CPU. Не наш рычаг.
- [#29395](https://github.com/ggml-org/llama.cpp/pull/29395) 09-24 — динамический
  квантизатор активаций: шкала на 32 значения вместо 128 — точность prefill,
  чисел нет. [#29282](https://github.com/ggml-org/llama.cpp/pull/29282),
  [#29404](https://github.com/ggml-org/llama.cpp/pull/29404),
  [#29511](https://github.com/ggml-org/llama.cpp/pull/29511) — DMA-кэш для маски FA,
  DMA для CONCAT, tiled GET_ROWS — мелкие ускорения по пути.
- Ядро llama.cpp: [#24669](https://github.com/ggml-org/llama.cpp/pull/24669) 09-24
  — **`llama_batch_ext`**, новый API батчей. Наш `llm_jni.cpp` живёт на
  `llama_batch_get_one`; при бампе проверить, не помечен ли он deprecated.
  [#24364](https://github.com/ggml-org/llama.cpp/pull/24364) W4A4 — только CUDA/Blackwell, мимо.

Данные: новых русских ZIM нет (проверено 09-20, за неделю релизов kiwix не было).

### Стоит ли что-то менять у нас

**Да, и это смена модели, а не настройка: Qwen3.5-4B Q4_0 на мастере ≥ 09-27.**
До этой недели наше «Qwen3.5 в 2× медленнее» держалось на трёх дефектах
бэкенда, и все три закрыты: копии не через DMA (09-15), DeltaNet без HMX (09-21),
`ssm_out` на CPU (09-25). Числа апстрима на S26: **~12–13.5 t/s генерация,
~1100 t/s prefill** — против наших 6.0–6.4 и 350–375 на S23 с QVikhr-3-4B
(разные телефоны, так что сравнивать надо на одном; но именно поэтому мерить
на S26). Цена: бамп пина на одну строку, `unsloth/Qwen3.5-4B-GGUF` Q4_0, один
прогон. Риск номер один — **русский**: QVikhr дообучен на русском, Qwen3.5 —
нет; смотреть ответы глазами на всех 32 вопросах, не только tok/s. Риск два —
`llama_batch_ext` при сборке.

**Вторым — Gemma 4 E4B + MTP, той же сборкой, на S26:** #29197 убирает
map/unmap как раз для её 4.6 ГБ, `ggml-org/gemma-4-E4B-it-GGUF` даёт Q4_0 и
MTP-голову, наш MTP-путь написан.

Не делать: сэмплинг на NPU (автор: прироста нет), W4A4 (CUDA), ждать Qwen 4.

**Что апстрим не чинит:** наш главный дефект качества — ~1 ответ из 10 мусор —
остаётся нашим. Смена модели может его случайно убрать или усугубить; это
второй критерий прогона после скорости.

### Что из прошлых рекомендаций сделано

Ничего: спринта на неделе 21–27 не было. Второй проход по b10920 и Gemma 4 E4B
на S26 — по-прежнему в очереди, и теперь обе рекомендации сливаются в одну
сборку с мастером ≥ 09-27.

### Источники

- [ggml-org/llama.cpp — коммиты ggml-hexagon](https://github.com/ggml-org/llama.cpp/commits/master/ggml/src/ggml-hexagon)
- PR: [#29199](https://github.com/ggml-org/llama.cpp/pull/29199), [#29123](https://github.com/ggml-org/llama.cpp/pull/29123), [#28906](https://github.com/ggml-org/llama.cpp/pull/28906), [#29197](https://github.com/ggml-org/llama.cpp/pull/29197), [#29502](https://github.com/ggml-org/llama.cpp/pull/29502), [#29395](https://github.com/ggml-org/llama.cpp/pull/29395), [#24669](https://github.com/ggml-org/llama.cpp/pull/24669), [#24364](https://github.com/ggml-org/llama.cpp/pull/24364)
- Модели: [Qwen 4 — анонс на Apsara](https://www.versely.studio/blog/qwen-4-announced-at-apsara-2026), [llmgateway timeline](https://llmgateway.io/timeline), [unsloth/Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF), [ggml-org/gemma-4-E4B-it-GGUF](https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF)

## Неделя до 2026-09-20

Первая запись в этом файле: недельный обзор введён 2026-09-13 вместо двух
ежедневных задач, но 09-19 прошёл интерактивным спринтом, так что предыдущей
записи здесь нет.

Главное за неделю — не модели, а рантайм: в Hexagon-бэкенде за 5 дней
(09-15…09-19) 10 коммитов, и один из них апстрим измерил ровно на нашем
кандидате — Qwen3.5-4B Q4_0 на v81, +30 % генерации.

### Что вышло

| Что | Размер / тип | Дата | На телефон? | Почему |
|---|---|---|---|---|
| **Ничего нового ≤4B плотного** | — | 09-13…09-20 | — | Проверены google, Qwen, openbmb, HuggingFaceTB, microsoft, meta-llama, mistralai, allenai, ibm-granite, LiquidAI — за неделю ни одного релиза весов в нашем классе |
| **Ничего нового русскоязычного ≤4B** | — | 09-13…09-20 | — | Проверены Vikhrmodels, RefalMachine (Ruadapt), t-tech (T-lite/T-pro), ai-sage (GigaChat), yandex, IlyaGusev — новых весов нет. QVikhr-3-4B остаётся нашей рабочей моделью |
| [Ternary-Bonsai-2-27B](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf) | 27B, троичные веса Qwen3.8-27B, 5.9 ГБ, PQ2_0/PTQ1_0 | 2026-09-16 | **Нет** | Два ограничения сразу: 5.9 ГБ против ~2.5 ГБ свободных на S23, и собственные типы квантования вместо Q4_0 — на DSP такое не пойдёт, только CPU |
| [yandex/AliceAI-T5-35B-A0.6B](https://huggingface.co/yandex/AliceAI-T5-35B-A0.6B) (веса 09-10, [сторонний GGUF](https://huggingface.co/ngquocvinh/AliceAI-T5-35B-A0.6B-GGUF) 09-14) | 35B MoE, A0.6B | 09-10 / 09-14 | **Нет** | MoE на 35B суммарных весов: даже в Q4_0 это ~18 ГБ, на порядок больше бюджета. Отмечено как заметный русский релиз, не как кандидат |
| [yandex/AliceAI-Foundation-80B-A3B-Base](https://huggingface.co/yandex/AliceAI-Foundation-80B-A3B-Base) | 80B MoE, A3B, base | 2026-09-12 | **Нет** | Тот же размер, плюс base без инструкт-тюна |
| [openbmb/MiniCPM5-2B](https://huggingface.co/openbmb/MiniCPM5-2B) — *не за эту неделю (09-06), новый факт* | 2.5B плотный, арх. `llama` (42 слоя, GQA 16/2, head_dim 128), Apache 2.0 | 09-06, GGUF обновлён 09-12 | **Нет** | Архитектурно подошёл бы идеально — плотный Llama, 2.5B. Но карточка заявляет только `en`/`zh`: русского нет, а у нас весь retrieval и UI русские. Плюс в [официальном GGUF](https://huggingface.co/openbmb/MiniCPM5-2B-GGUF) лежат только F16 / Q4_K_M / Q8_0 — Q4_0 пришлось бы квантовать самим |

Вывод по моделям: неделя пустая. Это нормальный результат, а не пропуск поиска.

### Рантайм и данные

**llama.cpp, Hexagon-бэкенд.** Наш пин — `d222767c` (25 авг). 09-19 был собран и
измерен b10920 `eafe15a5` (12 сен), но не отгружен. После `eafe15a5` в
`ggml/src/ggml-hexagon` легло ещё 10 коммитов, почти все — за эту неделю.
Текущий тег мастера на 20 сентября — b11060.

- [#28906](https://github.com/ggml-org/llama.cpp/pull/28906) `72b590d6`, 09-15 —
  **DMA вместо копии, когда src и dst одного типа и непрерывны.** В теле PR
  замер автора: `unsloth/Qwen3.5-4B-GGUF/Qwen3.5-4B-Q4_0.gguf` на Galaxy S26+
  (Snapdragon 8 Elite Gen 5, v81), HTP0, tg16 — **13.47 t/s, «~30 % improvement
  in TG»**. Формулировка PR: «should improve perf for models with large
  reshapes like Qwen 3.x».
- [#28886](https://github.com/ggml-org/llama.cpp/pull/28886) `930e2fa5`, 09-15 —
  возвращён потерянный contiguous fast-path и `hvx_copy_uu`; регрессия, которая
  жила в том числе и в b10920.
- [#28995](https://github.com/ggml-org/llama.cpp/pull/28995) `82324fc5`, 09-16 —
  `supports_op` больше не отвергает обнулённый rope-зонд. Llama прощупывает им
  размещение весов; отказ отправлял `rope_freqs` на CPU и резал граф декода на
  каждом слое полного внимания — **у gemma-4-E2B 5 разрезов вместо 2**.
- [#28994](https://github.com/ggml-org/llama.cpp/pull/28994) `1ec81880`, 09-16 —
  Q4_K/Q6_K на HTP. Уже разобрано в спринте 09-19: это перепаковка в Q4_0 на
  DSP, скорость та же, качество хуже прямого Q4_0. Правило «нативный Q4_0»
  в силе.
- [#26539](https://github.com/ggml-org/llama.cpp/pull/26539) `18a04f09`, 09-18 —
  HMX flash-attention принимает head_dim не кратный 64 (72) через padding до 64;
  раньше такие головы падали на медленный HVX/CPU-путь. Проверено на Android
  v81 и WoS v73/v81.
- [#29103](https://github.com/ggml-org/llama.cpp/pull/29103) `50631b3d`, 09-18 —
  IM2COL: 1D и padded, DMA-путь patch-embed.
  [#29105](https://github.com/ggml-org/llama.cpp/pull/29105) `2b184703`, 09-18 —
  ROLL (f32). Оба — про зрение и свёртки, нам мимо.
- [#29113](https://github.com/ggml-org/llama.cpp/pull/29113) `7d4b92bb`,
  [#29116](https://github.com/ggml-org/llama.cpp/pull/29116) `e613ef2c`, 09-19 —
  TOP_K (битонная сортировка в VTCM) и I32 GET_ROWS: **заготовка под сэмплинг
  прямо на NPU**. Сам сэмплинг этим ещё не включён, автор обещает следующий PR.
  Стоит следить: у нас decode — половина времени хода.
- [#29114](https://github.com/ggml-org/llama.cpp/pull/29114) `851cb34f`, 09-19 —
  GEGLU_QUICK (fp32), нужен **энкодеру зрения Gemma 4**.
- Открытые, не влитые: [#29123](https://github.com/ggml-org/llama.cpp/pull/29123)
  (09-19) добавляет Q5_K на HTP — и мимоходом сообщает важное: **в
  `unsloth/Qwen3.5-4B-GGUF` «Q4_0» тензор `ssm_out` лежит в Q5_K**, то есть
  сегодня уезжает на CPU. [#28952](https://github.com/ggml-org/llama.cpp/pull/28952)
  (09-15) — FP8-веса для v79+ (S26 это v81), пока про диффузию.
  [#29007](https://github.com/ggml-org/llama.cpp/pull/29007) (09-17) — публикация
  готовых Snapdragon-сборок в release workflow; если вольют, часть нашего CI
  станет не нужна.
- Gated DeltaNet: за неделю в бэкенде **ничего нового** — ядро и так есть на
  нашем текущем пине (найдено 09-19).
- [#28891](https://github.com/ggml-org/llama.cpp/issues/28891) (09-14) — «мусор
  на выходе при Vulkan+Hexagon». Выглядело как след нашего открытого дефекта, но
  автор закрыл issue в тот же день: причина — его собственная сборка без
  `libvulkan.so` в путях поиска. **Не зацепка**, перепроверять не нужно.

**Данные.** Новых русских снапшотов нет: свежайшие на
[download.kiwix.org](https://download.kiwix.org/zim/wikipedia/) — `ru_all_mini_2026-07`
(24.07), `ru_all_maxi_2026-02`, `ru_all_nopic_2026-01` (наш). За неделю появился
только английский: **`wikipedia_en_all_mini_2026-09.zim`, 13 ГБ, выложен
2026-09-17** — понадобится, когда начнётся английская поддержка, сейчас нет.
libzim — 9.8.2 (13.08), libkiwix — 14.2.1 (11.05), за неделю релизов не было.

**Реранкеры и эмбеддеры.** Нового в классе mE5 за неделю не нашлось; наш
рерэнкер трогать не из-за чего.

### Стоит ли что-то менять у нас

**Да, одно: сдвинуть пин на мастер ≥ 09-19 и одним прогоном закрыть сразу три
вопроса.** До этой недели «Qwen3.5 в 2 раза медленнее» было нашим измерением без
объяснения. Теперь объяснение названо апстримом и починено: Qwen 3.x гоняет
большие reshape'ы, копии шли не через DMA (#28906), плюс был потерян
contiguous fast-path (#28886). Автор #28906 измерил именно
`Qwen3.5-4B-Q4_0` на v81 и получил +30 % генерации — это наш класс модели и наш
S26. В тот же прогон ложатся второй проход по b10920 (висит с 09-19) и проверка,
не тащит ли `ssm_out` в Q5_K часть графа на CPU (#29123). Цена: одна строка в
двух файлах, одна сборка CI, один прогон. Риск: неподтверждённые −6 % декода с
одного прохода b10920 — поэтому мерить b10920 и свежий мастер в одном заходе,
иначе снова будет спор с шумом.

**Вторым номером — Gemma 4 E4B на S26, в ту же сборку.** Аргумент за неделю
усилился: #28995 убирает лишние разрезы графа декода именно у gemma-4 (на E2B
5 → 2), а [ggml-org/gemma-4-E4B-it-GGUF](https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF)
выкладывает **готовый Q4_0 и отдельную Q4_0-голову MTP** — наш MTP-путь уже
написан и на S23 даёт 1.18×. Цена нулевая, если ехать тем же пином; отдельной
сборки под это не заводить.

Ничего третьего. Моделей менять не на что — недели не было.

### Что из прошлых рекомендаций сделано

Этой секции сравнивать не с чем: предыдущей записи в файле нет. По `git log` за
неделю (09-13…09-20) — 5 коммитов, все из интерактивного спринта 09-19:
`ba43bad` (CI был мёртв с ~15.09 из-за удалённого Google пакета `tools`),
`106a645` (детектор мусора пропускал ответы с голым `\r`), `b66e531`
(«unattributed» считался разностью медиан и врал на 3.2 с), `84a7f31` (отчёт
спринта), `2404271` (зеркало документов в Google Drive).

Из очереди того спринта — второй проход по b10920, Gemma 4 E4B на S26,
перемер Qwen3.5-4B — **не сделано ничего**, задачи ждут следующего спринта. На
телефоне по-прежнему build-49 (b10920), экспериментальный, не main.

### Источники

- Коммиты Hexagon-бэкенда: [ggml-org/llama.cpp, ggml/src/ggml-hexagon](https://github.com/ggml-org/llama.cpp/commits/master/ggml/src/ggml-hexagon)
- PR: [#28906](https://github.com/ggml-org/llama.cpp/pull/28906), [#28886](https://github.com/ggml-org/llama.cpp/pull/28886), [#28995](https://github.com/ggml-org/llama.cpp/pull/28995), [#28994](https://github.com/ggml-org/llama.cpp/pull/28994), [#26539](https://github.com/ggml-org/llama.cpp/pull/26539), [#29103](https://github.com/ggml-org/llama.cpp/pull/29103), [#29105](https://github.com/ggml-org/llama.cpp/pull/29105), [#29113](https://github.com/ggml-org/llama.cpp/pull/29113), [#29114](https://github.com/ggml-org/llama.cpp/pull/29114), [#29116](https://github.com/ggml-org/llama.cpp/pull/29116), [#29123](https://github.com/ggml-org/llama.cpp/pull/29123), [#28952](https://github.com/ggml-org/llama.cpp/pull/28952), [#29007](https://github.com/ggml-org/llama.cpp/pull/29007)
- Issue: [#28891](https://github.com/ggml-org/llama.cpp/issues/28891) (закрыт как ложная тревога)
- Модели: [prism-ml/Ternary-Bonsai-2-27B-gguf](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf), [openbmb/MiniCPM5-2B](https://huggingface.co/openbmb/MiniCPM5-2B), [openbmb/MiniCPM5-2B-GGUF](https://huggingface.co/openbmb/MiniCPM5-2B-GGUF), [yandex/AliceAI-T5-35B-A0.6B](https://huggingface.co/yandex/AliceAI-T5-35B-A0.6B), [yandex/AliceAI-Foundation-80B-A3B-Base](https://huggingface.co/yandex/AliceAI-Foundation-80B-A3B-Base), [ggml-org/gemma-4-E4B-it-GGUF](https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF), [unsloth/Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF)
- Данные: [download.kiwix.org/zim/wikipedia/](https://download.kiwix.org/zim/wikipedia/), [openzim/libzim releases](https://github.com/openzim/libzim/releases), [kiwix/libkiwix releases](https://github.com/kiwix/libkiwix/releases)
