# APK signing for GitHub releases

The `Release` workflow (`.github/workflows/release.yml`) builds and signs the
Android APK automatically when you push a tag like `v1.0.0`.

There are two modes:

| Mode | When | Effect on updates |
|---|---|---|
| **Persistent keystore** (recommended) | Repository secrets are set | Every release shares one signature, so a new APK installs **over** the old one and keeps app data/settings. |
| **Ephemeral keystore** (default) | No secrets set | Each release is signed with a fresh key, so Android refuses in-place updates — you must **uninstall** the old version first. |

To get clean updates, set up the persistent keystore **once** as described below.

## 1. Create a keystore (once)

You need a JDK installed (`keytool` ships with it). On Windows PowerShell:

```powershell
keytool -genkeypair -v `
  -keystore parakeet-release.jks `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -alias release
```

It asks for a keystore password and your name/org (any value is fine for a
personal build). Keep the same value for the key password when prompted
(press Enter to reuse the store password). **Back up `parakeet-release.jks`
and the passwords** — losing them means you can never ship an in-place update
again.

## 2. Base64-encode the keystore

GitHub secrets store text, so encode the binary keystore:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("parakeet-release.jks")) `
  | Out-File -Encoding ascii keystore.base64.txt
```

## 3. Add the four repository secrets

Using the GitHub CLI from the repo folder:

```powershell
gh secret set RELEASE_KEYSTORE_BASE64 < keystore.base64.txt
gh secret set RELEASE_KEYSTORE_PASSWORD   # paste the keystore password
gh secret set RELEASE_KEY_ALIAS           # type: release
gh secret set RELEASE_KEY_PASSWORD        # paste the key password
```

Or via the web UI: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | contents of `keystore.base64.txt` |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `release` |
| `RELEASE_KEY_PASSWORD` | the key password |

## 4. Build a release

```powershell
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds the signed APK and attaches it to a new GitHub Release as
`ParakeetFlow-v1.0.0.apk`. Download it on your phone and install (requires
"install from unknown sources").

> Delete `parakeet-release.jks` and `keystore.base64.txt` from any synced/cloud
> folder after uploading — they are your signing identity.
