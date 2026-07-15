import { DurableObject } from 'cloudflare:workers';

// Cloudflare Worker - lobsterpot-positions
//
// GET /positions?viewer=RSN with WebSocket upgrade
// Stores live positions in a single Durable Object instance. Positions are kept
// in memory only and expire quickly if the client stops sending updates.

const MAX_COORDINATE = 64000;
const MAX_ACTIVITY_LENGTH = 80;
const MEMBER_FEED_URL = 'https://raw.githubusercontent.com/datlalo/lobsterpot-plugin-feed/refs/heads/main/plugin-feed.json';
const MEMBER_CACHE_MS = 60000;
const POSITION_TTL_MS = 90000;
const MIN_POSITION_UPDATE_MS = 8000;
const BROADCAST_INTERVAL_MS = 5000;

// Bounty submissions: members POST completions, the Discord bot pulls + acks them.
const MAX_NOTE_LENGTH = 280;
const MAX_BOUNTY_ID_LENGTH = 64;
const BOUNTY_RATE_WINDOW_MS = 60000; // per-member submission window
const BOUNTY_RATE_MAX = 5; // max submissions per member per window
const BOUNTY_PENDING_LIMIT = 200; // max rows returned to the bot per pull
const BOUNTY_RETENTION_MS = 7 * 24 * 60 * 60 * 1000; // purge consumed rows after 7 days

let memberCache = {
	expiresAt: 0,
	keys: new Set(),
};

let bountyCache = {
	expiresAt: 0,
	ids: new Set(),
};

export class ClanPositionRoom extends DurableObject {
	constructor(ctx, env) {
		super(ctx, env);
		this.sessions = new Map();
		this.positions = new Map();
		this.lastBroadcastAt = 0;
		this.broadcastTimer = null;

		for (const socket of this.ctx.getWebSockets()) {
			const session = socket.deserializeAttachment();
			if (!session || !session.key) {
				continue;
			}

			this.sessions.set(socket, session);
			if (session.position && isFreshPosition(session.position, Date.now())) {
				this.positions.set(session.key, session.position);
			}
		}
	}

