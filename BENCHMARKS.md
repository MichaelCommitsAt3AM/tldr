# Transcription benchmarks

These measurements were taken on `laptop-server`, which has an Intel Core i5-6200U, Intel HD Graphics 520, and AMD Radeon R7 M340. Times are wall-clock seconds for transcription. They exclude model loading, source retrieval, and summarization unless stated otherwise. Each cell represents one timed run.

## Whisper on CPU and Vulkan

All runs used English-only Whisper `base.en`. The CPU used faster-whisper INT8 with two threads and beam size 5. Both GPUs used whisper.cpp Q8 through Mesa Vulkan with beam size 5. The GPU logs confirmed the selected device. The Intel runs using a driver selection that fell back to the CPU were excluded.

| Clip | Audio (s) | CPU (s) | Radeon Vulkan (s) | Intel Vulkan (s) |
| --- | ---: | ---: | ---: | ---: |
| Clear American English | 31.4 | 4.57 | 6.08 | 13.90 |
| British English talk | 31.5 | 4.14 | 4.62 | 31.24 |
| Fast meeting speech | 45.0 | 4.76 | 5.49 | 16.14 |
| **Total** | **107.9** | **13.47** | **16.20** | **61.29** |

All three produced readable transcripts. These runs were not scored against reference text. Intel's British English run was unusually slow and was not repeated. The CPU was fastest on each clip.

## Moonshine versus Whisper on CPU

The original English Moonshine Base and Tiny models used the official quantized ONNX Runtime builds via Moonshine Voice 0.1.5. The Whisper baseline used faster-whisper `base.en` INT8, two threads, beam size 5, and voice activity detection. Models ran sequentially and were pinned to the same two physical cores. Each model was warmed up before measurement.

Each cell reports seconds and word error rate (WER). WER compares the output to the dataset reference after normalizing case, punctuation, and whitespace. The total WER pools word errors across the 1,010 reference words.

| Clip | Audio (s) | Whisper `base.en` | Moonshine Base | Moonshine Tiny |
| --- | ---: | ---: | ---: | ---: |
| Clear American English | 31.4 | 3.71 / 4.71% | 2.76 / 1.18% | 1.92 / 5.88% |
| Kenyan English | 66.7 | 7.41 / 5.98% | 6.35 / 13.04% | 4.54 / 10.87% |
| Noisy meeting | 60.0 | 5.12 / 23.39% | 5.23 / 21.05% | 3.78 / 53.80% |
| Long talk | 181.0 | 22.67 / 10.67% | 21.29 / 12.76% | 15.35 / 13.71% |
| Kenyan names and places | 19.0 | 5.20 / 4.44% | 1.71 / 13.33% | 1.10 / 11.11% |
| **Total** | **358.1** | **44.12 / 11.19%** | **37.34 / 13.27%** | **26.68 / 19.21%** |

Moonshine Base reduced total time by 15% and Tiny by 40%. Whisper had the lowest pooled WER and transcribed “William Ruto” correctly, while both Moonshine models missed the name. All three outputs ended before the last words in the long clip's reference, so its absolute WER is approximate. The benchmark scripts, model files, and raw results are under `~/speech-bench/moonshine-compare` on the server.

## Cloudflare Workers AI versus local Whisper

One request to `@cf/openai/whisper-large-v3-turbo` ran from `laptop-server` through TLDR's normal egress proxy. The input was the same 66.7-second Kenyan English clip (`P03`) as in the CPU benchmark. The request used English, voice activity detection, and beam size 5. Cloudflare wall time includes encoding the 2.13 MB WAV as base64, uploading it, and receiving the response. The local comparison uses the earlier warm CPU run on the same clip. Neither time includes fetching the original video or summarization.

| Backend | Wall time (s) | Word errors / reference words | WER |
| --- | ---: | ---: | ---: |
| Local faster-whisper `base.en` INT8 | 7.41 | 11 / 184 | 5.98% |
| Cloudflare Whisper | 10.49 | 12 / 184 | 6.52% |
| Cloudflare Whisper Large v3 Turbo | 23.68 | 6 / 184 | 3.26% |

Standard Cloudflare Whisper (`@cf/openai/whisper`) ran once through the same proxy on the same WAV, sent as raw audio using the model's defaults. Its wall time includes reading and uploading the WAV and receiving the response. It took 3.07 seconds longer than local Whisper and made one additional word error. Turbo made five fewer word errors than local Whisper and took 3.2 times as long. Estimated usage was 46 neurons for standard Whisper and 52 for Turbo. Each model was tested once on this clip, and quota consumption was not independently metered. [Cloudflare pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/)

## Segmented audio download

On one 73-second X clip, yt-dlp took 25.03 seconds with one concurrent fragment and 9.75 seconds with four. Both downloads produced 1,186,213-byte files. These timings cover yt-dlp invocation through download completion. TLDR uses four concurrent fragments for segmented downloads.
