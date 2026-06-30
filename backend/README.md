# LobsterPot Positions Worker

This Worker stores recent clan member map positions for the RuneLite plugin.
Entries expire after 120 seconds.

Posts and reads are checked against the live LobsterPot plugin feed member list.
This prevents non-member names from posting or reading positions through the normal
endpoint, but it is not cryptographic proof of RuneScape account ownership.

## Deploy

1. Log in to Cloudflare:

```bash
npx wrangler login
```

2. Create a Cloudflare KV namespace:

```bash
npx wrangler kv namespace create POSITIONS
```

3. Copy the returned namespace id into `wrangler.toml`.
4. Deploy:

```bash
npx wrangler deploy
```

5. Replace `BACKEND_URL` in `ClanPositionService` with the deployed Worker URL from Wrangler.

For local testing, run:

```bash
npx wrangler dev
```

For local plugin testing, temporarily point `BACKEND_URL` at `http://localhost:8787`.
