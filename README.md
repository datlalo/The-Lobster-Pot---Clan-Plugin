# The Lobster Pot — RuneLite Plugin

A clan companion plugin for [RuneLite](https://runelite.net). After logging in with their clan
website account, members can:

- **View their rank progress** (current rank, points, next rank, requirements, eligibility breakdown).
- **Request a rank-up** — submits a staff-reviewed claim with their justification.
- **See upcoming clan events and news.**
- Read the clan **Message of the Day** in chat when they log in.

## How it works (architecture & security)

The clan's Supabase **Admin API** uses an all-powerful Discord-bot key (it can set ranks, delete
members, adjust points). A RuneLite plugin runs on members' machines and is trivially decompiled,
so **this plugin ships no secrets and never talks to the Admin API directly.**

Instead it talks to the clan's **public member API**, which is account-scoped: the member logs in
with their website username/password, the plugin exchanges that for a **JWT** and uses it as a
bearer token. The **password is never stored** — only the returned tokens are persisted (see below).

### Member API endpoints used

Base URL is set in the plugin settings (defaults to the live member API).

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/login` | Exchange username/password for a JWT. |
| `GET` | `/me` | Profile: points, current rank, linked RSNs. |
| `GET` | `/rank/available` | Next rank, eligibility, reasons, pending claim. |
| `POST` | `/rank/claim` | Submit a rank-up request (`{ claim_text }`). |
| `GET` | `/events?upcoming=true` | Upcoming events. |
| `GET` | `/news` | Latest news posts. |
| `GET` | `/broadcasts` | Active broadcasts; each is shown in chat on login as the MOTD. |

> `/broadcasts` returns `{ "broadcasts": [ { "id", "message", "is_active", "created_at" } ] }`.
> On login the plugin prints every active broadcast's message to chat (once per game login).

### Credential & token storage

- The plugin stores **only** the JWT access/refresh tokens and expiry (under non-UI config keys),
  never the password.
- RuneLite syncs plugin config to its servers for logged-in RuneLite accounts, so tokens may be
  uploaded — they are short-lived and revocable, unlike a password.
- The access token expires after ~1 hour. There is no documented refresh route, so when it expires
  the plugin clears the session and prompts the member to log in again from the panel.

## Configuration

| Setting | Default | Description |
|---|---|---|
| API base URL | live member API | Base URL of the public member API (no trailing slash). |
| Enable rank-up requests | `true` | Show the rank-up request button. |
| Show MOTD on login | `true` | Print the active broadcast to chat once per login (requires being logged in to the member API). |

## Building

Requires a **JDK 11+**. From the project root:

```sh
./gradlew build      # compile + run unit tests
./gradlew run        # launch a dev RuneLite client with the plugin loaded
```

## Plugin Hub submission

Follows the [RuneLite Plugin Hub](https://github.com/runelite/plugin-hub) requirements: BSD 2-Clause
licensed, Java 11, no bundled third-party dependencies (`build=standard`), no secrets, icons loaded
via `getResourceAsStream`. To submit, push this repo to GitHub and add a file under `plugins/` in a
fork of `runelite/plugin-hub` pointing at your repo URL and commit hash.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
