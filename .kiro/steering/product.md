# Product

A minimal, runnable **"hello world" example** that proves **HTTP response streaming** works from a **Kotlin/JVM AWS Lambda** behind **Amazon API Gateway**.

## What it does

A single Lambda streams a **large object (~15 MB, well over the old 6 MB buffered limit)** from **S3**, through API Gateway response streaming, to the client — **without ever holding the whole object in memory**. The S3 object's `InputStream` is copied to the Lambda `OutputStream` with a bounded buffer.

## Why it exists

The goal is a clear, reproducible **guide for adding response streaming to a Kotlin/Java Lambda**. AWS's streaming helpers (e.g. `awslambda.HttpResponseStream.from()`) are Node.js-only, so on the JVM the API Gateway streaming response protocol must be implemented by hand. JS/TS examples are everywhere; JVM ones are not.

This example is the simplified, real (non-mock) counterpart of the streaming mechanism in [MockNest Serverless](https://github.com/elenavanengelenmaslova/mocknest-serverless). MockNest streams S3 blobs through a WireMock "dribble" path, which adds complexity. Here there is no mock engine and no business logic — just the streaming mechanism, wired end to end.

Reference: [AWS Lambda — Response streaming](https://docs.aws.amazon.com/lambda/latest/dg/configuration-response-streaming.html).

## Scope (keep it simple)

- One Lambda, one S3 source object, one streaming endpoint. **As simple as possible.**
- **Single Gradle module** — no clean architecture, no module split. The whole thing is AWS-specific glue, so layering adds no value here.
- No auth/business logic beyond what is needed to demonstrate streaming.
- Reuse MockNest's project setup, coding standards, cold-start optimizations (SnapStart + priming + tiered compilation), and SAM/pipeline deploy approach — just collapsed to one module.

## Success criteria (must be proven)

The example is only "done" when all of these hold against a **deployed** endpoint:

1. A payload **larger than 6 MB** (the ~15 MB object) is delivered successfully.
2. **First byte arrives early** — well before the full response completes (proves real streaming, not buffering at any layer).
3. **Lambda memory does not grow with response size** — the body is never materialized as a `String`/`ByteArray`.

Proven with layered tests (protocol/unit, handler/integration with LocalStack, and a **post-deploy script** that measures first-byte timing), mirroring the MockNest test strategy.

## Knowledge capture

Every hiccup, bug, gotcha, or fix encountered while building this (e.g. the `STREAM` vs `RESPONSE_STREAM` trap, status-code-committed-early, runtime buffering) MUST be recorded in **`docs/log.md`**, so it can feed the final article.
