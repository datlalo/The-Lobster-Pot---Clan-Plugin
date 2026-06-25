package com.lobsterpot;

import com.lobsterpot.ClanMembershipService.ClanAccess;
import com.lobsterpot.feed.FeedMember;
import com.lobsterpot.feed.FeedNextRank;
import com.lobsterpot.feed.PluginFeed;
import com.lobsterpot.feed.PluginFeedClient;
import com.lobsterpot.requirements.RankRequirementEvaluation;
import com.lobsterpot.requirements.RankRequirementEvaluator;
import com.lobsterpot.ui.LobsterPotPanel;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
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
	private RankRequirementEvaluator rankRequirementEvaluator;

	@Inject
	private LobsterPotPanel panel;

	private NavigationButton navButton;
	private volatile ClanAccess currentAccess;
	private volatile PluginFeed currentFeed;
	private final AtomicInteger requirementRequestId = new AtomicInteger();

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
			currentAccess = access;
			SwingUtilities.invokeLater(() -> panel.render(access));
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

	private void clearLegacyConfig()
	{
		for (String key : LEGACY_CONFIG_KEYS)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
	}
}
