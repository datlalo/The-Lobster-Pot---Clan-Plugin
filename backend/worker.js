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
const POSITION_TTL_MS = 60000;
const MIN_POSITION_UPDATE_MS = 4000;
const BROADCAST_INTERVAL_MS = 5000;

let memberCache = {
	expiresAt: 0,
	keys: new Set(),
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

		if (url.pathname === '/health') {
			return corsResponse(new Response('OK', { status: 200 }));
		}

		return corsResponse(new Response('Not Found', { status: 404 }));
	},
};

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
	return String(rsn || '').replace(/[\s_-\u00a0]+/g, '').toLowerCase();
}

function corsResponse(response) {
	const headers = new Headers(response.headers);
	headers.set('Access-Control-Allow-Origin', '*');
	headers.set('Access-Control-Allow-Methods', 'GET, OPTIONS');
	headers.set('Access-Control-Allow-Headers', 'Content-Type');
	return new Response(response.body, { status: response.status, headers });
}