	async fetch(request) {
		const url = new URL(request.url);
		if (request.method !== 'GET' || url.pathname !== '/positions') {
			return new Response('Not Found', { status: 404 });
		}

		if (request.headers.get('Upgrade') !== 'websocket') {
			return new Response('Expected WebSocket upgrade', { status: 426 });
		}

		const viewer = (url.searchParams.get('viewer') || '').trim();
		const key = memberKey(viewer);
		if (!key) {
			return new Response('Missing viewer', { status: 400 });
		}

		let memberKeys;
		try {
			memberKeys = await clanMemberKeys();
		} catch {
			return new Response('Member feed unavailable', { status: 503 });
		}
		if (!memberKeys.has(key)) {
			return new Response('Forbidden', { status: 403 });
		}

		const pair = new WebSocketPair();
		const [client, server] = Object.values(pair);
		const session = {
			connectionId: crypto.randomUUID(),
			key,
			rsn: viewer,
			lastUpdateAt: 0,
			position: null,
		};

		this.ctx.acceptWebSocket(server);
		this.sessions.set(server, session);
		server.serializeAttachment(session);
		this.sendPositions(server, memberKeys);

		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(socket, message) {
		const session = this.sessions.get(socket);
		if (!session || typeof message !== 'string') {
			return;
		}

		let memberKeys;
		try {
			memberKeys = await clanMemberKeys();
		} catch {
			socket.close(1011, 'Member feed unavailable');
			this.removeSession(socket, true);
			return;
		}
		if (!memberKeys.has(session.key)) {
			socket.close(1008, 'Forbidden');
			this.removeSession(socket, true);
			return;
		}

		let data;
		try {
			data = JSON.parse(message);
		} catch {
			socket.close(1003, 'Bad JSON');
			this.removeSession(socket, true);
			return;
		}

		if (data && data.type === 'clear') {
			this.removeSessionPosition(socket);
			this.queueBroadcast(memberKeys);
			return;
		}

		const now = Date.now();
		if (now - session.lastUpdateAt < MIN_POSITION_UPDATE_MS) {
			return;
		}
		if (!isValidPosition(data) || memberKey(data.rsn) !== session.key) {
			socket.close(1008, 'Invalid position');
			this.removeSession(socket, true);
			return;
		}

		const position = {
			connectionId: session.connectionId,
			key: session.key,
			rsn: String(data.rsn).trim(),
			x: Math.trunc(data.x),
			y: Math.trunc(data.y),
			plane: Math.trunc(data.plane),
			world: Math.trunc(data.world),
			activity: cleanActivity(data.activity),
			updatedAt: now,
		};

		session.lastUpdateAt = now;
		session.position = position;
		socket.serializeAttachment(session);
		this.positions.set(session.key, position);
		this.queueBroadcast(memberKeys);
	}

	async webSocketClose(socket) {
		this.removeSession(socket, false);
		this.queueBroadcast();
	}

	async webSocketError(socket) {
		this.removeSession(socket, false);
		this.queueBroadcast();
	}

	removeSession(socket, clearPosition) {
		if (clearPosition) {
			this.removeSessionPosition(socket);
		}
		this.sessions.delete(socket);
	}

	removeSessionPosition(socket) {
		const session = this.sessions.get(socket);
		if (!session) {
			return;
		}

		const current = this.positions.get(session.key);
		if (current && current.connectionId === session.connectionId) {
			this.positions.delete(session.key);
		}
		session.position = null;
		try {
			socket.serializeAttachment(session);
		} catch {
		}
	}

	queueBroadcast(memberKeys) {
		const now = Date.now();
		const waitMs = Math.max(0, BROADCAST_INTERVAL_MS - (now - this.lastBroadcastAt));
		if (waitMs === 0) {
			this.broadcastPositions(memberKeys).catch(() => {});
			return;
		}

		if (this.broadcastTimer) {
			return;
		}

		this.broadcastTimer = setTimeout(() => {
			this.broadcastTimer = null;
			this.broadcastPositions().catch(() => {});
		}, waitMs);
	}

	async broadcastPositions(memberKeys) {
		this.lastBroadcastAt = Date.now();
		let keys = memberKeys;
		if (!keys) {
			try {
				keys = await clanMemberKeys();
			} catch {
				return;
			}
		}

		this.removeStalePositions();
		for (const [socket, session] of this.sessions) {
			if (!keys.has(session.key)) {
				socket.close(1008, 'Forbidden');
				this.removeSession(socket, true);
				continue;
			}
			this.sendPositions(socket, keys);
		}
	}

	sendPositions(socket, memberKeys) {
		const session = this.sessions.get(socket);
		if (!session) {
			return;
		}

		this.removeStalePositions();
		const visiblePositions = [];
		for (const position of this.positions.values()) {
			if (position.key === session.key || !memberKeys.has(position.key)) {
				continue;
			}
			visiblePositions.push(publicPosition(position));
		}
		socket.send(JSON.stringify(visiblePositions));
	}

	removeStalePositions() {
		const now = Date.now();
		for (const [key, position] of this.positions) {
			if (!isFreshPosition(position, now)) {
				this.positions.delete(key);
			}
		}
	}
}

// Durable, SQLite-backed queue of bounty completion submissions. A single named instance serializes
// writes so dedup + rate limiting are race-free. The Discord bot pulls pending rows, records them as
// pending claims, then acks them; consumed rows are purged after a short retention window.
export class BountyRoom extends DurableObject {
	constructor(ctx, env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		this.sql.exec(
			`CREATE TABLE IF NOT EXISTS submissions (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				rsn_key TEXT NOT NULL,
				rsn TEXT NOT NULL,
				bounty_id TEXT NOT NULL,
				note TEXT,
				status TEXT NOT NULL,
				created_at INTEGER NOT NULL,
				consumed_at INTEGER
			)`
		);
		this.sql.exec('CREATE INDEX IF NOT EXISTS idx_submissions_lookup ON submissions (rsn_key, bounty_id, status)');
	}

