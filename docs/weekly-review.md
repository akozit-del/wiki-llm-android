# Недельный обзор

Что вышло за неделю в малых открытых моделях, в Hexagon-бэкенде llama.cpp и в
данных ZIM — и стоит ли что-то менять у нас. Обзор и рекомендация, не спринт:
код тут не трогается. Новые записи сверху.

Текущие числа для сравнения — в `benchmark/LATEST.md`, ограничения платформы —
в `CLAUDE.md`.

---

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
