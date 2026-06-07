# Structure

## Current State

```
.
├── .kiro/steering/      # Steering rules (product, tech, structure)
└── docs/
    └── article.md       # The guide being written from this example
```

No application code exists yet. The layout below is the target.

## Target Layout (single module)

This is an example repo with no business logic, so it is a **single Gradle module** — deliberately flatter than MockNest's clean-architecture, multi-module setup. Same setup style, collapsed to one module.

```
.
├── build.gradle.kts            # Single-module build (Kotlin, shadow fat-jar, Kover)
├── settings.gradle.kts
├── gradle.properties
├── src/
│   ├── main/kotlin/            # Handler, API GW request parser, streaming response writer, S3 source, priming hook
│   └── test/kotlin/            # Unit/property tests + LocalStack integration tests
│       └── ...
│   └── test/resources/         # Test data / fixtures
├── deployment/aws/sam/
│   ├── template.yaml           # Lambda + API Gateway, RESPONSE_STREAM, SnapStart
│   ├── samconfig.toml
│   ├── build.sh                # Builds the fat jar
│   └── deploy.sh               # sam build + deploy
├── scripts/
│   └── post-deploy-test.sh     # Verifies >6 MB payload + measures first-byte timing
└── docs/
    ├── article.md              # The published guide
    └── log.md                  # Running log of hiccups, bugs, and fixes (feeds the article)
```

## Component Separation

Even in one module, keep these concerns in separate files/classes so each is independently testable (this is the part the article explains):

- **Handler** — implements `RequestStreamHandler` (`handleRequest(input, output, context)`).
- **Request parsing** — API Gateway proxy event (`InputStream`) → small domain request object.
- **Response protocol writer** — writes metadata JSON, the 8 null-byte delimiter, then body bytes. This is the piece with byte-for-byte round-trip tests.
- **S3 source streaming** — opens the S3 object as an `InputStream` and copies it to the Lambda `OutputStream` with a bounded buffer (no full-object buffering).
- **Priming hook** — CRaC `beforeCheckpoint` warm-up for SnapStart (see tech.md).

## Conventions

- Mirror `main/kotlin` package structure under `test/kotlin`.
- Keep narrative docs in `docs/`. **`docs/log.md` is mandatory** — append every gotcha and fix as you go.
- SAM template, config, and deploy scripts live under `deployment/aws/sam/`; the post-deploy verification script lives under `scripts/`.
- Run `sam validate --template-file deployment/aws/sam/template.yaml` after any template change and confirm exit code 0 before committing.
