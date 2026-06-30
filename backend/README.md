# LobsterPot Positions Worker

This Worker powers the clan world map for the RuneLite plugin.

Clients connect to `GET /positions?viewer=RSN` using a WebSocket. A single Durable Object keeps
current member positions in memory and broadcasts compact snapshots to connected clan members.
Positions are not written to KV or permanent storage, and stale positions expire after about
20 seconds if a client stops sending updates.

Posts and reads are checked against the live LobsterPot plugin feed member list. This prevents
non-member names from posting or reading positions through the normal endpoint, but it is not
cryptographic proof of RuneScape account ownership.

## Deploy

1. Use a Cloudflare account with Workers Paid enabled. Durable Objects are not the old KV backend.
2. Log in to Cloudflare:

```bash
npx wrangler login
```

3. Deploy:

```bash
npx wrangler deploy
```

Wrangler will create/apply the Durable Object migration from `wrangler.toml`.

For local testing, run:

```bash
npx wrangler dev
```

For local plugin testing, temporarily point `BACKEND_URL` in `ClanPositionService` at
`http://localhost:8787`.
