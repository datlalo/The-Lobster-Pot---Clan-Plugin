# LobsterPot Positions Worker

This Worker powers the clan world map and bounty submissions for the RuneLite plugin.

Positions and reads are checked against the live LobsterPot plugin feed member list. This prevents
non-member names from posting or reading through the member endpoints, but it is not cryptographic
proof of RuneScape account ownership.

## World map (`ClanPositionRoom`)

Clients connect to `GET /positions?viewer=RSN` using a WebSocket. A single Durable Object keeps
current member positions in memory and broadcasts compact snapshots to connected clan members.
Positions are not persisted, and stale positions expire after `POSITION_TTL_MS` (90 seconds) if a
client stops sending updates.

## Bounty submissions (`BountyRoom`)

- `POST /bounty` `{rsn, bountyId, note}` - a member reports completing a bounty. Membership is
  checked against the feed member list, `bountyId` must be an active bounty from the feed, and the
  submission is dedup'd (one un-consumed per member+bounty) and rate-limited. Stored durably in a
  SQLite-backed Durable Object as `pending`. Returns 200/400/403/409/429 JSON.
- `GET /bounty/pending` - bot-only; returns pending submissions. Requires
  `Authorization: Bearer <BOT_TOKEN>`.
- `POST /bounty/ack` `{ids:[...]}` - bot-only; marks submissions consumed. Requires the bearer token.

The Discord bot pulls pending submissions, records them as pending claims (points only count after
an admin approves in Discord), then acks them. Consumed rows are purged after a short retention
window. The bot must also publish the active `bounties[]` catalog and per-member `pending_bounties[]`
in the feed.

## Deploy

1. Durable Objects with SQLite storage are available on the Workers Free plan; Workers Paid raises
   the request/storage limits.
2. Log in to Cloudflare:

```bash
npx wrangler login
```

3. Set the bot pull/ack secret (never committed, never shipped in the plugin):

```bash
npx wrangler secret put BOT_TOKEN
```

4. Deploy:

```bash
npx wrangler deploy
```

Wrangler will create/apply the Durable Object migrations from `wrangler.toml`.

For local testing, run `npx wrangler dev` and put `BOT_TOKEN=<value>` in a `.dev.vars` file (read
automatically by `wrangler dev`, and git-ignored). For local plugin testing, temporarily point
`BACKEND_URL` in `com.lobsterpot.Backend` at `http://localhost:8787`.
