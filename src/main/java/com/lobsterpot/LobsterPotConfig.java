package com.lobsterpot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lobsterpot")
public interface LobsterPotConfig extends Config
{
	@ConfigItem(
		keyName = "shareWorldMapLocation",
		name = "Share world map location",
		description = "Share your character name, world, map position, and detected activity with the LobsterPot clan map.",
		position = 0
	)
	default boolean shareWorldMapLocation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableBountySubmission",
		name = "Show bounty submissions",
		description = "Show the Bounties section in the panel so you can submit completed clan bounties for points.",
		position = 1
	)
	default boolean enableBountySubmission()
	{
		return true;
	}
}
