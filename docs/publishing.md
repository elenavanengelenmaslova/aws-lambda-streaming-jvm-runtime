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
  Key: `D1706CFEB3C8FEE3`

- [x] Upload the **public key** to a keyserver so Maven Central can verify signatures:

  ```bash
  gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
  ```

- [ ] Export the **private key** (needed for the GitHub secret):

  ```bash
  gpg --export-secret-keys --armor D1706CFEB3C8FEE3 > ~/signing-key.asc
  ```

> Keep `signing-key.asc` safe and do not commit it.

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

- [ ] Tag the commit and push:

  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

- [ ] Confirm the **publish** workflow passes in GitHub Actions.

The workflow derives the version from the tag (`v1.0.0` → `1.0.0`), signs the artifacts, and publishes automatically — no manual "close and release" step required.

---

## Verifying the release

- [ ] Check the portal (~5–10 minutes for Central propagation):
  - https://central.sonatype.com/artifact/nl.vintik/aws-lambda-streaming-core
  - https://search.maven.org/artifact/nl.vintik/aws-lambda-streaming-core
- [ ] Update the version placeholder in `README.md` and `docs/article.md`:
  ```
  implementation("nl.vintik:aws-lambda-streaming-core:<!-- VERSION -->")
  ```
