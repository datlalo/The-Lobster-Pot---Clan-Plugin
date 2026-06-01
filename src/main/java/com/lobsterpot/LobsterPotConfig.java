package com.lobsterpot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(LobsterPotConfig.GROUP)
public interface LobsterPotConfig extends Config
{
	String GROUP = "lobsterpot";

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Base URL of the clan's public member API (no trailing slash).",
		position = 0
	)
	default String apiBaseUrl()
	{
		return "https://rtkiqzyksmvgzydpqbwj.supabase.co/functions/v1/member-api";
	}

	@ConfigItem(
		keyName = "enableRankRequests",
		name = "Enable rank-up requests",
		description = "Show the button to submit a rank-up request from the side panel.",
		position = 1
	)
	default boolean enableRankRequests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMotdOnLogin",
		name = "Show MOTD on login",
		description = "Print the active clan Message of the Day to your chat box once when you log in (requires being logged in to the clan API).",
		position = 2
	)
	default boolean showMotdOnLogin()
	{
		return true;
	}
}
