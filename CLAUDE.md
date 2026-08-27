# Wiki LLM (Android)

Offline chat: a local GGUF model answering from a full-text search over Wikipedia
in ZIM format. Everything runs on the phone — no network at inference time.

Primary target is Snapdragon with a Hexagon NPU (tested on S23 / v73 and
S26 / v81); it degrades to CPU elsewhere. arm64 only, Android 12+.

Written in English deliberately: this file is loaded into context every session,
and Cyrillic costs roughly 2-3× the tokens per character.

## Request flow

```
question
   │
   ├─→ FactoidAnswerer: factoid intent + entity resolves to an article
   │   carrying the asked-for infobox field?  →  answer from the card,
   │   no model involved                                        (~0.4 s)
   │
   └─→ ChatViewModel → ZimSearchHolder (libkiwix)
       → RagPromptBuilder: BM25 + title-index probe + mE5 rerank
       → infobox + body into the prompt
       → LlmRepository → LlamaContext (llama.cpp JNI)           (~30 s)
```

RAG off sends the prompt straight to the model.

## Stack

| Component | Version |
|---|---|
| Kotlin | 2.0.0 |
| UI | Jetpack Compose (BOM 2024.09.02), M3 |
| AGP / Gradle / JDK | 8.5.2 / 8.9 / 17 |
| compileSdk / target / minSdk | 34 / 34 / 31 |
| ABI | `arm64-v8a` only |
| NDK / CMake | 26.1.10909125 / 3.22.1 |
| llama.cpp | pinned `d222767c` in **both** CMakeLists and hexagon-app.yml |

Key libraries: `org.kiwix:libkiwix:2.6.0` (libzim + bindings),
`com.getkeepsafe.relinker` (loads .so from AAR subdirs), `org.jsoup` (infobox and
body extraction), `okhttp` (model/ZIM downloads), `kotlinx-serialization`.

llama.cpp links as a single `libllm.so` without `common`. All GGML backends except
CPU/OpenCL/Hexagon are off.

## Layout

- `data/` — repositories: HF model catalog, Kiwix catalog, local models/ZIM,
  `LlmRepository` over JNI, `ChatHistoryStore`.
- `llm/` — `LlamaContext`, the Kotlin side of the JNI bridge.
- `rag/` — `ZimSearcher`, `ZimSearchHolder` (app-scoped), `RagPromptBuilder`,
  `QueryExtractor`, `InfoboxExtractor`, `FactoidAnswerer`, `EntityTitleProbe`,
  `EmbeddingHolder` (mE5 rerank).
- `diag/` — `DiagLog` (persistent, survives crashes), diag screen, GitHub issue
  reporter, `BenchmarkBridge` (adb-driven benchmark, debug builds only).
- `settings/` — `GenerationSettings` (SharedPreferences) and the settings screen.
- `ui/screens/` — Compose screens; chat is the start destination, everything else
  lives behind the drawer.
- `cpp/llm_jni.cpp` — the JNI bridge.

## Build, install, measure

```bash
# NPU build — this is the one that goes on the device
gh workflow run hexagon-app.yml --ref main
gh run download <run-id> -n wiki-llm-hexagon-apk
adb install -r wiki-llm-hexagon-*.apk

# Plain build (also green; both pin the same llama.cpp)
./gradlew :app:assembleDebug

# Benchmark: 32 questions, retrieval-only, no model
./benchmark/run_probe.sh 20 <adb-serial>
```

Debug `applicationId` is `com.wikillm.android.debug`. Signing uses the committed
`app/debug.keystore` (passwords `android`/`android`), so builds stay installable
over each other.

Devices: S23 `R5CW12RVLKZ`, S26 `R5GL21SQX6Z`.

## Hexagon constraints — learned the hard way, don't rediscover

- **Q4_0 only.** k-quants (`Q4_K_M` etc.) fall off the NPU onto the CPU: measured
  0.4 tok/s versus ~8 for the same model in Q4_0.
