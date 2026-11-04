# TL;DR

Watch less. Know more. Android client and a single-user FastAPI service for transcript-grounded video summaries.

## Design

The app uses the approved monochrome Utility design: focused Home input, a separate searchable Library, and Summary / Check result tabs. A bottom question composer supports contextual follow-ups; View transcript is in the result header menu. New checks open with a server-generated, selectable overview of the findings: one paragraph of at most 80 words, using the same body styling as Summary. Full explanations remain in the details. Existing reports remain readable with their original text. View details opens the claim list; each claim opens a detail sheet with its verdict, explanation, sources and optional excerpts. Ask about this claim transfers that claim into the composer, with a clear control to return to general video questions. The floating shortcut supports quick summaries, icon actions for copy/share/original, and expandable claim reports with source links. Minimize preserves the current result and fact-check state; Open full report opens the same analysis on its Check tab. Both surfaces follow the app's System / Light / Dark appearance setting.

`Minimal-Utility-Design-Guide.pdf` in the repository root is the reusable design reference for other projects. It includes light/dark tokens, typography and spacing, screen and popup patterns, accessibility and state guidance, and a ready-to-paste build brief. Regenerate it with `python tools/generate_design_guide.py` (requires ReportLab; uses Segoe UI on Windows, with Helvetica as a fallback).

## Current status

The debug build supports shared links, selected media files, persistent jobs, English-only Whisper Base (base.en) INT8 transcription, OpenCode-compatible summaries, history, optional Brave fact-checks, questions, and an opt-in floating shortcut. Paste a link or text into Home or the floating shortcut. Pasted text goes directly to summarization. Accepted video links include X (`x.com` and `twitter.com`), YouTube, TikTok, and Instagram. Arbitrary `t.co` short links are not accepted because they can redirect outside X; use the post's X or Twitter URL. A public YouTube video, a public spoken X video, and the user's public TikTok podcast clip were processed through the live server. The YouTube test also included a follow-up question and a Brave fact-check; one word in its transcript was mistranscribed. Instagram media retrieval was exercised, but the samples did not contain detectable speech; a complete spoken Reel has not yet been verified. Another public TikTok sample was blocked by TikTok. Individual platforms can change extraction behavior or require login; the app does not bypass platform access controls.

## Server configuration

The deployment uses two containers: the FastAPI worker on a dedicated internal network, and a narrow HTTPS CONNECT proxy that refuses private/non-global addresses and pins DNS results. This separation limits the extractor's network access. Do not set `TLDR_ISOLATED_NETWORK` on a directly connected service. The API is published only to `127.0.0.1:18766` on the server. One process and one worker are required for the SQLite queue.

1. On the Ubuntu server, edit `~/tldr/deployment/.env` and set `LLM_API_KEY` and `BRAVE_SEARCH_API_KEY`. Keep this file private (`chmod 600`). Never enter these keys in Android.
2. From `~/tldr`, run `docker compose -f deployment/compose.yml up -d --build`.
3. Generate a one-time code with `docker compose -f deployment/compose.yml exec api python -m app.pair_device`. Enter the nine-digit code in TLDR Settings within five minutes. Pairing replaces the previous device credential, and five failed attempts invalidate the code. The credential itself is generated and stored automatically. To revoke access, pair a new device or run `docker compose -f deployment/compose.yml exec api python -m app.create_token` and keep the generated value private.
4. In your Cloudflare tunnel configuration, add the hostname `tldr.currenta.tech` pointing to `http://localhost:18766` on this server. Preserve existing routes. Configure DNS and verify `https://tldr.currenta.tech/api/v1/health` responds over HTTPS. The tunnel configuration is account-managed, so it is not modified by the Compose project.
5. In the app's Settings, use `https://tldr.currenta.tech` and the pairing code, then use Test connection. OpenCode's documentation says DeepSeek V4.1 Flash requires enabling the Global region in workspace privacy settings. The user confirmed permission for TLDR's use of OpenCode Go; check current provider terms and availability.

The first transcription downloads the selected `base` multilingual model to the persistent data volume. It may take longer than subsequent runs. Model/provider keys, tunnel credentials and device credentials must not be committed or shared. `docker compose ... logs` may contain generic error traces but does not intentionally log API keys or transcripts.

Useful commands from `~/tldr`:

```sh
docker compose -f deployment/compose.yml ps
docker compose -f deployment/compose.yml logs --tail=100 api
docker compose -f deployment/compose.yml restart api
docker compose -f deployment/compose.yml down
```

The last command stops only TLDR's containers and preserves the named data volume. Back up the volume before changing deployment versions. For rollback, keep a copy of the previous source/image and run Compose with it; do not remove the volume. SQLite, model cache and pending uploaded files live in the volume. Uploaded files are removed after successful processing or when a failed/cancelled analysis is deleted; delete unwanted failed jobs to reclaim space.

## Android

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` or transfer it to the phone and allow installation from the selected source. The device credential is encrypted with Android Keystore; the app requires HTTPS. If the phone's DNS cannot resolve the backend hostname, TLDR sends that hostname to Cloudflare's encrypted DNS service as a fallback. Use Android's share sheet to send text containing a supported URL to TLDR, or choose a media file from Home. The overlay needs explicit permission in Settings; return to Settings and tap Enable again after granting it. Use **Add to Quick Settings** in the app or add TLDR from Android's Quick Settings editor to toggle the bubble from the control panel. Drag the bubble to the bottom X to hide it; this does not cancel a server job. It cannot automatically read links from other apps.

Build with Android Studio or the Gradle wrapper and JDK 17+:

```sh
cd android
./gradlew :app:assembleDebug
```

## Tests

Create a Python environment, install `backend/requirements.txt`, and run `cd backend && python -m pytest`. The automated tests mock external services and Whisper inference; they do not establish real integration availability or summary quality. No timing guarantee follows from the prior Whisper benchmark.

## Limits

Summaries are transcript-grounded, not independently verified. Fact-checks are optional. Videos without specific, externally verifiable factual claims still receive a summary and support questions; Check explains that no factual claims were identified and skips searching. Claim detection can be imperfect. Fact-checks are bounded and based on available search results and retrieved page text; sources may be incomplete or outdated. Check the linked sources. Questions use the stored transcript, the existing fact-check report when available, and up to six prior completed question/answer pairs supplied by the client. Conversation history is kept in the current result view and restored on rotation; it is not stored on the server. New analyses generate a single quick summary plus a title, language and claims for the separate fact-check tool. Longer summary variants, key points and notes are no longer generated. Existing saved analyses remain readable without regeneration. The server retains job data until deleted. Active Whisper inference cannot be interrupted immediately; a cancellation takes effect after inference returns. Downloads and processing are bounded by configured size and duration limits, but upstream availability, supported formats and rate limits vary.
