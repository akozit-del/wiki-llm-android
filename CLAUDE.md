# Wiki LLM (Android)

Offline-чат с локальной LLM, дополнённый полнотекстовым поиском по русской
Википедии в формате ZIM. Приложение разрабатывается под Samsung Galaxy S26 Ultra
(Android 16), но архитектурно работает на любом arm64-устройстве с Android 12+.

## Поток данных

```
Пользователь → ChatViewModel
                ├─ RAG ON  → QueryExtractor → ZimSearchHolder (libkiwix)
                │            → RagPromptBuilder (склейка топ-K параграфов)
                │            → LlmRepository → LlamaContext (llama.cpp, JNI)
                └─ RAG OFF → прямой prompt в LlamaContext
```

## Стек

| Компонент            | Версия                                  |
|----------------------|-----------------------------------------|
| Язык                 | Kotlin 2.0.0                            |
| UI                   | Jetpack Compose (BOM 2024.09.02), M3    |
| AGP / Gradle / JDK   | 8.5.2 / 8.9 / 17                        |
| compileSdk / target  | 34 / 34                                 |
| minSdk               | 31                                      |
| ABI                  | `arm64-v8a` (single)                    |
| NDK / CMake          | 26.1.10909125 / 3.22.1                  |
| Сборка нативки       | CMake + FetchContent llama.cpp `b3789`  |

### Ключевые библиотеки

| Зависимость                              | Назначение                                              |
|------------------------------------------|---------------------------------------------------------|
| `androidx.compose.*`                     | UI                                                      |
| `androidx.navigation:navigation-compose` | Навигация между экранами                                |
| `androidx.documentfile`                  | SAF tree URIs (выбор папки с ZIM)                       |
| `com.squareup.okhttp3:okhttp`            | Скачивание моделей и ZIM, GitHub Issues, pastebin       |
| `org.jetbrains.kotlinx:serialization-json` | Парсинг HF/Kiwix каталогов                            |
| `org.kiwix:libkiwix:2.6.0`               | libzim + libkiwix (.so + Java bindings)                 |
| `com.getkeepsafe.relinker:relinker:1.4.5`| Загрузка .so из подкаталогов внутри AAR                 |

Нативная llama.cpp линкуется как одиночная `libllm.so` без `common` (см.
`app/src/main/cpp/CMakeLists.txt`). Все GGML-бэкенды (BLAS / VULKAN / CUDA /
OPENMP / METAL / LLAMAFILE) выключены.

## Архитектура

MVVM с Compose-VM-уровневыми StateFlow. Слои:

- `data/` — репозитории и API-клиенты (модели Hugging Face, каталог Kiwix,
  локальные скачанные модели и ZIM, `LlmRepository` поверх JNI).
- `llm/` — Kotlin-обёртка над нативной llama.cpp (`LlamaContext`).
- `rag/` — поиск по ZIM, склейка контекста, выделение keywords,
  Application-scoped `ZimSearchHolder`.
- `diag/` — персистентный лог-буфер, отправка в GitHub Issues / clipboard /
  0x0.st.
- `settings/` — SharedPreferences и экран настроек (PAT-токен для авто-отправки).
- `ui/screens/` — Compose-экраны: Home, Models, Wiki, Chat, плюс RAG-контролы.
- `cpp/` — JNI-мост к llama.cpp (`llm_jni.cpp`), регистрирует
  `nativeLoad/nativeGenerate/nativeLastError/nativeFree`.

`MainActivity` — единственное Activity, навигация — Compose Nav. `Application`
(`WikiLLMApplication`) поднимает libzim/libkiwix/wrapper-нативки через ReLinker
и привязывает глобальный crash-handler.

## Build / Install / Run

CI собирает Debug APK на каждый push в `main` (`.github/workflows/build.yml`),
тэг релиза — `build-<run_number>`. Подпись — стабильный `app/debug.keystore`
(коммитнут в репо, пароли `android`/`android`).

```bash
# Debug APK без CI
./gradlew :app:assembleDebug

# Установить на подключённое устройство и запустить
./gradlew :app:installDebug
adb shell am start -n com.wikillm.android.debug/com.wikillm.android.MainActivity

# Lint / тесты (юнит-тестов пока нет; команды оставлены для совместимости)
./gradlew :app:lint
./gradlew :app:test

# Удобный цикл (см. scripts/dev-loop.sh)
./scripts/dev-loop.sh
```

`applicationId` в debug-сборке — `com.wikillm.android.debug` (`.debug` суффикс).
В release он будет `com.wikillm.android`.

## Что уже сделано (stages)

- **1 — Скелет + CI.** Compose-приложение, GitHub Actions сборка, стабильный
  debug keystore (`app/debug.keystore`).
- **2 — Каталог моделей.** Поиск GGUF на Hugging Face через `HfApi`, выкачка
  выбранного файла в `getExternalFilesDir/models/`. Прогресс, отмена,
  миграция со старого `filesDir`.
