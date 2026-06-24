package com.lobsterpot;

import com.google.gson.Gson;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import com.lobsterpot.feed.FeedMember;
import com.lobsterpot.feed.PluginFeed;
import com.lobsterpot.ui.LobsterPotPanel;
import java.awt.BorderLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LobsterPotPanelPreview
{
	private static final String PLUGIN_FEED_URL =
		"https://raw.githubusercontent.com/datlalo/lobsterpot-plugin-feed/refs/heads/main/plugin-feed.json";
	private static final Gson GSON = new Gson();

	public static void main(String[] args)
	{
		final String rsn = args.length > 0 && !args[0].trim().isEmpty() ? args[0].trim() : "Fikos";
		final String rank = args.length > 1 && !args[1].trim().isEmpty() ? args[1].trim() : "Ruby";

		SwingUtilities.invokeLater(() -> showPreview(rsn, rank));
	}

	private static void showPreview(String rsn, String rank)
	{
		final LobsterPotPanel panel = new LobsterPotPanel();
		panel.init(() -> refresh(panel, rsn, rank));

		final JFrame frame = new JFrame("The Lobster Pot panel preview");
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.setLayout(new BorderLayout());
		frame.add(panel, BorderLayout.CENTER);
		frame.setSize(260, 700);
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);

		refresh(panel, rsn, rank);
	}

	private static void refresh(LobsterPotPanel panel, String rsn, String rank)
	{
		panel.render(previewAccess(rsn, rank));
		panel.setFeedLoading();

		final Thread loader = new Thread(() ->
		{
			final PluginFeed feed = fetchFeed();
			if (feed == null)
			{
				SwingUtilities.invokeLater(() -> panel.renderFeed(null, "Could not load online plugin feed."));
				return;
			}
			if (!containsMember(feed, rsn))
			{
				addPreviewMember(feed, rsn, rank);
			}

			final PluginFeed loadedFeed = feed;
			SwingUtilities.invokeLater(() -> panel.renderFeed(loadedFeed, null));
		}, "lobsterpot-panel-preview-feed");
		loader.setDaemon(true);
		loader.start();
	}

	private static PluginFeed fetchFeed()
	{
		final OkHttpClient httpClient = new OkHttpClient.Builder()
			.connectTimeout(5, TimeUnit.SECONDS)
			.readTimeout(5, TimeUnit.SECONDS)
			.build();
		final Request request = new Request.Builder()
			.url(PLUGIN_FEED_URL)
			.header("Accept", "application/json")
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute();
			ResponseBody body = response.body())
		{
			if (!response.isSuccessful() || body == null)
			{
				return null;
			}
			return GSON.fromJson(body.charStream(), PluginFeed.class);
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	private static void addPreviewMember(PluginFeed feed, String rsn, String rank)
	{
		final List<FeedMember> members = new ArrayList<>(feed.getMembers());
		members.add(sampleMember(rsn, rank));
		try
		{
			final Field membersField = PluginFeed.class.getDeclaredField("members");
			membersField.setAccessible(true);
			membersField.set(feed, members);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Could not add preview member to feed.", e);
		}
	}

	private static FeedMember sampleMember(String rsn, String rank)
	{
		final String json = "{"
			+ "\"rsn\":\"" + jsonEscape(rsn) + "\","
			+ "\"rsn_key\":\"" + rsnKey(rsn) + "\","
			+ "\"bot_rank\":\"" + jsonEscape(rank) + "\","
			+ "\"progression_rank\":\"" + jsonEscape(rank) + "\","
			+ "\"join_date\":\"" + LocalDate.now().minusMonths(9) + "\","
			+ "\"points_total\":535,"
			+ "\"points_spent\":0,"
			+ "\"points_available\":535,"
			+ "\"months_in_clan\":9,"
			+ "\"next_rank\":{"
			+ "\"name\":\"Diamond\","
			+ "\"point_cost\":700,"
			+ "\"min_months\":5,"
			+ "\"requirements\":\"Fire Cape\","
			+ "\"missing_points\":165,"
			+ "\"missing_months\":0,"
			+ "\"can_claim\":false"
			+ "}"
			+ "}";
		return GSON.fromJson(json, FeedMember.class);
	}

	private static ClanAccess previewAccess(String rsn, String rank)
	{
		try
		{
			final Constructor<ClanAccess> constructor = ClanAccess.class.getDeclaredConstructor(boolean.class,
				String.class, String.class, String.class, String.class, LocalDate.class);
			constructor.setAccessible(true);
			return constructor.newInstance(true, "Allowed: preview LobsterPot clan member.", rsn,
				ClanMembershipService.REQUIRED_CLAN_NAME, rank, LocalDate.now().minusMonths(9));
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Could not create preview clan access.", e);
		}
	}

	private static boolean containsMember(PluginFeed feed, String rsn)
	{
		final String key = rsnKey(rsn);
		for (FeedMember member : feed.getMembers())
		{
			if (key.equals(member.getRsnKey()) || key.equals(rsnKey(member.getRsn())))
			{
				return true;
			}
		}
		return false;
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

	private static String jsonEscape(String value)
	{
		return value == null ? "" : value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"");
	}
}
