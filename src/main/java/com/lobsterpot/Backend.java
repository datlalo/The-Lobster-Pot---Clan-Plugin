package com.lobsterpot;

/**
 * Shared location of the LobsterPot Cloudflare Worker backend. Both the world-map position service
 * and the bounty submission client target this host, so it lives in one place to avoid drift.
 */
public final class Backend
{
	public static final String URL = "https://lobsterpot-positions.lobsterpot.workers.dev";

	private Backend()
	{
	}
}
