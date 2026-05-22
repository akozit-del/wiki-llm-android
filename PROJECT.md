# Wiki LLM Android — статус проекта

> Этот файл — точка восстановления. Если работаешь со свежей сессией Claude — прочитай его перед тем, как что-либо делать.

## Что мы делаем

Android-приложение для Samsung Galaxy S26 Ultra (Android 16), которое:

1. Локально хранит GGUF-модели и ZIM-файлы Википедии.
2. Запускает LLM на CPU через llama.cpp (JNI-обёртка).
3. Делает RAG-чат: вопрос пользователя → полнотекстовый поиск по ZIM через libzim → склейка релевантных параграфов в prompt → ответ модели по фактам.

Пользователь — не разработчик, не использует Android Studio, билдит **только через GitHub Actions**. APK раздаётся через GitHub Releases. Все объяснения по-русски.

## Repo + CI

- **GitHub:** `akozit-del/wiki-llm-android`
- **CI:** `.github/workflows/build.yml` — собирает Debug APK на каждый push в main, тэг `build-<run_number>`.
- **Подпись:** commit'нутый `app/debug.keystore` (пароль `android`, alias `androiddebugkey`) — чтобы подпись была стабильной между билдами и data не терялась при обновлении.
- **Gradle:** 8.9, AGP 8.5.2, Kotlin 2.0.0, Compose BOM 2024.09.02.
- **NDK:** `26.1.10909125`, CMake `3.22.1`, ABI **только `arm64-v8a`**.

## Стейджи

| # | Стейдж | Статус |
|---|---|---|
| 1 | Скелет + CI | ✅ build-3 |
| 2 | HF каталог моделей + GGUF-загрузка | ✅ build-4/5 |
| 3 | Kiwix ZIM каталог + file picker | ✅ build-6/8 |
| 4 | llama.cpp через JNI + чат | ✅ build-12/14 (build-12 базовый, 14 с chat template + repeat penalty) |
| 5 | libzim через libkiwix AAR | 🟡 build-16/19 — собирается, идёт отладка SAF-открытия |
| 6 | RAG-чат с слайдером N (10/20/50 статей) | ⏳ |
| 7 | Полировка + settings + OOM-handling | ⏳ |

## Накопленные технические решения

### Stage 4: llama.cpp

- Закреплён тэг **`b3789`** (FetchContent в `app/src/main/cpp/CMakeLists.txt`).
- В `b3789` `llama_batch_get_one` принимает 4 аргумента: `(tokens, n_tokens, pos_0, seq_id)`. В свежих версиях — 2.
- В `b3789` `llama_sampler_init_penalties` принимает **9 параметров** (включая `n_vocab`, `special_eos_id`, `linefeed_id`, `penalize_nl`, `ignore_eos`). В свежих — 4.
- `LLAMA_BUILD_COMMON OFF`, все `GGML_*` (BLAS, VULKAN, CUDA, OPENMP, LLAMAFILE, METAL) выключены.
- Линкуем только `llama` + `android` + `log`.
- Chat template применяется через `llama_chat_apply_template(model, nullptr, msgs, 1, true, buf, len)` — `nullptr` = взять template из метаданных модели.
- Sampler chain: penalties → min_p(0.05) → temp(0.7) → dist.
- `llama_log_set` ловит ошибки загрузки в `g_last_error`, экспонируется через `nativeLastError(): String`.

### Stage 5: libzim

- Подключён **`org.kiwix:libkiwix:2.6.0`** (Maven Central AAR ~60 МБ, прекомпильнаya ARM64 нативка).
- Java API: `org.kiwix.libzim.Archive`, `Searcher`, `Query`, `Search`, `SearchIterator` (`getTitle`, `getPath`, `getSnippet`, `getScore`).
- **ВАЖНО:** конструктор `Archive(FileDescriptor)` в публичном AAR **не реализован** (`UnsatisfiedLinkError: setNativeArchiveByFD`). build-19 пробует 4 варианта по очереди: `Archive(FdInput[])`, `Archive(FdInput)`, `Archive(FileDescriptor, offset, size)`, `Archive(FileDescriptor)` — какой сработает, тот и используется. Результаты в Diag-логе.
- HTML→plain text для статей: простая regex-чистка (script/style выкинуть, теги выкинуть, decode entities).

### Storage и пути

- Модели лежат в `getExternalFilesDir(null)/models/` (внешний app-specific) — выживают при переустановке, есть миграция из старого `filesDir/models`.
- ZIM файлы пользователя — в `/Android/media/org.kiwix.kiwixmobile/`. Доступ через SAF tree URI + persisted permissions. Открытие через `ContentResolver.openFileDescriptor` → `ParcelFileDescriptor` → `Archive(FdInput…)`.

### Diag-лог (build-19)

- `com.wikillm.android.diag.DiagLog` — синглтон с буфером последних 300 записей.
- Экран «Диагностика» на главной странице: список с цветами по уровню (E/W/I) + кнопки «Копировать» / «Очистить».
- Глобальный crash-handler через `Thread.setDefaultUncaughtExceptionHandler`.
- Когда что-то не работает — пользователь делает «Копировать» и кидает в чат, я читаю реальный стек.

## Что НЕ работает / известные ограничения

- **Gemma 3** не загружается на `b3789` (`unknown model architecture: gemma3`). Чтобы починить — нужно поднять llama.cpp до ~`b9265` (свежий), но это сильный API-break, отдельная отдельная итерация.
- **Q2_K квантизация** — почти всегда зацикливается на любом языке, не использовать.
- **Каталог Kiwix** в приложении — HTML-парсер не распознаёт текущий формат страницы (regex старый). Не блокирующий для основного флоу.
- **Сборка из коммитов <build-12** не запускается на Android — старая подпись/keystore.

## Текущая точка

- Последний рабочий чат: **build-14** (Qwen2.5-1.5B Q4_K_M отвечает связно, но с галлюцинациями — норма для 1.5B-модели без вики-контекста).
- Последний билд: **build-19** — добавлен Diag-лог + перебор 4 конструкторов `Archive(fd)`. Ждём dump из «Диагностики» от пользователя, чтобы узнать, какой из конструкторов libkiwix реально умеет открывать ZIM через SAF.

## Следующие шаги

1. Получить от пользователя «Копировать» из Диагностики после открытия «Поиск в вики» — узнать, какой конструктор `Archive(...)` работает.
2. Закрепить рабочий вариант, удалить остальные ветки перебора.
3. Подключить полнотекстовый поиск к ChatViewModel: режим «Без вики / Вся вики», слайдер 10/20/50 кандидатов.
4. Промпт-шаблон: `Контекст:\n<top-K snippets>\n\nВопрос: <user>` → llama.cpp.
5. (Опц.) Добавить эмулятор Android в CI для базовых smoke-тестов после изменений UI/нативки.
6. (Опц.) Апгрейд llama.cpp до b9265+ для Gemma 3 / Llama 3.3 / Phi 4.

## Среда разработки

- Работаю в `/tmp/wll/repo` (Yandex.Disk FUSE на `/Helth/` ломает `.git`).
- Sync обратно в `/Helth/wiki-llm-android/` через `cp` после каждого коммита.
- GitHub PAT с правами `repo` — у пользователя в чате, при необходимости новый запрашиваю.
- Sandbox **не имеет прямого доступа к api.github.com** (403 от прокси) — для чтения Actions использую Claude in Chrome MCP через залогиненный браузер пользователя.