	submit({ rsnKey, rsn, bountyId, note, rejectedInFeed }) {
		const now = Date.now();

		const recent = this.sql
			.exec('SELECT COUNT(*) AS n FROM submissions WHERE rsn_key = ? AND created_at > ?', rsnKey, now - BOUNTY_RATE_WINDOW_MS)
			.toArray()[0];
		if (recent && recent.n >= BOUNTY_RATE_MAX) {
			return { code: 429, status: 'rate_limited' };
		}

		// A pending row (queued, awaiting bot pickup) always blocks - this is the core dedup and also
		// stops a rapid re-click from churning the queue while the feed is still stale.
		const pending = this.sql
			.exec("SELECT id FROM submissions WHERE rsn_key = ? AND bounty_id = ? AND status = 'pending' LIMIT 1", rsnKey, bountyId)
			.toArray();
		if (pending.length > 0) {
			return { code: 409, status: 'already_submitted' };
		}

		// A consumed row means the bot picked this claim up and it's awaiting an admin decision, so we
		// normally block re-submission. Exception: if the feed shows this member's last claim for this
		// bounty was rejected, the claim is decided - clear the stale row so a retry is queued fresh.
		// (The bot's /bounty/reset does the same thing explicitly; this makes retries self-healing.)
		const consumed = this.sql
			.exec("SELECT id FROM submissions WHERE rsn_key = ? AND bounty_id = ? AND status = 'consumed' LIMIT 1", rsnKey, bountyId)
			.toArray();
		if (consumed.length > 0) {
			if (!rejectedInFeed) {
				return { code: 409, status: 'already_submitted' };
			}
			this.sql.exec("DELETE FROM submissions WHERE rsn_key = ? AND bounty_id = ? AND status = 'consumed'", rsnKey, bountyId);
		}

		this.sql.exec(
			'INSERT INTO submissions (rsn_key, rsn, bounty_id, note, status, created_at) VALUES (?, ?, ?, ?, ?, ?)',
			rsnKey,
			rsn,
			bountyId,
			note || '',
			'pending',
			now
		);
		return { code: 200, status: 'submitted' };
	}

	listPending(limit) {
		return this.sql
			.exec(
				"SELECT id, rsn, rsn_key, bounty_id, note, created_at FROM submissions WHERE status = 'pending' ORDER BY id LIMIT ?",
				limit
			)
			.toArray();
	}

	ack(ids) {
		if (!Array.isArray(ids) || ids.length === 0) {
			return { consumed: 0 };
		}
		const validIds = ids.map((x) => Math.trunc(Number(x))).filter((n) => Number.isFinite(n));
		if (validIds.length === 0) {
			return { consumed: 0 };
		}

		const now = Date.now();
		const placeholders = validIds.map(() => '?').join(',');
		// Count the rows we will actually consume by querying first (rowsWritten counts index writes
		// too, so it can't be used as a logical row count).
		const consumed = this.sql
			.exec(`SELECT COUNT(*) AS n FROM submissions WHERE status = 'pending' AND id IN (${placeholders})`, ...validIds)
			.toArray()[0].n;
		if (consumed > 0) {
			this.sql.exec(
				`UPDATE submissions SET status = 'consumed', consumed_at = ? WHERE status = 'pending' AND id IN (${placeholders})`,
				now,
				...validIds
			);
		}
		this.sql.exec("DELETE FROM submissions WHERE status = 'consumed' AND consumed_at < ?", now - BOUNTY_RETENTION_MS);
		return { consumed };
	}

