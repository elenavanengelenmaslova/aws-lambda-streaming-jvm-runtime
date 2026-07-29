# Publishing `aws-lambda-streaming-core` to Maven Central

One-time setup steps, then a single `git tag` to release.

---

## One-time setup

### 1. Create a Sonatype Central Portal account

- [x] Register at **https://central.sonatype.com** (the new portal — not the legacy OSSRH).

---

### 2. Verify the `nl.vintik` namespace

- [x] Log in to the portal and go to **Namespaces**.
- [x] Click **Add Namespace** and enter `nl.vintik`.
- [x] Copy the DNS TXT record value the portal provides.
- [x] Add the TXT record in your DNS provider for `vintik.nl`.
- [x] Click **Verify** in the portal.

> The namespace must be verified before any artifact under `nl.vintik` can be published.

---

### 3. Generate a GPG signing key

- [x] Generate the key:

  ```bash
  gpg --gen-key
  # choose RSA 4096, set a passphrase, use your real name / email
  ```

- [x] Note down the key ID (or find it later with `gpg --list-secret-keys`).
  Key: `B6CF7D736D2A83A4C6B37EC8252674D63D7F47EA`

- [x] Upload the **public key** to a keyserver so Maven Central can verify signatures:

  ```bash
  gpg --keyserver keyserver.ubuntu.com --send-keys B6CF7D736D2A83A4C6B37EC8252674D63D7F47EA
  ```

- [ ] Export the **private key** as a base64-encoded single line for the GitHub secret.
  The workflow decodes it at runtime, avoiding newline-handling issues with multiline secrets:

  ```bash
  gpg --export-secret-keys --armor B6CF7D736D2A83A4C6B37EC8252674D63D7F47EA \
    | base64 | tr -d '\n'
  ```

  Copy the single-line output — that is the value for `GPG_SIGNING_KEY`.
  The publish workflow decodes it with `base64 -d` before passing it to Gradle.

> Never save the private key to a file or commit it. Run the export command and paste directly into the GitHub secret.

---

### 4. Generate a Central Portal user token

- [ ] In the portal: **Account** (top-right) → **Generate User Token**.
- [ ] Copy the **username** and **password** — the password is only shown once.

---

### 5. Add the four GitHub repository secrets

- [ ] Go to **GitHub repo → Settings → Secrets and variables → Actions → New repository secret** and add all four:

  | Secret name | Value |
  |---|---|
  | `MAVEN_CENTRAL_USERNAME` | Portal token username (from step 4) |
  | `MAVEN_CENTRAL_PASSWORD` | Portal token password (from step 4) |
  | `GPG_SIGNING_KEY` | Full contents of `signing-key.asc` (from step 3) |
  | `GPG_SIGNING_PASSWORD` | Passphrase chosen in step 3 |

---

## Releasing a version

- [ ] Update the version in `streaming-core/build.gradle.kts`:

  ```kotlin
  version = "1.0.0"  // change to the desired release version
  ```

- [ ] Commit the version bump, tag the commit, and push:

  ```bash
  git add streaming-core/build.gradle.kts
  git commit -m "chore: bump version to 1.0.0"
  git tag v1.0.0
  git push origin main v1.0.0
  ```

- [ ] Confirm the **publish** workflow passes in GitHub Actions.

The workflow triggers on any `v*` tag, signs the artifacts using the GPG key from `GPG_SIGNING_KEY`, and publishes using `publishAndReleaseToMavenCentral` — no manual "close and release" step required. The version published is whatever is set in `streaming-core/build.gradle.kts`; the tag is used only to trigger the workflow.

---

## Verifying the release

- [ ] Check the portal (~5–10 minutes for Central propagation):
  - https://central.sonatype.com/artifact/nl.vintik/aws-lambda-streaming-core
  - https://search.maven.org/artifact/nl.vintik/aws-lambda-streaming-core
- [ ] Update the version placeholder in `README.md` and `docs/article.md`:
  ```kotlin
  implementation("nl.vintik:aws-lambda-streaming-core:<!-- VERSION -->")
  ```
