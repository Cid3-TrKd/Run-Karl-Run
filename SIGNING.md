# Signing

Every Run-Karl-Run APK is signed with one dedicated release key. That key is the
app's identity to Android, and it is the reason an update can be installed over
an existing copy.

## The identity

| | |
|---|---|
| Distinguished name | `CN=Run-Karl-Run, OU=A1-143, O=cid3trk, C=US` |
| Certificate SHA-256 | `1B:9D:AD:C4:50:39:6F:5A:FA:BE:BD:BB:7F:5E:BA:6D:62:8D:D8:11:D6:30:C7:15:6A:9E:70:93:26:AC:F6:BC` |
| Certificate SHA-1 | `5F:FF:09:B6:5E:2C:D7:77:CE:FD:3A:53:97:7D:8E:37:AB:38:E1:84` |
| Key | RSA 4096, signature algorithm SHA384withRSA |
| Validity | 2026-10-21 → 2056-10-13 |
| Self-signed | yes (issuer = subject) |

Self-signed is normal for Android. There is no certificate authority in this
chain: the certificate *is* the app's identity, and it is the same one from the
first release onward.

## Verify what you downloaded

```sh
apksigner verify --print-certs RunKarlRun-1.0.apk
```

The `Signer #1 certificate SHA-256 digest` line must be exactly:

```
1b9dadc450396f5afabebdbb7f5eba6d628dd811d630c7156a9e709326acf6bc
```

That is the lowercase form of the fingerprint above. If it differs, the APK was
not signed by this project — do not install it. `SHA256SUMS` on each release
covers the file bytes; this covers the signer, and they answer different
questions.

## What this means for updates

Android will only install an update signed by the same key, for the same package
(`ai.a1143.runkarlrun`). That is a platform guarantee, not a setting. So:

- The key stays the same for the life of the app.
- The private key is not in this repository and never will be. Publishing it
  would let anyone sign an APK Android accepts as an update.
- It is kept offline, in more than one place, because losing it means the app can
  never be updated in place again — every existing install would have to be
  uninstalled first.

## Not Play-signed

This app is not distributed through Google Play, so there is no Play App Signing
key involved and no upload key distinct from the release key. The certificate
above is the only one ever used.
