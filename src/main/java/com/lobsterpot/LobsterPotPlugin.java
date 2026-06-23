package com.lobsterpot;

import com.lobsterpot.ClanMembershipService.ClanAccess;
import com.lobsterpot.feed.PluginFeed;
import com.lobsterpot.feed.PluginFeedClient;
import com.lobsterpot.ui.LobsterPotPanel;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.GameState;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "The Lobster Pot",
	description = "Clan companion for LobsterPot members.",
	tags = {"clan", "lobster", "pot"}
)
public class LobsterPotPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "lobsterpot";
	private static final String[] LEGACY_CONFIG_KEYS = {
		"apiBaseUrl",
		"enableRankRequests",
		"showMotdOnLogin",
		"session.accessToken",
		"session.refreshToken",
		"session.expiresAt",
		"session.username"
	};

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClanMembershipService clanMembershipService;

	@Inject
	private PluginFeedClient pluginFeedClient;

	@Inject
	private LobsterPotPanel panel;

	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		clearLegacyConfig();
		panel.init(this::refreshAll);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/lobsterpot/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("The Lobster Pot")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshAll();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN
			|| event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			refreshAccess();
		}
	}

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		if (!event.isGuest())
		{
			refreshAccess();
		}
	}

	private void refreshAccess()
	{
		clientThread.invoke(() ->
		{
			final ClanAccess access = clanMembershipService.checkAccess();
			SwingUtilities.invokeLater(() -> panel.render(access));
		});
	}

	private void refreshAll()
	{
		refreshAccess();
		refreshFeed();
	}

	private void refreshFeed()
	{
		panel.setFeedLoading();
		pluginFeedClient.fetch(new PluginFeedClient.FeedCallback()
		{
			@Override
			public void onSuccess(PluginFeed feed)
			{
				SwingUtilities.invokeLater(() -> panel.renderFeed(feed, null));
			}

			@Override
			public void onFailure(String error)
			{
				SwingUtilities.invokeLater(() -> panel.renderFeed(null, error));
			}
		});
	}

	private void clearLegacyConfig()
	{
		for (String key : LEGACY_CONFIG_KEYS)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
	}
}
