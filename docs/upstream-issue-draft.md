# Черновик issue для ggml-org/llama.cpp — НЕ ОТПРАВЛЕН

Публикуется от GitHub-аккаунта пользователя, **только с его явного согласия**.
Восстановлен 2026-10-28 по тексту от 2026-09-29; дополнен фактами из обзоров
10-11…10-25. Перед отправкой: повторить краш-пробу на свежем мастере и заменить
строку «не проверено» в таблице реальным результатом.

---

**Title:** Eval bug: ggml-hexagon on Hexagon v73 (Galaxy S23, SM8550) — cDSP dies on the first decode since #28994, Q4_0 models included

## Summary

Since `1ec81880` (#28994, "hexagon: Support for K-Quants Q4_K and Q6_K") every build kills the cDSP on the first `llama_decode` on a Galaxy S23 (Hexagon **v73**), with plain **Q4_0** models. The commit immediately before it, `82324fc5` (#28995), works. Bisected on device, one commit apart. The same builds run fine on a Galaxy S26 (v81).

## Device

- Samsung Galaxy S23 (SM-S918B), Snapdragon 8 Gen 2 (SM8550), Hexagon Arch **v73**, Android 14
- `HTP0 hwinfo: threads 4, hvx 4, hmx 1, vtcm 8 MB`; `FASTRPC_GET_DOMAINS query failed (0x6c), using static CDSP domains`; `forcing ndev to 1 for SoCs archs lower than v75`
- Built with `ghcr.io/snapdragon-toolchain/arm64-android:v0.7`, preset `arm64-android-snapdragon-release`; loaded through a JNI wrapper over the public llama API (`llama_decode`, `llama_batch_get_one`), all layers on `HTP0`, `flash_attn_type=AUTO`, `n_ubatch=1024`, F16 KV, single context, no speculative decoding

## Models

- `Vikhrmodels/QVikhr-3-4B-Instruction-GGUF` Q4_0 (dense Qwen3 fine-tune)
- `unsloth/Qwen3.5-4B-GGUF` Q4_0

Both fail identically.

## Bisect (fresh build, install, one RAG prompt of ~1800 tokens)

| llama.cpp | date | S23 (v73) |
|---|---|---|
| `eafe15a5` (b10920) | 09-12 | OK |
| `38a5b42d` (parent of #28906) | 09-15 | OK |
| `72b590d6` #28906 | 09-15 | OK |
| `930e2fa5` #28886 | 09-15 | OK |
| `82324fc5` #28995 | 09-16 | **OK** |
| `1ec81880` #28994 | 09-16 | **cDSP dies** |
| `68d9053a` (parent of #29197) | 09-21 | dies |
| `97a418bd` | 09-24 | dies |
| `fc07d781` | 09-29 | dies |
| `7182ef71` (after #29785, #30067, #30635) | 10-16 | built, **not yet tested** |

Same master on Galaxy S26 (SM8850, v81): runs, Qwen3.5-4B Q4_0 12.5 tok/s decode, 836 tok/s prefill.

Workarounds tried on `fc07d781`, all still die: `GGML_HEXAGON_MM_SELECT=1`, `GGML_HEXAGON_OPFUSION=0`, `GGML_HEXAGON_NHMX=0` (dies at ~24 s instead of ~3 s), both together, draft #29606 applied.

## Log (fc07d781, first decode)

```
I ggml-hex: Hexagon Arch version v73, DMA64 disabled
I ggml-hex: HTP0 new session : session-id 0 domain-id 3 uri file:///libggml-htp-v73.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=cdsp&...
I ggml-hex: HTP0 op batching: n-bufs 16 n-tensors 8192 n-ops 1280 vmem 3102130176
I fastrpc_apps_user.c:1915: get_remote_notif_response: received status notification 3 for domain 3, session 0
E dspqueue_cpu.c:963::error: 0x2e: (nErr = dspsignal_wait(q->domain, QUEUE_SIGNAL(q->id, signal_no), to_now)) == 0
E dspqueue_cpu.c:1014: Error 0x2e: wait_signal_locked failed for queue ... signal 2
E fastrpc_apps_user.c:1360: Error 0x8000040d: remote_handle_invoke failed for handle 0x3, method 4 on domain 3 (sc 0x4020200)
F libc: Fatal signal 6 (SIGABRT)
    #01 libggml-base.so (ggml_abort+228)
    #02 libggml-hexagon.so (ggml_hexagon_session::flush_pending(bool)+616)
    #04 libllama.so (llama_context::decode(llama_batch_ext const&)+2740)
    #05 libllama.so (llama_decode+60)
```

With `GGML_HEXAGON_OPTRACE=1` (QVikhr, 1024-token prefill): opbatch #1 (layers 0–12, 205 ops, incl. fused `MUL_MAT_NX` and `RMS_NORM+MUL`) is pushed and popped successfully; opbatch #2 (`MUL_MAT blk.12.ffn_up q4_0 x f32, 2560:9728 x 2560:1024`, `SWIGLU`, `ffn_down`, `ADD`, `RMS_NORM`, `attn_q`, `ROPE`, …) is pushed, and the PD dies within ~1 ms of batch #1 being popped.

## Possibly related

- #29473 — SM7750 (v73), HMX MUL_MAT returns inf / garbled output (different SoC, no FP16 HMX; our device fails with HMX disabled too)
- #30720 — SM7550 (v73), deterministic garbage with full offload, suspected corruption in large mappings within the 32-bit IOVA window
- #30534 — wrong mappable-memory estimate on v73 with `GGML_HEXAGON_VMEM=0`
- #30934 — re-entry into `flush_pending` on batching failure path
- #29717 — master reported working on IQ-9075 (v73), so this is not universal to v73

Happy to test patches on this device.
