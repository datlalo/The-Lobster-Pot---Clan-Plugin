// Cloudflare Worker — lobsterpot-positions
//
// KV namespace binding: POSITIONS
//
// POST /position  { rsn, x, y, plane, world }  → upserts with 120s TTL
// GET  /positions?viewer=RSN                    → returns live member entries as JSON array

const TTL_SECONDS = 120;
const MAX_COORDINATE = 64000;
const MAX_ACTIVITY_LENGTH = 80;
const MEMBER_FEED_URL = 'https://raw.githubusercontent.com/datlalo/lobsterpot-plugin-feed/refs/heads/main/plugin-feed.json';
const MEMBER_CACHE_MS = 60000;

let memberCache = {
	expiresAt: 0,
	keys: new Set(),
};

export default {
	async fetch(request, env) {
		const url = new URL(request.url);

		if (request.method === 'OPTIONS') {
			return corsResponse(new Response(null, { status: 204 }));
		}

		if (request.method === 'POST' && url.pathname === '/position') {
			let body;
			try {
				body = await request.json();
			} catch {
				return corsResponse(new Response('Bad JSON', { status: 400 }));
			}

			if (!isValidPosition(body)) {
				return corsResponse(new Response('Invalid fields', { status: 400 }));
			}

			const position = {
				rsn: String(body.rsn).trim(),
				x: Math.trunc(body.x),
				y: Math.trunc(body.y),
				plane: Math.trunc(body.plane),
				world: Math.trunc(body.world),
				activity: cleanActivity(body.activity),
			};
			const key = memberKey(position.rsn);
			let memberKeys;
			try {
				memberKeys = await clanMemberKeys();
			} catch {
				return corsResponse(new Response('Member feed unavailable', { status: 503 }));
			}
			if (!memberKeys.has(key)) {
				return corsResponse(new Response('Forbidden', { status: 403 }));
			}

			await env.POSITIONS.put(key, JSON.stringify(position), { expirationTtl: TTL_SECONDS });
			return corsResponse(new Response('OK', { status: 200 }));
		}

		if (request.method === 'GET' && url.pathname === '/positions') {
			let memberKeys;
			try {
				memberKeys = await clanMemberKeys();
			} catch {
				return corsResponse(new Response('Member feed unavailable', { status: 503 }));
			}
			const viewerKey = memberKey(url.searchParams.get('viewer') || '');
			if (!memberKeys.has(viewerKey)) {
				return corsResponse(new Response('Forbidden', { status: 403 }));
			}

			const list = await env.POSITIONS.list();
			const entries = await Promise.all(
				list.keys.map(async ({ name }) => {
					const value = await env.POSITIONS.get(name);
					return value ? JSON.parse(value) : null;
				})
			);
			const positions = entries.filter((position) => {
				const positionKey = position ? memberKey(position.rsn) : '';
				return position && isValidPosition(position) && positionKey !== viewerKey && memberKeys.has(positionKey);
			});
			return corsResponse(new Response(JSON.stringify(positions), {
				status: 200,
				headers: { 'Content-Type': 'application/json' },
			}));
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

function cleanActivity(activity) {
	if (typeof activity !== 'string') {
		return '';
	}
	return activity.replace(/\s+/g, ' ').trim().slice(0, MAX_ACTIVITY_LENGTH);
}

function memberKey(rsn) {
	return rsn.replace(/[\s_-]+/g, '').toLowerCase();
}

function corsResponse(response) {
	const headers = new Headers(response.headers);
	headers.set('Access-Control-Allow-Origin', '*');
	headers.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
	headers.set('Access-Control-Allow-Headers', 'Content-Type');
	return new Response(response.body, { status: response.status, headers });
}
