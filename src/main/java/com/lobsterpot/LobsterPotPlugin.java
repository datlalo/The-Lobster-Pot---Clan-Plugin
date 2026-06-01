package com.lobsterpot;

import com.google.inject.Provides;
import com.lobsterpot.api.ApiCallback;
import com.lobsterpot.api.LobsterPotApiClient;
import com.lobsterpot.api.model.Broadcast;
import com.lobsterpot.ui.LobsterPotPanel;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "The Lobster Pot",
	description = "Clan companion: view rank progress, request a rank-up, see upcoming events and news, and read the clan MOTD on login.",
	tags = {"clan", "lobster", "pot", "rank", "events", "news", "motd", "broadcast"}
)
public class LobsterPotPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private LobsterPotConfig config;

	@Inject
	private LobsterPotApiClient apiClient;

	@Inject
	private SessionManager session;

	@Inject
	private LobsterPotPanel panel;

	private NavigationButton navButton;

	/** Whether the MOTD has already been shown during the current game-login session. */
	private boolean motdShown;

	@Override
	protected void startUp()
	{
		panel.init();

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/lobsterpot/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("The Lobster Pot")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Show the MOTD if the member logs in (to the API) while already in-game this session.
		session.addListener(this::maybeShowMotd);

		maybeShowMotd();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
	}

	@Provides
	LobsterPotConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LobsterPotConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				maybeShowMotd();
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				// New game-login session: allow the MOTD to show again.
				motdShown = false;
				break;
			default:
				break;
		}
	}

	private void maybeShowMotd()
	{
		if (motdShown
			|| !config.showMotdOnLogin()
			|| !session.isLoggedIn()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		motdShown = true; // guard against duplicate fetches while the request is in flight
		apiClient.getActiveBroadcasts(new ApiCallback<List<Broadcast>>()
		{
			@Override
			public void onSuccess(List<Broadcast> broadcasts)
			{
				if (broadcasts == null || broadcasts.isEmpty())
				{
					return;
				}
				clientThread.invoke(() ->
				{
					for (Broadcast broadcast : broadcasts)
					{
						if (broadcast != null && broadcast.isActive()
							&& broadcast.getMessage() != null && !broadcast.getMessage().trim().isEmpty())
						{
							postMotd(broadcast.getMessage().trim());
						}
					}
				});
			}

			@Override
			public void onFailure(String error, int httpCode)
			{
				log.debug("Could not fetch MOTD: {} (HTTP {})", error, httpCode);
				// Allow a retry on the next login/session change.
				motdShown = false;
			}
		});
	}

	private void postMotd(String message)
	{
		// Broadcasts may contain multiple lines; the chat box is single-line, so queue one per line.
		for (String line : message.split("\\r?\\n"))
		{
			if (line.trim().isEmpty())
			{
				continue;
			}
			final String formatted = new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("[The Lobster Pot] ")
				.append(ChatColorType.NORMAL)
				.append(line.trim())
				.build();

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(formatted)
				.build());
		}
	}
}
