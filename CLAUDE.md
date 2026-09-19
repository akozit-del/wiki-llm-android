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

- **Native Q4_0 only.** On our pin k-quants (`Q4_K_M` etc.) fall off the NPU onto
  the CPU: measured 0.4 tok/s versus ~8 for the same model in Q4_0. Upstream
  added Q4_K/Q6_K to the backend on 2026-09-16 (#28994), but it *repacks* them
  to Q4_0 on the DSP — same speed, worse quality than a GGUF quantized to Q4_0
  directly. So the rule survives a pin bump; only the failure mode changes.
- **Dense transformers only.** Qwen2.5/Qwen3 and Llama work. Hybrid SSM/DeltaNet
  (Qwen3.5) measured ~2× slower. The "no delta-net kernel" explanation is stale:
  `ggml-hexagon` handles `GGML_OP_GATED_DELTA_NET` and `SSM_CONV` already on our
  pin, so the 2× is either an old measurement or a slow kernel — re-measure
  before ruling Qwen3.5 out again. Phi-4's scaled rotary aborts the backend
  outright (`ggml_abort` in `flush_pending`).
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

`benchmark/LATEST.md` — digest of benchmark runs, newest first. **Read this
first**; it carries current numbers and open issues.

The daily improvement tasks (11:37 and 02:47) were cancelled on 2026-09-13.
What runs now is one weekly review, Sundays 11:00: what changed in small
open models, the Hexagon backend and ZIM data over the past week, and
whether anything in this system should change because of it. It writes to
`docs/weekly-review.md` (newest first) and, when the workspace allows, a
Notion page. It reviews and recommends; it does not touch code. Prompt in
`~/.claude/scheduled-tasks/wiki-llm-weekly-review/`.

Where results are finalized: `benchmark/LATEST.md` for measurements,
`docs/weekly-review.md` for the weekly landscape review, and Notion sprint
pages under «⚡ Wiki-LLM: скорость локального инференса» for full reports.

Metrics that matter: recall@k, fast-path hit rate, false-fast-rate, latency by
phase. Note that recall@1 swings ~3 points between runs of the same build, so
smaller differences are noise.

## Current state

Retrieval and the fast path are in good shape (recall@3 97%, infobox fast path
93-100%, zero wrong-article fast answers). A model-path turn (S23, QVikhr-3-4B,
thinking off) is ~18 s median: prefill ~35%, decode ~50%, retrieval ~8%
(2026-09-19, `benchmark/before-pin-b10920-build47.txt`). Decode was 90% until
the 2026-09-01 prompt/history/thinking changes cut it 4.5×.

Open defect: ~1 model answer in 10 is garbage — random tokens across scripts,
often to the 1600-token cap. Tied to a few questions (АвтоВАЗ, Байкал, БКП,
now also Юпитер/фотосинтез), not to MTP, not to the sampler, not to context
size. `score_garbage.py` counts it; `turns.log` (TurnDump) holds the exact
prompt. Reproduce from a *sequence* of turns, not a single question.

llama.cpp pin: b10920 (`eafe15a5`) builds green with unmodified `llm_jni.cpp`
(run 35430043900) and loads on S23, but one 13-turn pass measured −6% decode,
−7% prefill and 2/13 garbage vs 1/13 — within noise, unconfirmed. Not shipped;
a second pass decides.

`recall@1` was 52% until 2026-08-27 and is now 91%: the probe's ordering was
being lost to `distinctBy { it.path }`, which keeps the *first* copy of a path,
not the highest-scored one. No genuine retrieval miss is left on the set. Two
questions still rank 2 (`ls01`, `ls02`) and there the ground truth is the thing
that looks wrong — we rank a list article above the topic article and the
benchmark counts that as a loss. `pr03` is unreachable: this snapshot redirects
«Пенициллин» to «Бензилпенициллин», so the expected article does not exist as a
target.
The UI and the whole retrieval side are Russian-only; English support is not
started.
