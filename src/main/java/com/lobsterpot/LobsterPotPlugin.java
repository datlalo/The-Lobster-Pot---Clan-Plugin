package com.lobsterpot;

import com.google.inject.Provides;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import com.lobsterpot.feed.FeedBroadcast;
import com.lobsterpot.feed.FeedMember;
import com.lobsterpot.feed.FeedNextRank;
import com.lobsterpot.feed.PluginFeed;
import com.lobsterpot.feed.PluginFeedClient;
import com.lobsterpot.requirements.RankRequirementEvaluation;
import com.lobsterpot.requirements.RankRequirementEvaluator;
import com.lobsterpot.ui.LobsterPotPanel;
import com.lobsterpot.worldmap.ClanPositionService;
import com.lobsterpot.worldmap.ClanPositionWorldMapOverlay;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "The Lobster Pot",
	description = "Clan companion for LobsterPot members with broadcasts, events, rank progress, and clan world map locations.",
	tags = {"clan", "lobster", "pot", "broadcast", "events", "map", "world"}
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
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClanMembershipService clanMembershipService;

	@Inject
	private PluginFeedClient pluginFeedClient;

	@Inject
	private RankRequirementEvaluator rankRequirementEvaluator;

	@Inject
	private LobsterPotPanel panel;

	@Inject
	private ClanPositionService clanPositionService;

	@Inject
	private ClanPositionWorldMapOverlay clanPositionWorldMapOverlay;

	@Inject
	private LobsterPotConfig config;

	private NavigationButton navButton;
	private volatile ClanAccess currentAccess;
	private volatile PluginFeed currentFeed;
	private final AtomicInteger requirementRequestId = new AtomicInteger();
	private final Object broadcastAnnouncementLock = new Object();
	private boolean loggedIn;
	private boolean announceBroadcastAfterFeedLoad;
	private String announcedLoginBroadcastKey;

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

		clanPositionService.start();
		overlayManager.add(clanPositionWorldMapOverlay);

		loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (loggedIn)
		{
			queueLoginBroadcastAnnouncement();
		}
		refreshAll();
	}

	@Override
	protected void shutDown()
	{
		clanPositionService.stop();
		overlayManager.remove(clanPositionWorldMapOverlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		clanPositionService.onTick(currentAccess, config.shareWorldMapLocation());
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		clanPositionService.addHopMenuEntry(event);
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		clanPositionService.addHoveredMapMenuEntry();
		clanPositionService.rewriteHopMenuEntries();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			final boolean justLoggedIn = !loggedIn;
			loggedIn = true;
			if (justLoggedIn)
			{
				queueLoginBroadcastAnnouncement();
				refreshFeed();
			}
			refreshAccess();
			return;
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			loggedIn = false;
			clearLoginBroadcastAnnouncement();
			clanPositionService.clearPoints();
			refreshAccess();
			return;
		}

		if (event.getGameState() == GameState.HOPPING)
		{
			clanPositionService.clearPoints();
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
			currentAccess = access;
			if (!access.isAllowed())
			{
				clanPositionService.clearPoints();
			}
			SwingUtilities.invokeLater(() -> panel.render(access));
			tryAnnounceLoginBroadcast();
			evaluateRankRequirement();
		});
	}

	private void refreshAll()
	{
		refreshAccess();
		refreshFeed();
	}

	private void refreshFeed()
	{
		currentFeed = null;
		requirementRequestId.incrementAndGet();
		panel.setFeedLoading();
		pluginFeedClient.fetch(new PluginFeedClient.FeedCallback()
		{
			@Override
			public void onSuccess(PluginFeed feed)
			{
				currentFeed = feed;
				SwingUtilities.invokeLater(() -> panel.renderFeed(feed, null));
				tryAnnounceLoginBroadcast();
				evaluateRankRequirement();
			}

			@Override
			public void onFailure(String error)
			{
				currentFeed = null;
				requirementRequestId.incrementAndGet();
				SwingUtilities.invokeLater(() -> panel.renderFeed(null, error));
			}
		});
	}

	private void queueLoginBroadcastAnnouncement()
	{
		synchronized (broadcastAnnouncementLock)
		{
			announceBroadcastAfterFeedLoad = true;
			announcedLoginBroadcastKey = null;
		}
		currentAccess = null;
		currentFeed = null;
	}

	private void clearLoginBroadcastAnnouncement()
	{
		synchronized (broadcastAnnouncementLock)
		{
			announceBroadcastAfterFeedLoad = false;
			announcedLoginBroadcastKey = null;
		}
	}

	private void tryAnnounceLoginBroadcast()
	{
		final FeedBroadcast broadcast;
		final String broadcastKey;
		synchronized (broadcastAnnouncementLock)
		{
			if (!announceBroadcastAfterFeedLoad)
			{
				return;
			}

			final ClanAccess access = currentAccess;
			final PluginFeed feed = currentFeed;
			if (access == null || feed == null)
			{
				return;
			}
			if (!access.isAllowed())
			{
				announceBroadcastAfterFeedLoad = false;
				return;
			}

			broadcast = currentActiveBroadcast(feed);
			announceBroadcastAfterFeedLoad = false;
			if (broadcast == null)
			{
				return;
			}

			broadcastKey = broadcastKey(broadcast);
			if (broadcastKey.equals(announcedLoginBroadcastKey))
			{
				return;
			}
			announcedLoginBroadcastKey = broadcastKey;
		}

		final String message = broadcastChatMessage(broadcast);
		if (!hasText(message))
		{
			return;
		}

		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
			}
		});
	}

	private static FeedBroadcast currentActiveBroadcast(PluginFeed feed)
	{
		if (feed == null)
		{
			return null;
		}

		final Instant now = Instant.now();
		for (FeedBroadcast broadcast : feed.getBroadcasts())
		{
			if (isActiveBroadcast(broadcast, now))
			{
				return broadcast;
			}
		}
		return null;
	}

	private static boolean isActiveBroadcast(FeedBroadcast broadcast, Instant now)
	{
		if (broadcast == null || (!hasText(broadcast.getTitle()) && !hasText(broadcast.getMessage())))
		{
			return false;
		}

		final Instant start = parseInstant(broadcast.getStartsAt());
		if (start != null && now.isBefore(start))
		{
			return false;
		}

		final Instant expires = parseInstant(broadcast.getExpiresAt());
		return expires == null || now.isBefore(expires);
	}

	private static String broadcastChatMessage(FeedBroadcast broadcast)
	{
		final String title = cleanChatPart(broadcast.getTitle());
		final String message = cleanChatPart(broadcast.getMessage());
		if (hasText(title) && hasText(message))
		{
			return "The Lobster Pot broadcast: " + title + " - " + message;
		}
		if (hasText(title))
		{
			return "The Lobster Pot broadcast: " + title;
		}
		if (hasText(message))
		{
			return "The Lobster Pot broadcast: " + message;
		}
		return null;
	}

	private static String cleanChatPart(String value)
	{
		if (!hasText(value))
		{
			return null;
		}
		return Text.escapeJagex(Text.sanitizeMultilineText(value).replaceAll("\\s+", " ").trim());
	}

	private static String broadcastKey(FeedBroadcast broadcast)
	{
		if (hasText(broadcast.getId()))
		{
			return broadcast.getId().trim();
		}
		return firstNonBlank(broadcast.getTitle(), "") + "|" + firstNonBlank(broadcast.getMessage(), "");
	}

	private static String firstNonBlank(String... values)
	{
		for (String value : values)
		{
			if (hasText(value))
			{
				return value.trim();
			}
		}
		return "";
	}

	private static Instant parseInstant(String value)
	{
		if (!hasText(value))
		{
			return null;
		}
		try
		{
			return OffsetDateTime.parse(value.trim()).toInstant();
		}
		catch (Exception ignored)
		{
			try
			{
				return Instant.parse(value.trim());
			}
			catch (Exception alsoIgnored)
			{
				return null;
			}
		}
	}

	private void evaluateRankRequirement()
	{
		final int requestId = requirementRequestId.incrementAndGet();
		final ClanAccess access = currentAccess;
		final PluginFeed feed = currentFeed;
		if (access == null || !access.isAllowed() || !hasText(access.getPlayerName()) || feed == null)
		{
			publishRequirementEvaluation(requestId, null);
			return;
		}

		clientThread.invoke(() ->
		{
			if (requestId != requirementRequestId.get())
			{
				return;
			}

			final FeedNextRank nextRank = findNextRank(feed, access.getPlayerName());
			final RankRequirementEvaluation evaluation = rankRequirementEvaluator.evaluateLocal(nextRank);
			publishRequirementEvaluation(requestId, evaluation);

			if (!rankRequirementEvaluator.shouldCheckJadKillCount(nextRank, evaluation))
			{
				return;
			}

			rankRequirementEvaluator.evaluateJadKillCount(access.getPlayerName(), nextRank)
				.whenComplete((hiscoreEvaluation, throwable) ->
				{
					final RankRequirementEvaluation result = throwable == null && hiscoreEvaluation != null
						? hiscoreEvaluation
						: RankRequirementEvaluation.unknown(evaluation.getRequirementText(), "Fire Cape not verified");
					publishRequirementEvaluation(requestId, result);
				});
		});
	}

	private void publishRequirementEvaluation(int requestId, RankRequirementEvaluation evaluation)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (requestId == requirementRequestId.get())
			{
				panel.renderRequirementEvaluation(evaluation);
			}
		});
	}

	private static FeedNextRank findNextRank(PluginFeed feed, String playerName)
	{
		if (feed == null || !hasText(playerName))
		{
			return null;
		}

		final String playerKey = rsnKey(playerName);
		for (FeedMember member : feed.getMembers())
		{
			if (playerKey.equals(member.getRsnKey()) || playerKey.equals(rsnKey(member.getRsn())))
			{
				return member.getNextRank();
			}
		}
		return null;
	}

	private static String rsnKey(String rsn)
	{
		if (rsn == null)
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder(rsn.length());
		for (int i = 0; i < rsn.length(); i++)
		{
			final char c = Character.toLowerCase(rsn.charAt(i));
			if (c != ' ' && c != '_' && c != '-' && c != '\u00A0')
			{
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static boolean hasText(String value)
	{
		return value != null && !value.trim().isEmpty();
	}

	@Provides
	LobsterPotConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LobsterPotConfig.class);
	}

	private void clearLegacyConfig()
	{
		for (String key : LEGACY_CONFIG_KEYS)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
	}
}
