package com.lobsterpot.ui;

import com.lobsterpot.ClanMembershipService;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import com.lobsterpot.feed.FeedBroadcast;
import com.lobsterpot.feed.FeedEvent;
import com.lobsterpot.feed.FeedMember;
import com.lobsterpot.feed.FeedNextRank;
import com.lobsterpot.feed.FeedPendingClaim;
import com.lobsterpot.feed.PluginFeed;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.laf.RuneLiteScrollBarUI;

@Singleton
public class LobsterPotPanel extends PluginPanel
{
	private static final Color ALLOWED_COLOR = new Color(0x4CAF50);
	private static final Color DENIED_COLOR = new Color(0xE53935);
	private static final Color HEADING_COLOR = Color.WHITE;
	private static final Color VALUE_COLOR = Color.WHITE;
	private static final Color KEY_COLOR = new Color(0x9E9E9E);
	private static final int WRAP_WIDTH = 150;
	private static final int CARD_WRAP_WIDTH = 142;
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy");
	private static final DateTimeFormatter DATE_TIME_FORMAT =
		DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault());

	private final JPanel scrollContent = new ScrollableContentPanel();
	private final JLabel status = new JLabel();
	private final JLabel player = field();
	private final JLabel profileStatus = new JLabel();
	private final JPanel profileList = new JPanel();
	private final JLabel broadcastStatus = new JLabel();
	private final JPanel broadcastList = new JPanel();
	private final JLabel eventsStatus = new JLabel();
	private final JPanel eventsList = new JPanel();
	private final JButton refresh = new JButton("Refresh");

	private Runnable refreshAction = () -> {};
	private String currentPlayerName;
	private boolean currentAccessAllowed;
	private PluginFeed currentFeed;
	private String currentFeedError;

	@Inject
	public LobsterPotPanel()
	{
		super(false);
	}

	public void init(Runnable refreshAction)
	{
		this.refreshAction = refreshAction;

		removeAll();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		scrollContent.removeAll();
		scrollContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollContent.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 14));

		final JPanel main = new JPanel();
		main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
		main.setOpaque(false);
		main.setAlignmentX(Component.LEFT_ALIGNMENT);
		scrollContent.add(main, BorderLayout.NORTH);

		final JLabel title = new JLabel("The Lobster Pot");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		main.add(title);
		main.add(verticalGap(8));

		final JPanel accessSection = section("Clan access");
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		accessSection.add(status);
		accessSection.add(verticalGap(8));
		accessSection.add(requiredClanLine());
		accessSection.add(verticalGap(2));
		accessSection.add(player);
		main.add(accessSection);
		main.add(verticalGap(10));

		main.add(buildProfileSection());
		main.add(verticalGap(10));
		main.add(buildBroadcastsSection());
		main.add(verticalGap(10));
		main.add(buildEventsSection());
		main.add(verticalGap(10));

		final JPanel buttons = new JPanel(new GridLayout(1, 1, 0, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		refresh.setHorizontalAlignment(SwingConstants.CENTER);
		for (ActionListener listener : refresh.getActionListeners())
		{
			refresh.removeActionListener(listener);
		}
		refresh.addActionListener(e -> this.refreshAction.run());
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, refresh.getPreferredSize().height));
		buttons.add(refresh);
		main.add(buttons);

		final JScrollPane scrollPane = new JScrollPane(scrollContent,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUI(new RuneLiteScrollBarUI());
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);

		setChecking();
	}

	public void render(ClanAccess access)
	{
		if (access == null)
		{
			setChecking();
			return;
		}

		status.setForeground(access.isAllowed() ? ALLOWED_COLOR : DENIED_COLOR);
		status.setText(wrapped(access.getMessage()));
		currentPlayerName = access.getPlayerName();
		currentAccessAllowed = access.isAllowed();
		setInfo(player, "Character:", valueOrUnknown(access.getPlayerName()));
		showProfile();

		revalidate();
		repaint();
	}

	public void setFeedLoading()
	{
		currentFeed = null;
		currentFeedError = null;
		setProfileMessage("Loading clan profile...");
		setBroadcastsMessage("Loading broadcasts...");
		setEventsMessage("Loading events...");
	}

	public void clearFeed(String message)
	{
		currentFeed = null;
		currentFeedError = message;
		setProfileMessage(message);
		setBroadcastsMessage(message);
		setEventsMessage(message);
	}

	public void renderFeed(PluginFeed feed, String error)
	{
		currentFeed = feed;
		currentFeedError = error;

		if (error != null)
		{
			setProfileMessage(error);
			setBroadcastsMessage(error);
			setEventsMessage(error);
			return;
		}
		if (feed == null)
		{
			setProfileMessage("No feed loaded.");
			setBroadcastsMessage("No feed loaded.");
			setEventsMessage("No feed loaded.");
			return;
		}

		showProfile();
		showBroadcasts(feed.getBroadcasts());
		showEvents(feed.getEvents());
	}

	private void setChecking()
	{
		currentPlayerName = null;
		currentAccessAllowed = false;
		status.setForeground(Color.LIGHT_GRAY);
		status.setText(wrapped("Checking clan membership..."));
		setInfo(player, "Character:", "Unknown");
		setProfileMessage("Log in to view your clan profile.");
	}

	private static JLabel requiredClanLine()
	{
		final JLabel label = field();
		setInfo(label, "Required clan:", ClanMembershipService.REQUIRED_CLAN_NAME);
		return label;
	}

	private JPanel buildProfileSection()
	{
		final JPanel section = section("Clan Profile");
		profileStatus.setForeground(Color.LIGHT_GRAY);
		profileStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(profileStatus);
		profileList.setLayout(new BoxLayout(profileList, BoxLayout.Y_AXIS));
		profileList.setOpaque(false);
		profileList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(profileList);
		return section;
	}

	private JPanel buildBroadcastsSection()
	{
		final JPanel section = section("Broadcasts");
		broadcastStatus.setForeground(Color.LIGHT_GRAY);
		broadcastStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(broadcastStatus);
		broadcastList.setLayout(new BoxLayout(broadcastList, BoxLayout.Y_AXIS));
		broadcastList.setOpaque(false);
		broadcastList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(broadcastList);
		return section;
	}

	private JPanel buildEventsSection()
	{
		final JPanel section = section("Events");
		eventsStatus.setForeground(Color.LIGHT_GRAY);
		eventsStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(eventsStatus);
		eventsList.setLayout(new BoxLayout(eventsList, BoxLayout.Y_AXIS));
		eventsList.setOpaque(false);
		eventsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(eventsList);
		return section;
	}

	private void showProfile()
	{
		profileList.removeAll();
		if (currentFeedError != null)
		{
			setProfileMessage(currentFeedError);
			return;
		}
		if (currentPlayerName == null || currentPlayerName.trim().isEmpty())
		{
			setProfileMessage("Log in to view your clan profile.");
			return;
		}
		if (!currentAccessAllowed)
		{
			setProfileMessage("Available for LobsterPot clan members.");
			return;
		}
		if (currentFeed == null)
		{
			setProfileMessage("Loading clan profile...");
			return;
		}

		final FeedMember member = findMember(currentFeed, currentPlayerName);
		if (member == null)
		{
			setProfileMessage("No clan profile found for " + currentPlayerName + ".");
			return;
		}

		profileStatus.setText(" ");
		profileList.add(profileRow(member));
		revalidate();
		repaint();
	}

	private JPanel profileRow(FeedMember member)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(member.getRsn())));
		row.add(grayLine("Rank: " + valueOrUnknown(firstNonBlank(member.getProgressionRank(), member.getBotRank()))));
		row.add(grayLine("Join date: " + formatDateLike(member.getJoinDate())));
		row.add(grayLine("Points: " + formatInt(member.getPointsAvailable()) + " available"));
		row.add(grayLine(formatInt(member.getPointsTotal()) + " total / "
			+ formatInt(member.getPointsSpent()) + " spent"));
		row.add(grayLine("Time in clan: " + formatMonths(member.getMonthsInClan())));

		addNextRank(row, member.getNextRank(), member.getPendingClaim());
		addPendingClaims(row, member);
		return row;
	}

	private void addNextRank(JPanel row, FeedNextRank nextRank, FeedPendingClaim pendingClaim)
	{
		if (nextRank == null)
		{
			row.add(grayLine("Next rank: Top rank reached"));
			return;
		}

		row.add(grayLine("Next rank: " + valueOrUnknown(nextRank.getName())));
		row.add(grayLine("Needs: " + formatInt(nextRank.getPointCost()) + " points / "
			+ formatMonths(nextRank.getMinMonths())));

		if (Boolean.TRUE.equals(nextRank.getCanClaim()))
		{
			if (pendingClaim == null)
			{
				row.add(wrappedCardLine("Eligible to claim " + valueOrUnknown(nextRank.getName()) + ".", ALLOWED_COLOR));
			}
			return;
		}

		final int missingPoints = positive(nextRank.getMissingPoints());
		final int missingMonths = positive(nextRank.getMissingMonths());
		if (missingPoints > 0 || missingMonths > 0)
		{
			row.add(grayLine("Missing: " + formatInt(missingPoints) + " points / "
				+ formatMonths(missingMonths)));
		}
		if (nextRank.getRequirements() != null && !nextRank.getRequirements().trim().isEmpty())
		{
			row.add(wrappedCardLine("Requirement: " + nextRank.getRequirements().trim(), KEY_COLOR));
		}
	}

	private void addPendingClaims(JPanel row, FeedMember member)
	{
		final FeedPendingClaim pendingRank = member.getPendingClaim();
		if (pendingRank != null)
		{
			row.add(wrappedCardLine("Rank claim pending: " + valueOrUnknown(pendingRank.getRank())
				+ pendingDate(pendingRank.getCreatedAt()), ColorScheme.BRAND_ORANGE));
		}

		final FeedPendingClaim pendingJoinDate = member.getPendingJoinDateClaim();
		if (pendingJoinDate != null)
		{
			row.add(wrappedCardLine("Join-date claim pending: "
				+ formatDateLike(pendingJoinDate.getRequestedJoinDate())
				+ pendingDate(pendingJoinDate.getCreatedAt()), ColorScheme.BRAND_ORANGE));
		}
	}

	private void showBroadcasts(List<FeedBroadcast> broadcasts)
	{
		broadcastList.removeAll();
		if (broadcasts == null || broadcasts.isEmpty())
		{
			setBroadcastsMessage("No active broadcasts.");
			return;
		}

		broadcastStatus.setText(" ");
		for (FeedBroadcast broadcast : broadcasts)
		{
			broadcastList.add(broadcastRow(broadcast));
			broadcastList.add(verticalGap(6));
		}
		revalidate();
		repaint();
	}

	private void showEvents(List<FeedEvent> events)
	{
		eventsList.removeAll();
		if (events == null || events.isEmpty())
		{
			setEventsMessage("No upcoming events.");
			return;
		}

		eventsStatus.setText(" ");
		for (FeedEvent event : events)
		{
			eventsList.add(eventRow(event));
			eventsList.add(verticalGap(6));
		}
		revalidate();
		repaint();
	}

	private JPanel broadcastRow(FeedBroadcast broadcast)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(broadcast.getTitle())));
		row.add(wrappedCardLine(valueOrUnknown(broadcast.getMessage()), Color.LIGHT_GRAY));
		if (broadcast.getExpiresAt() != null && !broadcast.getExpiresAt().trim().isEmpty())
		{
			row.add(grayLine("Expires " + formatIso(broadcast.getExpiresAt())));
		}
		return row;
	}

	private JPanel eventRow(FeedEvent event)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(event.getTitle())));
		row.add(grayLine(eventType(event)));
		row.add(grayLine(formatIso(event.getStartsAt())));
		if (event.getLocation() != null && !event.getLocation().trim().isEmpty())
		{
			row.add(grayLine("@ " + event.getLocation()));
		}
		if (event.getDescription() != null && !event.getDescription().trim().isEmpty())
		{
			row.add(wrappedCardLine(event.getDescription(), Color.LIGHT_GRAY));
		}
		return row;
	}

	private static String eventType(FeedEvent event)
	{
		if ("skill_competition".equals(event.getType()))
		{
			return "Skill competition: " + valueOrUnknown(event.getMetricLabel());
		}
		if ("boss_competition".equals(event.getType()))
		{
			return "Boss competition: " + valueOrUnknown(event.getMetricLabel());
		}
		return "Clan event";
	}

	private static JPanel card()
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static JLabel boldLine(String text)
	{
		final JLabel label = wrappedCardLine(text, HEADING_COLOR);
		label.setFont(FontManager.getRunescapeBoldFont());
		return label;
	}

	private static JLabel grayLine(String text)
	{
		return wrappedCardLine(text, KEY_COLOR);
	}

	private static JLabel wrappedCardLine(String text, Color color)
	{
		final JLabel label = new JLabel("<html><div style='width:" + CARD_WRAP_WIDTH + "px'>" + escape(text) + "</div></html>");
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private void setBroadcastsMessage(String message)
	{
		broadcastList.removeAll();
		broadcastStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private void setProfileMessage(String message)
	{
		profileList.removeAll();
		profileStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private void setEventsMessage(String message)
	{
		eventsList.removeAll();
		eventsStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private static JPanel section(String heading)
	{
		final JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 8, 0)));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel title = new JLabel(heading);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(HEADING_COLOR);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(title);
		section.add(verticalGap(6));
		return section;
	}

	private static void setInfo(JLabel label, String key, String value)
	{
		label.setText("<html><div style='width:" + WRAP_WIDTH + "px'>"
			+ "<span style='color:" + hex(KEY_COLOR) + "'>" + escape(key) + " </span>"
			+ "<span style='color:" + hex(VALUE_COLOR) + "'>" + escape(value) + "</span></div></html>");
	}

	private static JLabel field()
	{
		final JLabel label = new JLabel();
		label.setForeground(VALUE_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static Component verticalGap(int height)
	{
		final JPanel spacer = new JPanel();
		spacer.setOpaque(false);
		spacer.setPreferredSize(new Dimension(0, height));
		spacer.setMinimumSize(new Dimension(0, height));
		spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		return spacer;
	}

	private static String wrapped(String message)
	{
		return "<html><body style='width:" + WRAP_WIDTH + "px'>" + escape(message) + "</body></html>";
	}

	private static String valueOrUnknown(String value)
	{
		return value == null || value.trim().isEmpty() ? "Unknown" : value;
	}

	private static String formatDateLike(String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return "Unknown";
		}

		final String trimmed = value.trim();
		if (trimmed.length() >= 10)
		{
			try
			{
				return DATE_FORMAT.format(LocalDate.parse(trimmed.substring(0, 10)));
			}
			catch (Exception ignored)
			{
				return trimmed;
			}
		}
		return trimmed;
	}

	private static String formatIso(String iso)
	{
		if (iso == null || iso.trim().isEmpty())
		{
			return "Date TBC";
		}
		try
		{
			return DATE_TIME_FORMAT.format(OffsetDateTime.parse(iso));
		}
		catch (Exception ignored)
		{
			try
			{
				return DATE_TIME_FORMAT.format(Instant.parse(iso));
			}
			catch (Exception alsoIgnored)
			{
				return iso;
			}
		}
	}

	private static FeedMember findMember(PluginFeed feed, String playerName)
	{
		final String playerKey = rsnKey(playerName);
		for (FeedMember member : feed.getMembers())
		{
			if (playerKey.equals(member.getRsnKey()) || playerKey.equals(rsnKey(member.getRsn())))
			{
				return member;
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

	private static String firstNonBlank(String first, String second)
	{
		return first != null && !first.trim().isEmpty() ? first : second;
	}

	private static String formatInt(Integer value)
	{
		return String.format(Locale.US, "%,d", value == null ? 0 : value);
	}

	private static String formatMonths(Integer months)
	{
		if (months == null)
		{
			return "Unknown";
		}
		return months == 1 ? "1 month" : formatInt(months) + " months";
	}

	private static int positive(Integer value)
	{
		return value == null ? 0 : Math.max(0, value);
	}

	private static String pendingDate(String createdAt)
	{
		if (createdAt == null || createdAt.trim().isEmpty())
		{
			return "";
		}
		return " since " + formatDateLike(createdAt);
	}

	private static String hex(Color color)
	{
		return String.format("#%06x", color.getRGB() & 0xFFFFFF);
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static final class ScrollableContentPanel extends JPanel implements Scrollable
	{
		ScrollableContentPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
