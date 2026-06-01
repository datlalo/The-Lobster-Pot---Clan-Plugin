package com.lobsterpot;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LobsterPotPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LobsterPotPlugin.class);
		RuneLite.main(args);
	}
}
