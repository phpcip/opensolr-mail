# Contributing to Opensolr Mail

Bug reports, fixes, features and documentation are all welcome.

**Become a contributor:** to join Opensolr Mail, or any other Opensolr open source project, write to
[support@opensolr.com](mailto:support@opensolr.com?subject=Contributor%20request%3A%20Opensolr%20Mail).
Tell us who you are, which project, and what you would like to work on. Issues and pull requests on GitHub
are open to everyone without asking first.

## How a change gets in

1. **Open an issue** describing the bug or the idea, so nobody does the same work twice.
2. **Fork the repository and create a branch** for that one change.
3. **Make the change** following the conventions below, and build it (`./gradlew assembleGithubDebug`)
   with no new warnings.
4. **Try it on a real phone**: sign in, read, send, search, and whatever your change touches.
5. **Open a pull request** saying what changed, why, and how you checked it.

## Conventions

- Kotlin official style, 4-space indentation.
- Short comments, only where a line needs one. Longer explanations go in the documentation.
- Keep the layers: screens never talk to the network, `net/` and `jmap/` never touch the UI.
- Work where the data lives: one batched JMAP request instead of one per message, one faceted Solr query
  instead of counting on the phone, never a request inside a loop.
- No new dependency without a reason in the pull request, and never one that sends data anywhere.
- The design stays flat: 2 dp corners, one accent colour, hairlines instead of shadows, no text smaller
  than 14.

## Security rules every change keeps

- User text reaches Solr only as a bound parameter (`v=$uq`), filter values only through bound `{!terms}`.
- Tokens and keys are stored only through `SecureStore`, never logged, never put in a URL.
- HTTPS only.
- No analytics, advertising, tracking or crash-reporting code.
- Vulnerabilities are reported privately as described in [SECURITY.md](SECURITY.md).
