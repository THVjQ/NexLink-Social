# NexLink Social

An invite-only, end-to-end-encrypted messenger for Android, built on
[Matrix](https://matrix.org). Package `com.thvjq.nexlink.social`.

Companion to [NexLink](https://github.com/THVjQ/NexLink) — the SMS, dialler and
inbox app — but the two are **independent applications** with separate package
names, separate Play listings and separate repositories. Installing one does not
install or change the other.

## Why this repository is separate

The two apps shared a Gradle build until the split. They never shared code:
neither `:app` nor `:shared` nor `:wear` declared a dependency on a social
module, and no source file in either tree imported the other's package. The
first success criterion in the specification (§1.5) is that *a NexLink user who
never enables Social is unaffected*, and a repository boundary is the strongest
form that rule can take.

Two invariant checks guard NexLink rather than Social, so they now run in the
other repository. `tools/check-invariants.sh` reports them as `SKIP` here rather
than passing vacuously — a check that quietly stops checking is the accident the
invariants exist to prevent.

## Modules

| module | what |
|---|---|
| `:social` | the application — `com.thvjq.nexlink.social` |
| `:social-ui` | chat, onboarding and settings screens |
| `:social-core` | Matrix session, store, crypto, sync |
| `:social-rtc` | calls (§17–§19) |
| `:social-contract` | Parcelable IPC types. No SDK, no network. |
| `:rustls-tls` | vendored TLS verifier for the Rust SDK — see its `VENDORED.md` |

The UI is programmatic Kotlin views — no XML layouts and no Compose.

## Building

Two files are deliberately not in version control and must be supplied:

- `social/google-services.json` — FCM configuration (§13.3)
- `keystore.properties` and the release keystore — signing

```bash
tools/legal/build-legal.py                 # assemble docs/legal/dist — do this FIRST
./gradlew :social:assembleDebug            # debug APK
./gradlew :social:bundleRelease            # Play bundle
./gradlew :social:assembleRelease -Pabi=arm64-v8a   # single-ABI APK for sideloading
tools/check-invariants.sh                  # the §2.8 invariants
```

`copyLegalDocs` fails the build if `docs/legal/dist/` is missing. The app ships
the **assembled** documents, each carrying Appendix A; a document without it
defines none of the terms it uses, and "Terrorism" is one of them.

## Documentation

`docs/social/` holds the specification the code is written against; sections are
§-numbered and referenced from commit messages and comments throughout.
`docs/legal/` holds the legal pack — see its README before changing anything
there. Server deployment lives in `infra/`, including the Synapse modules that
back invites and the moderation console, and the sidecar that keeps them in sync
with this repository.

> ⚠️ **`docs/legal/` and `tools/legal/` are currently duplicated in
> THVjQ/NexLink.** The pack covers all four NexLink products and the website
> generator was run from there, but the Social app is what *ships* it, so it had
> to come across for this repository to build. Two copies of a legal document is
> how they come to disagree — pick one home and delete the other before either
> is edited again.

## Licence

See `LICENSE`.
