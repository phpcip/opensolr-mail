# Security

## Reporting a vulnerability

Please report security issues privately to **support@opensolr.com** with "Opensolr Mail security" in the
subject. Include the version of the app, the steps to reproduce, and what an attacker could achieve.
Please do not open a public GitHub issue for a vulnerability.

## Scope

- This app: the code in this repository and the signed APKs published under
  [Releases](https://github.com/phpcip/opensolr-mail/releases).
- The Opensolr endpoints the app uses: the sign-in at `/app/mail/sso` and `/app/mail/token`, the push relay
  under `/app/mail/`, and the Opensolr REST API calls in `net/OpensolrApi.kt`.

## Verifying a download

Every release APK is signed with the same key. Check the certificate before installing:

```bash
apksigner verify --print-certs opensolr-mail.apk
```

The SHA-256 digest must be:

```
2c044aa14fa3a28a4f6b66e408b5d393635bea30dc2d626a449f63ee5c7ca9c0
```

The same fingerprint is published by opensolr.com in
[/.well-known/assetlinks.json](https://opensolr.com/.well-known/assetlinks.json), which is what lets Android
hand the Opensolr sign-in callback to this app and no other.
