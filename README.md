# Opensolr Mail

**Every Fastmail account in one inbox, and all of your mail found in a second.**

Opensolr Mail is a free, open source Android mail client for Fastmail. One unified inbox for all your
accounts, conversations in threads, push notifications you can act on, Fastmail Notes, and your Fastmail
calendars in the phone's calendar app. Connect an Opensolr account and every message you ever received,
with the text inside its attachments, becomes searchable by words and by meaning, with AI answers on top.

**[Download the APK](https://github.com/phpcip/opensolr-mail/releases/latest/download/opensolr-mail.apk)** ·
[Releases](https://github.com/phpcip/opensolr-mail/releases) ·
[Opensolr](https://opensolr.com)

---

## What it does

| | |
|---|---|
| **One inbox for every account** | All Inboxes, Drafts, Sent, Archive, Junk, Trash and Flagged across every Fastmail account, plus each account's own folders. Conversations are grouped in threads, and the list is grouped by day, month, sender, company or account. |
| **Native Fastmail sign-in** | Your Fastmail password and two-factor code are typed on Fastmail's own page, in the phone's browser. The app never sees them and needs no IMAP settings and no app password: it talks JMAP, with OAuth 2.0 and PKCE. |
| **Push you can act on** | New mail arrives at once, and the notification has exactly three actions: Mark as read, Delete, and Reply all, typed right in the notification. |
| **Swipe** | Left to delete, right to flag. Long press selects conversations for bulk actions. |
| **Housekeeping** | Mark all as read, on any view or mailbox. Long press Trash or Junk to empty it for good. |
| **Notes** | Fastmail Notes, read and written from the app. |
| **Calendars** | Each account's Fastmail calendars sync both ways into Android's calendar store, so they show up in Google Calendar or any calendar app. |
| **Search** | With an Opensolr account: words and meaning blended, an AI answer drawn from your own mail, a Fresh switch that favours recent mail, filters (from, to, company, account, folder, year, attachment type, weekday, dates, unread, flagged, answered, attachments), and grouping by date, sender, company or account. |
| **Attachments searched too** | On Wi-Fi, pictures are read with OCR and documents (PDF, Office, OpenDocument, RTF, text, HTML) are turned into text, so a search finds the invoice by the number printed inside it. Archives are never opened. |
| **Updates itself** | Once a day the app looks at the latest release on GitHub, and **Check for updates** in Settings asks on the spot. The app downloads the APK, checks that it is signed with the same key and is really newer, and hands it to Android's installer. A copy installed from Google Play is updated by Play instead. |
| **Seven languages** | English, Deutsch, Français, Español, Română, 日本語, 中文. |

## Search needs an Opensolr account

Mail, notes and calendars work with a Fastmail account alone. Search lives in an **Opensolr Index** that
the app creates in your Opensolr account (`mail_<hash of your Opensolr email>__dense`), so without an
Opensolr account there is no search and no AI answers, and new mail is checked every 15 minutes instead
of arriving at once, because instant push goes through the relay on opensolr.com.

What your plan decides, shown in **Settings → Opensolr account**:

- **Disk space or bandwidth used up:** Opensolr closes the index, and search and indexing stop until you
  upgrade, or until the month resets for bandwidth.
- **Monthly AI requests used up:** search goes on by words only, without search by meaning and without AI
  answers, until the allowance resets. New mail gets its meaning later.
- **A plan without vector search:** search by words only.

## How it works

1. **Fastmail.** The app signs in with OAuth 2.0 + PKCE and keeps the tokens encrypted with an Android
   Keystore key. Mail is synced over JMAP ([RFC 8620](https://www.rfc-editor.org/rfc/rfc8620),
   [RFC 8621](https://www.rfc-editor.org/rfc/rfc8621)) in single batched requests; calendars over CalDAV.
2. **Push.** Each account gets a JMAP PushSubscription pointing at a relay address on opensolr.com that is
   unique to this phone and account. Fastmail encrypts every push with keys that never leave the phone
   ([RFC 8291](https://www.rfc-editor.org/rfc/rfc8291)); the relay forwards the encrypted bytes through
   Firebase Cloud Messaging and cannot read them. The phone decrypts and syncs.
3. **Indexing.** New and changed messages are written to your Opensolr Index with their subject, people,
   folders, flags and text, and a search vector made by Opensolr's embedding service. The whole history is
   walked newest first; deletions and moves follow at once.
4. **Search.** A query goes to your index through Opensolr's `{!hybrid}` parser: an edismax query over the
   words and a kNN query over the vectors, blended with the balance set in Settings.

## Your data

- **Your mail stays between Fastmail, your phone and your own Opensolr Index.** No analytics, no ads, no
  tracking.
- **What reaches Opensolr:** only with an Opensolr account connected, the text of your messages goes into
  your index, the text to embed goes to Opensolr's embedding service, and attachments are sent to be read
  into text. The index is yours: you can see it, back it up or empty it in the Opensolr control panel.
- **Passwords are only typed in the browser.** Tokens and the Opensolr API key are stored encrypted with an
  Android Keystore key and excluded from phone backups.

## Requirements

- Android 8.0 (API 26) or newer.
- A [Fastmail](https://www.fastmail.com) account.
- For search: an [Opensolr](https://opensolr.com) account. For search by meaning and AI answers: a plan
  that includes vector search.

## Install

1. On your phone, download
   [opensolr-mail.apk](https://github.com/phpcip/opensolr-mail/releases/latest/download/opensolr-mail.apk).
2. Allow your browser to install it when Android asks.
3. Open **Opensolr Mail** and sign in with Fastmail. Connect Opensolr for search now or later in Settings.

The APK is signed with the Opensolr release key. SHA-256 of the signing certificate:

```
2C:04:4A:A1:4F:A3:A2:8A:4F:6B:66:E4:08:B5:D3:93:63:5B:EA:30:DC:2D:62:6A:44:9F:63:EE:5C:7C:A9:C0
```

## Build from source

```bash
git clone https://github.com/phpcip/opensolr-mail.git
cd opensolr-mail
./gradlew assembleGithubDebug
```

JDK 17 or newer and the Android SDK (platform 36) are needed. Push needs a Firebase project of your own;
without it the app builds and runs with push off. Details: [building](docs/building.md).

## Project layout

```
app/src/main/java/com/opensolr/mail/
  auth/     Fastmail and Opensolr sign-in (OAuth 2.0 + PKCE) and the callback activity
  data/     accounts, preferences, Keystore encryption, the local mail database, models
  dav/      CalDAV and the Android calendar sync adapter
  index/    the Opensolr Index: creation, configuration, the indexer
  jmap/     JMAP client, sync, actions queue, notes, replies
  net/      Opensolr API, HTTP, updates
  push/     push subscriptions, Web Push decryption, the FCM service
  search/   query building and the AI answer prompt
  sync/     WorkManager workers and notifications
  ui/       Jetpack Compose screens and theme
solr/conf/  schema.xml, solrconfig.xml and analyzer files uploaded to the index
```

## License

[MIT](LICENSE) © Opensolr SRL. Space Grotesk font: SIL Open Font License, see
[third_party/space-grotesk/OFL.txt](third_party/space-grotesk/OFL.txt).

Contributing: see [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).