	// Clears a member's submissions for one bounty so they can claim it again. The bot calls this
	// when a claim is rejected (re-enabling a retry) without weakening dedup for undecided claims.
	reset(rsnKey, bountyId) {
		const cleared = this.sql
			.exec('SELECT COUNT(*) AS n FROM submissions WHERE rsn_key = ? AND bounty_id = ?', rsnKey, bountyId)
			.toArray()[0].n;
		if (cleared > 0) {
			this.sql.exec('DELETE FROM submissions WHERE rsn_key = ? AND bounty_id = ?', rsnKey, bountyId);
		}
		return { cleared };
	}
}

export default {
	async fetch(request, env) {
		const url = new URL(request.url);

		if (request.method === 'OPTIONS') {
			return corsResponse(new Response(null, { status: 204 }));
		}

		if (url.pathname === '/positions') {
			const id = env.POSITION_ROOM.idFromName('lobsterpot');
			return env.POSITION_ROOM.get(id).fetch(request);
		}

		if (url.pathname === '/bounty' && request.method === 'POST') {
			return handleBountySubmit(request, env);
		}

		if (url.pathname === '/bounty/pending' && request.method === 'GET') {
			return handleBountyPending(request, env);
		}

		if (url.pathname === '/bounty/ack' && request.method === 'POST') {
			return handleBountyAck(request, env);
		}

		if (url.pathname === '/bounty/reset' && request.method === 'POST') {
			return handleBountyReset(request, env);
		}

		if (url.pathname === '/health') {
			return corsResponse(new Response('OK', { status: 200 }));
		}

		return corsResponse(new Response('Not Found', { status: 404 }));
	},
};

async function handleBountySubmit(request, env) {
	let data;
	try {
		data = await request.json();
	} catch {
		return jsonResponse(400, { status: 'bad_request', message: 'Invalid JSON.' });
	}
	if (!isValidBountySubmission(data)) {
		return jsonResponse(400, { status: 'bad_request', message: 'Invalid bounty submission.' });
	}

	const key = memberKey(data.rsn);
	let memberKeys;
	let bountyIds;
	let rejectedIds;
	try {
		[memberKeys, bountyIds, rejectedIds] = await Promise.all([clanMemberKeys(), activeBountyIds(), rejectedBountyIdsFor(key)]);
	} catch {
		return jsonResponse(503, { status: 'unavailable', message: 'Clan feed unavailable.' });
	}
	if (!memberKeys.has(key)) {
		return jsonResponse(403, { status: 'forbidden', message: 'Only LobsterPot members can submit bounties.' });
	}
	if (!bountyIds.has(data.bountyId)) {
		return jsonResponse(400, { status: 'unknown_bounty', message: 'That bounty is not active.' });
	}

	const id = env.BOUNTY_ROOM.idFromName('lobsterpot');
	const result = await env.BOUNTY_ROOM.get(id).submit({
		rsnKey: key,
		rsn: String(data.rsn).trim(),
		bountyId: data.bountyId,
		note: cleanNote(data.note),
		rejectedInFeed: rejectedIds.has(data.bountyId),
	});
	return jsonResponse(result.code, { status: result.status });
}

async function handleBountyPending(request, env) {
	if (!requireBotAuth(request, env)) {
		return jsonResponse(401, { status: 'unauthorized' });
	}
	const id = env.BOUNTY_ROOM.idFromName('lobsterpot');
	const submissions = await env.BOUNTY_ROOM.get(id).listPending(BOUNTY_PENDING_LIMIT);
	return jsonResponse(200, { submissions });
}

async function handleBountyAck(request, env) {
	if (!requireBotAuth(request, env)) {
		return jsonResponse(401, { status: 'unauthorized' });
	}
	let data;
	try {
		data = await request.json();
	} catch {
		return jsonResponse(400, { status: 'bad_request' });
	}
	const id = env.BOUNTY_ROOM.idFromName('lobsterpot');
	const result = await env.BOUNTY_ROOM.get(id).ack(data && data.ids);
	return jsonResponse(200, { consumed: result.consumed });
}

async function handleBountyReset(request, env) {
	if (!requireBotAuth(request, env)) {
		return jsonResponse(401, { status: 'unauthorized' });
	}
	let data;
	try {
		data = await request.json();
	} catch {
		return jsonResponse(400, { status: 'bad_request' });
	}
	if (!data || typeof data.rsn !== 'string' || typeof data.bountyId !== 'string') {
		return jsonResponse(400, { status: 'bad_request', message: 'rsn and bountyId are required.' });
	}
	const id = env.BOUNTY_ROOM.idFromName('lobsterpot');
	const result = await env.BOUNTY_ROOM.get(id).reset(memberKey(data.rsn), data.bountyId);
	return jsonResponse(200, { cleared: result.cleared });
}

async function clanMemberKeys() {
	const now = Date.now();
	if (memberCache.expiresAt > now) {
		return memberCache.keys;
	}

	const response = await fetch(MEMBER_FEED_URL, {
		headers: { Accept: 'application/json' },
		cf: { cacheTtl: 60 },
	});
	if (!response.ok) {
		throw new Error(`Member feed failed: ${response.status}`);
	}

	const feed = await response.json();
	const keys = new Set();
	for (const member of feed.members || []) {
		if (member.rsn) {
			keys.add(memberKey(member.rsn));
		}
		if (member.rsn_key) {
			keys.add(memberKey(member.rsn_key));
		}
	}

	memberCache = {
		expiresAt: now + MEMBER_CACHE_MS,
		keys,
	};
	return keys;
}

async function activeBountyIds() {
	const now = Date.now();
	if (bountyCache.expiresAt > now) {
		return bountyCache.ids;
	}

	const response = await fetch(MEMBER_FEED_URL, {
		headers: { Accept: 'application/json' },
		cf: { cacheTtl: 60 },
	});
	if (!response.ok) {
		throw new Error(`Member feed failed: ${response.status}`);
	}

	const feed = await response.json();
	const ids = new Set();
	for (const bounty of feed.bounties || []) {
		if (bounty && bounty.active && typeof bounty.id === 'string' && bounty.id) {
			ids.add(bounty.id);
		}
	}

	bountyCache = { expiresAt: now + MEMBER_CACHE_MS, ids };
	return ids;
}

// The set of bounty IDs whose latest claim for this member is currently rejected in the feed. Used
// to re-enable a retry: submit() clears the stale consumed row for a rejected bounty. Reads the feed
// fresh (edge-cached ~60s) rather than the membership cache so a just-rejected claim is seen quickly.
async function rejectedBountyIdsFor(rsnKey) {
	const response = await fetch(MEMBER_FEED_URL, {
		headers: { Accept: 'application/json' },
		cf: { cacheTtl: 60 },
	});
	if (!response.ok) {
		throw new Error(`Member feed failed: ${response.status}`);
	}

	const feed = await response.json();
	const ids = new Set();
	for (const member of feed.members || []) {
		const keys = [];
		if (member.rsn) {
			keys.push(memberKey(member.rsn));
		}
		if (member.rsn_key) {
			keys.push(memberKey(member.rsn_key));
		}
		if (!keys.includes(rsnKey)) {
			continue;
		}
		for (const pending of member.pending_bounties || []) {
			if (pending && typeof pending.bounty_id === 'string' && String(pending.status || '').toLowerCase() === 'rejected') {
				ids.add(pending.bounty_id);
			}
		}
	}
	return ids;
}

function isValidBountySubmission(body) {
	return body
		&& typeof body.rsn === 'string'
		&& body.rsn.trim().length > 0
		&& typeof body.bountyId === 'string'
		&& body.bountyId.length > 0
		&& body.bountyId.length <= MAX_BOUNTY_ID_LENGTH
		&& (body.note === undefined || body.note === null || typeof body.note === 'string');
}

function cleanNote(note) {
	if (typeof note !== 'string') {
		return '';
	}
	return note.replace(/\s+/g, ' ').trim().slice(0, MAX_NOTE_LENGTH);
}

function requireBotAuth(request, env) {
	const token = env.BOT_TOKEN;
	if (!token) {
		return false;
	}
	const header = request.headers.get('Authorization') || '';
	const prefix = 'Bearer ';
	if (!header.startsWith(prefix)) {
		return false;
	}
	return timingSafeEqual(header.slice(prefix.length), token);
}

function timingSafeEqual(a, b) {
	const encoder = new TextEncoder();
	const aBytes = encoder.encode(a);
	const bBytes = encoder.encode(b);
	if (aBytes.length !== bBytes.length) {
		return false;
	}
	let diff = 0;
	for (let i = 0; i < aBytes.length; i++) {
		diff |= aBytes[i] ^ bBytes[i];
	}
	return diff === 0;
}

function jsonResponse(status, body) {
	return corsResponse(
		new Response(JSON.stringify(body), {
			status,
			headers: { 'Content-Type': 'application/json' },
		})
	);
}

function isValidPosition(body) {
	return typeof body.rsn === 'string'
		&& body.rsn.trim().length > 0
		&& typeof body.x === 'number'
		&& Number.isFinite(body.x)
		&& body.x >= 0
		&& body.x <= MAX_COORDINATE
		&& typeof body.y === 'number'
		&& Number.isFinite(body.y)
		&& body.y >= 0
		&& body.y <= MAX_COORDINATE
		&& typeof body.plane === 'number'
		&& Number.isFinite(body.plane)
		&& body.plane >= 0
		&& body.plane <= 3
		&& typeof body.world === 'number'
		&& Number.isFinite(body.world)
		&& body.world > 0;
}

function publicPosition(position) {
	return {
		rsn: position.rsn,
		x: position.x,
		y: position.y,
		plane: position.plane,
		world: position.world,
		activity: position.activity,
	};
}

function isFreshPosition(position, now) {
	return position && typeof position.updatedAt === 'number' && now - position.updatedAt <= POSITION_TTL_MS;
}

function cleanActivity(activity) {
	if (typeof activity !== 'string') {
		return '';
	}
	return activity.replace(/\s+/g, ' ').trim().slice(0, MAX_ACTIVITY_LENGTH);
}

function memberKey(rsn) {
	// Strip whitespace, underscores, hyphens and non-breaking spaces, then lowercase. The hyphen is
	// escaped: unescaped, `_-\u00a0` is a range (0x5F-0xA0) that also strips a-z, collapsing most
	// names and breaking membership matching for anyone whose feed name isn't already uppercase.
	return String(rsn || '').replace(/[\s_\-\u00a0]+/g, '').toLowerCase();
}

function corsResponse(response) {
	const headers = new Headers(response.headers);
	headers.set('Access-Control-Allow-Origin', '*');
	headers.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
	headers.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
	return new Response(response.body, { status: response.status, headers });
}