- **3 — Каталог ZIM.** Поиск ZIM-файлов в каталоге Kiwix + ручной picker через
  SAF, авто-скан выбранной директории через `DocumentFile.fromTreeUri`.
- **4 — llama.cpp + чат.** `LlamaContext.load` грузит модель через mmap, чат
  стримит токены через `channelFlow`. Chat template из метаданных модели,
  sampler-chain: penalties → min_p → temp → dist. Системный промпт через
  `llama_chat_apply_template`. `nativeLastError` пробрасывает текст
  `llama_log_set` в Kotlin.
- **5 — libzim.** Подключён `org.kiwix:libkiwix:2.6.0`, нативки грузятся через
  ReLinker (libzim.so и libkiwix.so) и `System.loadLibrary` (libzim_wrapper.so,
  libkiwix_wrapper.so). Открытие ZIM через `/proc/self/fd/N` поверх
  SAF-`ParcelFileDescriptor`. Прямой File-API на `/Android/media/<other_pkg>/`
  заработал только после явной выдачи `MANAGE_EXTERNAL_STORAGE`.
- **6 — RAG-чат.** Переключатель «Без вики / Вся вики», слайдер 10/20/50
  кандидатов. `QueryExtractor` чистит вопрос от стоп-слов, склейка топ-K
  параграфов идёт в prompt с инструкцией «отвечай только по выдержкам».
  Fallback на самое длинное слово, если AND-поиск дал ноль кандидатов.
- **Диагностика.** Персистентный `DiagLog` в `filesDir/diag.log` (выживает
  при crash), Compose-экран, авто-отправка в GitHub Issues через
  `GitHubIssueReporter` (PAT из SharedPreferences или CI-секрета `DIAG_PAT`),
  плюс fallback-аплоад на 0x0.st / ix.io.

## Конвенции

- Стиль Kotlin — официальный, 4 пробела, `kotlinx.coroutines` для асинхронки,
  `StateFlow` в VM, без LiveData.
- Compose: один `Composable` на файл/экран не обязательно, но связанные
  виджеты живут рядом с экраном.
- В JNI-обёртке (`llm_jni.cpp`) все native-методы пишут ошибки в
  `__android_log_print` + добавляют их в `g_last_error` через `llama_log_set`.
- Имена тэгов в `DiagLog`: `WikiLLMApp`, `ChatVM`, `ZimSearcher`,
  `RagPromptBuilder`, `WikiSearchVM`, `ZimSearchHolder`, `GitHubReporter`.
- Сообщения коммитов: префикс этапа или короткий тэг (`Stage 6 fix:`,
  `build-13 fix:`, `Stage 5e:`), затем суть на английском. Из `git log`
  ввыводится понятный таймлайн стейджей.
- Sandbox / CI / PROJECT.md — обновляются вручную при крупных архитектурных
  сдвигах. CLAUDE.md (этот файл) — то же самое.

## В работе

**Stage 8 — llama.cpp upgrade + статистика генерации**

- `llama.cpp` обновлён `b3789 → b9296` (репо `ggml-org/llama.cpp`), чтобы
  поддержать архитектуру `gemma4` и др. новые модели.
- `llm_jni.cpp` переписан под новый API: `llama_model_load_from_file`,
  `llama_init_from_model`, vocab-функции (`llama_vocab_*`),
  `llama_chat_apply_template(tmpl_str, …)` (шаблон берётся из
  `llama_model_chat_template`), `llama_batch_get_one(tokens, n)` (позиции
  трекаются автоматически), `llama_memory_clear`, упрощённые `penalties`.
  `llama_sampler_sample` теперь сам делает accept — отдельный вызов убран.
- `TokenCallback.onComplete(promptTokens, genTokens)` + новый тип `LlmEvent`
  (`Token`/`Done`): нативка отдаёт точное число токенов промпта и ответа.
- `ChatViewModel`: тикер живого прогресса (`GenProgress`: прошедшее время + ETA
  до `maxTokens`), финальная статистика (`GenStats`: модель, секунды, токены,
  ток/с) под каждым ответом.
- `ChatScreen`: «⏱ N с · осталось ~M с» во время генерации, строка
  «модель · N с · K ток · R ток/с» после ответа.

**Что дальше (Stage 9+):** полный OpenWebUI-редизайн (чат — главный экран,
боковое меню, выбор модели сверху, тёмная тема), докачка моделей с резюме +
проценты.

### Готово

- **Stage 7 — Chat history + RAG fixes.** `nativeGenerateChat` (roles/contents,
  шаблон на всю историю), `generateChat`-обёртки, история обменов в модель,
  `maxTokens 256→512`, RAG `topK 3→5` / `budgetChars 1500→2000` + title-boost
  (фикс «спидвей вместо Тольятти»), понятная ошибка неподдерживаемых архитектур,
  coreference-резолв местоимений в follow-up вопросах.

