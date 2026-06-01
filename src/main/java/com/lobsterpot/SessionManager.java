package com.lobsterpot;

import com.lobsterpot.api.model.LoginResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Holds the member-API auth session. Persists ONLY tokens (never the password) under non-UI config
 * keys so the login survives client restarts. Note: RuneLite syncs config to its servers for
 * logged-in accounts, so tokens (revocable, short-lived) may be uploaded — passwords never are.
 *
 * <p>Tokens are read/written off any thread; UI listeners are notified on the calling thread and
 * should marshal to the EDT themselves.</p>
 */
@Singleton
public class SessionManager
{
	private static final String KEY_ACCESS_TOKEN = "session.accessToken";
	private static final String KEY_REFRESH_TOKEN = "session.refreshToken";
	private static final String KEY_EXPIRES_AT = "session.expiresAt";
	private static final String KEY_USERNAME = "session.username";

	/** Treat the token as expired this many seconds early, to avoid racing the boundary. */
	private static final long EXPIRY_SKEW_SECONDS = 30;

	private final ConfigManager configManager;
	private final List<Runnable> listeners = new ArrayList<>();

	private volatile String accessToken;
	private volatile String refreshToken;
	private volatile long expiresAtEpochSec;
	private volatile String username;

	@Inject
	public SessionManager(ConfigManager configManager)
	{
		this.configManager = configManager;
		load();
	}

	private void load()
	{
		accessToken = configManager.getConfiguration(LobsterPotConfig.GROUP, KEY_ACCESS_TOKEN);
		refreshToken = configManager.getConfiguration(LobsterPotConfig.GROUP, KEY_REFRESH_TOKEN);
		username = configManager.getConfiguration(LobsterPotConfig.GROUP, KEY_USERNAME);
		final String expiresAt = configManager.getConfiguration(LobsterPotConfig.GROUP, KEY_EXPIRES_AT);
		expiresAtEpochSec = parseLong(expiresAt);
	}

	public synchronized void applyLogin(LoginResponse login)
	{
		accessToken = login.getAccessToken();
		refreshToken = login.getRefreshToken();
		username = login.getUser() != null ? login.getUser().getUsername() : null;
		expiresAtEpochSec = login.getExpiresAt() > 0
			? login.getExpiresAt()
			: Instant.now().getEpochSecond() + Math.max(0, login.getExpiresIn());

		configManager.setConfiguration(LobsterPotConfig.GROUP, KEY_ACCESS_TOKEN, accessToken);
		configManager.setConfiguration(LobsterPotConfig.GROUP, KEY_REFRESH_TOKEN, nullToEmpty(refreshToken));
		configManager.setConfiguration(LobsterPotConfig.GROUP, KEY_EXPIRES_AT, Long.toString(expiresAtEpochSec));
		configManager.setConfiguration(LobsterPotConfig.GROUP, KEY_USERNAME, nullToEmpty(username));
		fireChanged();
	}

	public synchronized void clear()
	{
		accessToken = null;
		refreshToken = null;
		username = null;
		expiresAtEpochSec = 0;

		configManager.unsetConfiguration(LobsterPotConfig.GROUP, KEY_ACCESS_TOKEN);
		configManager.unsetConfiguration(LobsterPotConfig.GROUP, KEY_REFRESH_TOKEN);
		configManager.unsetConfiguration(LobsterPotConfig.GROUP, KEY_EXPIRES_AT);
		configManager.unsetConfiguration(LobsterPotConfig.GROUP, KEY_USERNAME);
		fireChanged();
	}

	@Nullable
	public String getAccessToken()
	{
		return accessToken;
	}

	@Nullable
	public String getUsername()
	{
		return username;
	}

	public boolean isLoggedIn()
	{
		return accessToken != null && !accessToken.isEmpty() && !isExpired();
	}

	public boolean isExpired()
	{
		if (expiresAtEpochSec <= 0)
		{
			return false;
		}
		return Instant.now().getEpochSecond() >= (expiresAtEpochSec - EXPIRY_SKEW_SECONDS);
	}

	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	private void fireChanged()
	{
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}

	private static long parseLong(@Nullable String value)
	{
		if (value == null || value.isEmpty())
		{
			return 0;
		}
		try
		{
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static String nullToEmpty(@Nullable String s)
	{
		return s == null ? "" : s;
	}
}