- **Dense transformers only.** Qwen2.5/Qwen3 and Llama work. Hybrid SSM/DeltaNet
  (Qwen3.5) is ~2× slower — no delta-net kernel, those layers go to CPU. Phi-4's
  scaled rotary aborts the backend outright (`ggml_abort` in `flush_pending`).
- **≤4B parameters.** 7B/8B fail at load: the KV cache exceeds what the DSP will
  allocate (`HTP0 buffer mapping failed`).
- **KV cache must stay F16.** Quantized K/V is rejected by `set_rows`,
  `flash_attn_ext` and `mul_mat` alike, silently moving attention to the CPU.
- `flash_attn_type = AUTO` and `n_ubatch = 1024` — Qualcomm's own scripts use
  these; together they roughly doubled prefill.
- `ADSP_LIBRARY_PATH` must be set in `Application.onCreate`, else the DSP can't
  find `libggml-htp-vNN.so` (error `0x80000406`).
- `useLegacyPackaging=true` — otherwise `dladdr` returns an in-APK path and the
  backend .so files aren't found.
- `libOpenCL.so` and `libcdsprpc.so` are declared via `<uses-native-library>`, not
  packaged.
- The MTP staging functions live in a C++ (not `extern "C"`) header. Declaring
  them `extern "C"` breaks loading of the entire `libllm.so`.

## Other non-obvious facts

- A token can end mid-UTF-8-sequence; `NewStringUTF` aborts on that. `run_generation`
  buffers bytes (`utf8_complete_len`) and hands Kotlin a `ByteArray`.
- ZIM opens over SAF through `/proc/self/fd/N`. Direct File API on
  `/Android/media/<other_pkg>/` needs `MANAGE_EXTERNAL_STORAGE`.
- `ZimSearcher.lookupExactTitle` is **not** exact — tier 2 is a fuzzy
  `SuggestionSearcher`. Anything relying on identity must re-check the title.
- `adb shell input text` throws on Cyrillic on Samsung firmware. Benchmark
  questions arrive via intent instead — see `BenchmarkBridge`.

## Conventions

- Kotlin official style, 4 spaces, coroutines + `StateFlow`, no LiveData.
- Comment *why*, not *what*. Prefer a note explaining a non-obvious constraint
  over a restatement of the code.
- `DiagLog` tags: `WikiLLMApp`, `ChatVM`, `ZimSearcher`, `RagPromptBuilder`,
  `ZimSearchHolder`, `FactoidAnswerer`, `BenchmarkBridge`.
- Commit subject: `area: what changed` (`rag:`, `llm:`, `chat:`, `bench:`,
  `build:`, `docs:`). Body explains the reasoning and cites measurements.
- A wrong fast answer is worse than a miss. Every uncertain step in
  `FactoidAnswerer` returns null and falls through to the full pipeline.

## Measuring

`benchmark/questions.json` — 32 questions. Ground truth is the **article and
infobox field**, not the answer value: values depend on the ZIM snapshot, and
seeding them from our own output would make the benchmark circular.

`benchmark/LATEST.md` — digest of the scheduled runs, newest first. **Read this
first**; it carries current numbers and open issues.

Two scheduled tasks (11:37 and 02:47 daily) each spend half an hour on one
improvement and report to Notion plus `LATEST.md`. Their prompts live in
`~/.claude/scheduled-tasks/`.

Metrics that matter: recall@k, fast-path hit rate, false-fast-rate, latency by
phase. Note that recall@1 swings ~3 points between runs of the same build, so
smaller differences are noise.

## Current state

Retrieval and the fast path are in good shape (recall@3 97%, infobox fast path
93-100%, zero wrong-article fast answers). Decode is now the dominant cost of a
full RAG turn — about 90% of wall time.

`recall@1` was 52% until 2026-08-27 and is now 78%: the probe's ordering was
being lost to `distinctBy { it.path }`, which keeps the *first* copy of a path,
not the highest-scored one. Six questions are still ranked 2-3, five of them the
whole `list` category. The UI and the whole retrieval side are Russian-only;
English support is not started.
