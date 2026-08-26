# Wiki LLM

Offline Wikipedia with a local language model, on Android. No network, no API keys,
no data leaving the phone — the corpus, the retrieval and the model all sit on the
device.

Built for the case where the network isn't there: a plane, a train, a basement, a
country with a bad connection. You get Wikipedia's factual grounding plus a model
that can read it for you and answer in prose.

## What makes it different

Most offline-wiki projects either have no model at all (Kiwix) or shell out to a
cloud LLM. This one runs the model on the phone's **NPU** and publishes measured
latency and accuracy numbers, which almost nobody in this niche does.

The other trick is a **fast path that skips the model entirely**. Wikipedia
infoboxes are structured "label: value" pairs, so for a single-fact question the
answer is literally one field. Reading that field is sub-second; asking a 4B model
to restate it costs ~30 seconds and can hallucinate.

```
"кто мэр Тольятти"  →  Илья Геннадьевич Сухих     98–409 ms, no model involved
"из чего состоит атмосфера Марса"  →  full RAG     ~28 s
```

## Measured

Samsung S26 (Snapdragon 8 Elite, Hexagon v81), 32-question reference set,
Russian Wikipedia `nopic` snapshot (13.7 GB).

| Metric | Value |
|---|---|
| Infobox fast path | **98–409 ms** |
| Full RAG answer | 28.4 s (250 tokens) |
| Prefill | **1056 tok/s** |
| Decode | 10.7 tok/s (under RAG context) |
| ZIM search, median / p90 | **525 / 849 ms** |
| recall@3 | **97%** (31/32) |
| recall@1 | 53% |
| MRR | 0.729 |
| Fast-path hit rate, infobox questions | **93%** (13/14) |
| Fast-path coverage, all questions | 44% (14/32) |
| Wrong-article fast answers | **0%** |

Reproduce with `./benchmark/run_probe.sh 20 <adb-serial>`. Methodology and the
question set are in [`benchmark/`](benchmark/).

Two honest caveats. `recall@1` swings 53–56% between runs of the same build, so
differences under ~5 points on this set are noise, not signal. And one question is
permanently unanswerable in this snapshot because the expected article is a
redirect — we don't retarget the reference to match our own output, since that
would make the benchmark circular.

## How it works

```
question
   │
   ├─→ factoid intent + entity resolves to an article with the asked-for
   │   infobox field?  →  answer straight from the card          (~0.4 s)
   │
   └─→ otherwise: ZIM search (BM25 + title-index probe + mE5 rerank)
       → top-K articles → infobox + body into the prompt
       → local model generates                                    (~30 s)
```

Retrieval is deliberately layered. Xapian's BM25 alone matches word forms, so a
question about «Казань» loses to an article titled «Архитектура Казани». A probe
against the title index fixes that, and lifted recall@3 from 62% to 97%.

Entity resolution is strict on purpose: a candidate has to *be* the entity, not
merely mention it, so «Улица 40 лет Победы (Тольятти)» can never answer a question
about «Тольятти». A confidently wrong sub-second answer is worse than falling back
to the slow path.

## Requirements

- Android 12+ (API 31), **arm64** only
- Snapdragon with a Hexagon NPU for the fast path — other chips fall back to CPU
  and run several times slower
- ~3 GB free RAM for a 4B model
- Disk for the corpus: 4.9 GB (`mini`) to 13.7 GB (`nopic`) for Russian Wikipedia

## Models

Ship in GGUF, **Q4_0 only** — k-quants (`Q4_K_M` and friends) fall off the NPU onto
the CPU and run ~20× slower. Tested:

| Model | Size | Role |
|---|---|---|
| QVikhr-3-4B-Instruction | 2.2 GB | Russian reader, direct answers |
| DeepSeek-R1-Distill-Qwen-1.5B | 1.0 GB | Fastest; English reasoning |
| Qwen3-4B-Thinking-2507 | 2.2 GB | Reasoning with a visible thought stream |

Architecture matters more than size: dense transformers (Qwen2.5/Qwen3, Llama)
work; hybrid SSM/DeltaNet is roughly 2× slower on Hexagon, and Phi-4's scaled
rotary aborts the backend outright. Models above ~4B don't fit — their KV cache
exceeds what the DSP will allocate.

## Building

CI builds on every push. `hexagon-app.yml` produces the NPU build used on device;
both workflows pin the same llama.cpp commit so they can't drift apart.

```bash
gh workflow run hexagon-app.yml --ref main
gh run download <run-id> -n wiki-llm-hexagon-apk
adb install -r wiki-llm-hexagon-*.apk
```

Local builds need JDK 17, Android SDK 34 and NDK 26.1:

```bash
./gradlew :app:assembleDebug
```

## Current limitations

**The retrieval side is built around Russian Wikipedia.** Infobox labels, the
factoid intent patterns and the genitive-to-nominative entity handling are all
Russian. The UI is Russian too. An English corpus will mostly work through the
generic RAG path, but the fast path won't fire. English support is planned, not
present.

`recall@1` sits at 53%: the right article is nearly always retrieved, but not
always ranked first. The ordering set by the title probe is lost somewhere
downstream — a known open issue, not a mystery.

## Stack

Kotlin 2.0 · Jetpack Compose (Material 3) · llama.cpp with the `ggml-hexagon` NPU
backend via JNI · libzim/libkiwix for the corpus · jsoup for infobox parsing ·
multilingual-e5-small for semantic reranking.

## License

MIT
