package com.lobsterpot.ui;

import com.lobsterpot.ClanMembershipService;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import com.lobsterpot.feed.FeedBounty;
import com.lobsterpot.feed.FeedBroadcast;
import com.lobsterpot.feed.FeedEvent;
import com.lobsterpot.feed.FeedMember;
import com.lobsterpot.feed.FeedNextRank;
import com.lobsterpot.feed.FeedPendingBounty;
import com.lobsterpot.feed.FeedPendingClaim;
import com.lobsterpot.feed.PluginFeed;
import com.lobsterpot.feed.PointHistoryEntry;
import com.lobsterpot.requirements.RankRequirementEvaluation;
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
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.ui.laf.RuneLiteScrollBarUI;

@Singleton
public class LobsterPotPanel extends PluginPanel
{
	private static final Color ALLOWED_COLOR = new Color(0x4CAF50);
	private static final Color DENIED_COLOR = new Color(0xE53935);
	private static final Color WARNING_COLOR = ColorScheme.BRAND_ORANGE;
	private static final Color HEADING_COLOR = Color.WHITE;
	private static final Color VALUE_COLOR = Color.WHITE;
	private static final Color KEY_COLOR = new Color(0x9E9E9E);
	private static final int WRAP_WIDTH = 150;
	private static final int CARD_WRAP_WIDTH = 142;
	private static final String CHECK = "\u2713";
	private static final String CROSS = "\u2717";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy");
	private static final DateTimeFormatter EVENT_DATE_FORMAT =
		DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter EVENT_TIME_FORMAT =
		DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter DATE_TIME_FORMAT =
		DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault());

	private final JLabel title = new JLabel();
	private final JLabel accessStatus = new JLabel();
	private final JLabel playerSummary = field();
	private final JLabel accessDetail = mutedLabel();
	private final JLabel nextRankStatus = mutedLabel();
	private final JPanel nextRankList = new JPanel();
	private final JLabel broadcastStatus = mutedLabel();
	private final JPanel broadcastList = new JPanel();
	private final JLabel eventsStatus = mutedLabel();
	private final JPanel eventsList = new JPanel();
	private final JPanel bountySection = section("Bounties");
	private final JLabel bountyStatus = mutedLabel();
	private final JPanel bountyList = new JPanel();
	private final JButton detailsToggle = new JButton();
	private final JPanel detailsContent = new JPanel();
	private final JLabel detailsStatus = mutedLabel();
	private final JPanel detailsList = new JPanel();
	// Profile tab
	private final JLabel profileStatus = mutedLabel();
	private final JPanel profileList = new JPanel();
	private final JLabel pointHistoryStatus = mutedLabel();
	private final JPanel pointHistoryList = new JPanel();
	// Bounties tab (claim list + claim history); Overview keeps its own bounty section above.
	private final JLabel bountyClaimStatus = mutedLabel();
	private final JPanel bountyClaimList = new JPanel();
	private final JLabel bountyHistoryStatus = mutedLabel();
	private final JPanel bountyHistoryList = new JPanel();
	private final JButton refresh = new JButton("Refresh");

	private Runnable refreshAction = () -> {};
	private BiConsumer<String, String> submitAction = (bountyId, note) -> {};
	// bountyId -> epoch millis of an optimistic "just submitted" that the feed may not reflect yet.
	// Honored only until a feed generated after this time arrives, then the feed status takes over.
	private final Map<String, Long> locallySubmitted = new HashMap<>();
	private final Set<String> inFlightBounties = new HashSet<>();
	private boolean bountiesEnabled = true;
	private String bountyActionMessage;
	private String currentPlayerName;
	private String currentRankName;
	private String currentAccessMessage;
	private boolean currentAccessAllowed;
	private boolean detailsExpanded;
	private PluginFeed currentFeed;
	private String currentFeedError;
	private RankRequirementEvaluation currentRequirementEvaluation;

	@Inject
	public LobsterPotPanel()
	{
		super(false);
	}

	public void init(Runnable refreshAction, BiConsumer<String, String> submitAction)
	{
		this.refreshAction = refreshAction;
		this.submitAction = submitAction;

		removeAll();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Persistent header (title + access), shown above the tabs on every tab.
		final JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(title);
		north.add(verticalGap(8));
		north.add(buildHeaderCard());
		north.add(verticalGap(8));

		// Tab strip drives which content shows in the display area.
		final JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final MaterialTabGroup tabGroup = new MaterialTabGroup(display);
		tabGroup.setLayout(new GridLayout(1, 3, 6, 0));
		tabGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
		tabGroup.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		final MaterialTab overviewTab = new MaterialTab("Overview", tabGroup, buildOverviewTab());
		final MaterialTab profileTab = new MaterialTab("Profile", tabGroup, buildProfileTab());
		final MaterialTab bountiesTab = new MaterialTab("Bounties", tabGroup, buildBountiesTab());
		tabGroup.addTab(overviewTab);
		tabGroup.addTab(profileTab);
		tabGroup.addTab(bountiesTab);
		north.add(tabGroup);

		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		add(buildRefreshBar(), BorderLayout.SOUTH);

		tabGroup.select(overviewTab);

		setDetailsExpanded(false);
		setChecking();
	}

	private JScrollPane buildOverviewTab()
	{
		final JPanel main = tabColumn();
		main.add(buildNextRankSection(false));
		main.add(verticalGap(10));
		main.add(buildBroadcastsSection());
		main.add(verticalGap(10));
		main.add(buildEventsSection());
		main.add(verticalGap(10));
		main.add(buildBountiesSection());
		main.add(verticalGap(10));
		main.add(buildDetailsSection());
		return scrollPane(main);
	}

	private JScrollPane buildProfileTab()
	{
		final JPanel main = tabColumn();
		final JPanel stats = section("Profile");
		stats.add(profileStatus);
		listPanel(profileList);
		stats.add(profileList);
		main.add(stats);
		main.add(verticalGap(10));

		final JPanel history = section("Point history");
		history.add(pointHistoryStatus);
		listPanel(pointHistoryList);
		history.add(pointHistoryList);
		main.add(history);
		return scrollPane(main);
	}

	private JScrollPane buildBountiesTab()
	{
		final JPanel main = tabColumn();
		final JPanel claim = section("Bounties");
		claim.add(bountyClaimStatus);
		listPanel(bountyClaimList);
		claim.add(bountyClaimList);
		main.add(claim);
		main.add(verticalGap(10));

		final JPanel history = section("Claim history");
		history.add(bountyHistoryStatus);
		listPanel(bountyHistoryList);
		history.add(bountyHistoryList);
		main.add(history);
		return scrollPane(main);
	}

	private JPanel buildRefreshBar()
	{
		final JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		refresh.setHorizontalAlignment(SwingConstants.CENTER);
		for (ActionListener listener : refresh.getActionListeners())
		{
			refresh.removeActionListener(listener);
		}
		refresh.addActionListener(e -> this.refreshAction.run());
		bar.add(refresh, BorderLayout.CENTER);
		return bar;
	}

	private static JPanel tabColumn()
	{
		final JPanel main = new JPanel();
		main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
		main.setOpaque(false);
		main.setAlignmentX(Component.LEFT_ALIGNMENT);
		return main;
	}

	private static void listPanel(JPanel panel)
	{
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	private static JScrollPane scrollPane(JPanel main)
	{
		final JPanel content = new ScrollableContentPanel();
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 14));
		content.add(main, BorderLayout.NORTH);

		final JScrollPane scrollPane = new JScrollPane(content,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUI(new RuneLiteScrollBarUI());
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		return scrollPane;
	}

	public void render(ClanAccess access)
	{
		if (access == null)
		{
			currentRequirementEvaluation = null;
			setChecking();
			return;
		}

		final String previousPlayerName = currentPlayerName;
		currentPlayerName = access.getPlayerName();
		currentRankName = access.getRankName();
		currentAccessMessage = access.getMessage();
		currentAccessAllowed = access.isAllowed();
		if (!currentAccessAllowed || !rsnKey(currentPlayerName).equals(rsnKey(previousPlayerName)))
		{
			currentRequirementEvaluation = null;
		}
		updateHeader();
		showProfile();
		showProfileDetails();

		revalidate();
		repaint();
	}

	public void setFeedLoading()
	{
		currentFeed = null;
		currentFeedError = null;
		currentRequirementEvaluation = null;
		setNextRankMessage("Loading clan profile...");
		setDetailsMessage("Loading clan details...");
		setBroadcastsMessage("Loading broadcasts...");
		setEventsMessage("Loading events...");
		setBountiesMessage("Loading bounties...");
		setListMessage(profileList, profileStatus, "Loading profile...");
		setListMessage(pointHistoryList, pointHistoryStatus, "Loading point history...");
	}

	public void clearFeed(String message)
	{
		currentFeed = null;
		currentFeedError = message;
		currentRequirementEvaluation = null;
		setNextRankMessage(message);
		setDetailsMessage(message);
		setBroadcastsMessage(message);
		setEventsMessage(message);
		setBountiesMessage(message);
		setListMessage(profileList, profileStatus, message);
		setListMessage(pointHistoryList, pointHistoryStatus, message);
	}

	public void renderFeed(PluginFeed feed, String error)
	{
		currentFeed = feed;
		currentFeedError = error;

		if (error != null)
		{
			currentRequirementEvaluation = null;
			setNextRankMessage(error);
			setDetailsMessage(error);
			setBroadcastsMessage(error);
			setEventsMessage(error);
			setBountiesMessage(error);
			setListMessage(profileList, profileStatus, error);
			setListMessage(pointHistoryList, pointHistoryStatus, error);
			return;
		}
		if (feed == null)
		{
			currentRequirementEvaluation = null;
			setNextRankMessage("No feed loaded.");
			setDetailsMessage("No feed loaded.");
			setBroadcastsMessage("No feed loaded.");
			setEventsMessage("No feed loaded.");
			setBountiesMessage("No feed loaded.");
			setListMessage(profileList, profileStatus, "No feed loaded.");
			setListMessage(pointHistoryList, pointHistoryStatus, "No feed loaded.");
			return;
		}

		showProfile();
		showProfileDetails();
		showBroadcasts(feed.getBroadcasts());
		showEvents(feed.getEvents());
		showBounties(feed.getBounties(), findMember(feed, currentPlayerName));
	}

	public void renderRequirementEvaluation(RankRequirementEvaluation evaluation)
	{
		currentRequirementEvaluation = evaluation;
		showProfile();
		showProfileDetails();
	}

	private void setChecking()
	{
		currentRequirementEvaluation = null;
		currentPlayerName = null;
		currentRankName = null;
		currentAccessMessage = "Checking clan membership...";
		currentAccessAllowed = false;
		updateHeader();
		setNextRankMessage("Log in to view your clan profile.");
		setDetailsMessage("Log in to view clan details.");
		showProfileDetails();
		setBountiesMessage("Log in to view clan bounties.");
	}

	private JPanel buildHeaderCard()
	{
		final JPanel header = card();
		accessStatus.setFont(FontManager.getRunescapeBoldFont());
		accessStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		playerSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
		accessDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(accessStatus);
		header.add(verticalGap(4));
		header.add(playerSummary);
		header.add(verticalGap(4));
		header.add(accessDetail);
		return header;
	}

	private JPanel buildNextRankSection(boolean showHeading)
	{
		final JPanel section = showHeading ? section("Next rank") : plainSection();
		section.add(nextRankStatus);
		nextRankList.setLayout(new BoxLayout(nextRankList, BoxLayout.Y_AXIS));
		nextRankList.setOpaque(false);
		nextRankList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(nextRankList);
		return section;
	}

	private JPanel buildBroadcastsSection()
	{
		final JPanel section = section("Broadcasts");
		section.add(broadcastStatus);
		broadcastList.setLayout(new BoxLayout(broadcastList, BoxLayout.Y_AXIS));
		broadcastList.setOpaque(false);
		broadcastList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(broadcastList);
		return section;
	}

	private JPanel buildEventsSection()
	{
		final JPanel section = section("Upcoming Events");
		section.add(eventsStatus);
		eventsList.setLayout(new BoxLayout(eventsList, BoxLayout.Y_AXIS));
		eventsList.setOpaque(false);
		eventsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(eventsList);
		return section;
	}

	private JPanel buildBountiesSection()
	{
		bountySection.add(bountyStatus);
		bountyList.setLayout(new BoxLayout(bountyList, BoxLayout.Y_AXIS));
		bountyList.setOpaque(false);
		bountyList.setAlignmentX(Component.LEFT_ALIGNMENT);
		bountySection.add(bountyList);
		return bountySection;
	}

	private JPanel buildDetailsSection()
	{
		final JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		detailsToggle.setFont(FontManager.getRunescapeBoldFont());
		detailsToggle.setForeground(HEADING_COLOR);
		detailsToggle.setHorizontalAlignment(SwingConstants.LEFT);
		detailsToggle.setContentAreaFilled(false);
		detailsToggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 8, 0)));
		detailsToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (ActionListener listener : detailsToggle.getActionListeners())
		{
			detailsToggle.removeActionListener(listener);
		}
		detailsToggle.addActionListener(e -> setDetailsExpanded(!detailsExpanded));
		section.add(detailsToggle);

		detailsContent.setLayout(new BoxLayout(detailsContent, BoxLayout.Y_AXIS));
		detailsContent.setOpaque(false);
		detailsContent.setAlignmentX(Component.LEFT_ALIGNMENT);
		detailsContent.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		detailsContent.add(detailsStatus);

		detailsList.setLayout(new BoxLayout(detailsList, BoxLayout.Y_AXIS));
		detailsList.setOpaque(false);
		detailsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		detailsContent.add(detailsList);
		section.add(detailsContent);
		return section;
	}

	private void updateHeader()
	{
		title.setText(titleText(currentAccessAllowed));
		accessStatus.setVisible(!currentAccessAllowed);
		accessStatus.setForeground(currentAccessAllowed ? ALLOWED_COLOR : DENIED_COLOR);
		accessStatus.setText(wrapped((currentAccessAllowed ? CHECK + " Access granted" : CROSS + " Access denied")));

		final String playerName = firstNonBlank(currentPlayerName, "Unknown character");
		final String rankName = firstNonBlank(currentRankName, "Rank unknown");
		playerSummary.setText(wrapped(playerName + " - " + rankName));

		accessDetail.setVisible(!currentAccessAllowed);
		if (!currentAccessAllowed)
		{
			final String detail = firstNonBlank(currentAccessMessage,
				"Required clan: " + ClanMembershipService.REQUIRED_CLAN_NAME);
			accessDetail.setText(wrapped(detail));
		}
	}

	private static String titleText(boolean accessAllowed)
	{
		if (!accessAllowed)
		{
			return "<html><span style='color:" + hex(ColorScheme.BRAND_ORANGE) + "'>The Lobster Pot</span></html>";
		}
		return "<html><span style='color:" + hex(ColorScheme.BRAND_ORANGE) + "'>The Lobster Pot</span>"
			+ " <span style='color:" + hex(ALLOWED_COLOR) + "'>" + CHECK + "</span></html>";
	}

	private void showProfile()
	{
		nextRankList.removeAll();
		detailsList.removeAll();
		if (currentFeedError != null)
		{
			setNextRankMessage(currentFeedError);
			setDetailsMessage(currentFeedError);
			return;
		}
		if (currentPlayerName == null || currentPlayerName.trim().isEmpty())
		{
			setNextRankMessage("Log in to view your clan profile.");
			setDetailsMessage("Log in to view clan details.");
			return;
		}
		if (!currentAccessAllowed)
		{
			setNextRankMessage("Available for LobsterPot clan members.");
			setDetailsMessage("Clan details are available after access is granted.");
			return;
		}
		if (currentFeed == null)
		{
			setNextRankMessage("Loading clan profile...");
			setDetailsMessage("Loading clan details...");
			return;
		}

		final FeedMember member = findMember(currentFeed, currentPlayerName);
		if (member == null)
		{
			setNextRankMessage("No clan profile found for " + currentPlayerName + ".");
			setDetailsMessage("No clan profile found.");
			return;
		}

		currentRankName = firstNonBlank(member.getProgressionRank(), member.getBotRank(), currentRankName);
		updateHeader();
		nextRankStatus.setVisible(false);
		detailsStatus.setVisible(false);
		nextRankList.add(nextRankCard(member));
		detailsList.add(detailsCard(member));
		revalidate();
		repaint();
	}

	private JPanel nextRankCard(FeedMember member)
	{
		final JPanel row = card();
		final FeedNextRank nextRank = member.getNextRank();
		if (nextRank == null)
		{
			row.add(boldLine("Top rank reached"));
			row.add(statusLine(CHECK + " All requirements met", ALLOWED_COLOR));
			addPendingClaims(row, member);
			return row;
		}

		row.add(boldLine("Next rank: " + valueOrUnknown(nextRank.getName())));
		row.add(verticalGap(6));
		row.add(createProgressRow("Points", currentRankPoints(member, nextRank), nextRank.getPointCost(), ""));
		row.add(verticalGap(6));
		row.add(createProgressRow("Time in clan", member.getMonthsInClan(), nextRank.getMinMonths(), "months"));
		addRequirementLine(row, nextRank);
		addBlockers(row, nextRank);
		addPendingClaims(row, member);
		return row;
	}

	private JPanel detailsCard(FeedMember member)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(member.getRsn())));
		row.add(grayLine("Rank: " + valueOrUnknown(firstNonBlank(member.getProgressionRank(), member.getBotRank()))));
		row.add(grayLine("Join date: " + formatDateLike(member.getJoinDate())));
		row.add(grayLine("Points available: " + formatInt(member.getPointsAvailable())));
		row.add(grayLine(formatInt(member.getPointsTotal()) + " total / "
			+ formatInt(member.getPointsSpent()) + " spent"));
		row.add(grayLine("Time in clan: " + formatMonths(member.getMonthsInClan())));
		return row;
	}

	private void addRequirementLine(JPanel row, FeedNextRank nextRank)
	{
		final String requirementText = specialRequirementText(nextRank);
		if (!hasText(requirementText))
		{
			return;
		}

		row.add(verticalGap(6));
		if (currentRequirementEvaluation != null
			&& currentRequirementEvaluation.getStatus() == RankRequirementEvaluation.Status.MET)
		{
			row.add(statusLine(CHECK + " " + currentRequirementEvaluation.getMessage(), ALLOWED_COLOR));
			return;
		}
		row.add(grayLine("Special requirement: " + requirementText));
	}

	private void addBlockers(JPanel row, FeedNextRank nextRank)
	{
		final int missingPoints = positive(nextRank.getMissingPoints());
		final int missingMonths = positive(nextRank.getMissingMonths());
		final String requirementText = specialRequirementText(nextRank);
		final boolean hasRequirement = hasText(requirementText);
		final boolean requirementMet = !hasRequirement
			|| (currentRequirementEvaluation != null
			&& currentRequirementEvaluation.getStatus() == RankRequirementEvaluation.Status.MET);

		row.add(verticalGap(6));
		row.add(smallHeading("Blocking requirements"));
		if (missingPoints > 0)
		{
			row.add(statusLine(CROSS + " " + formatInt(missingPoints) + " more points needed", DENIED_COLOR));
		}
		else if (nextRank.getPointCost() != null)
		{
			row.add(statusLine(CHECK + " Points requirement met", ALLOWED_COLOR));
		}

		if (missingMonths > 0)
		{
			row.add(statusLine(CROSS + " " + moreMonthsText(missingMonths) + " needed", DENIED_COLOR));
		}
		else if (nextRank.getMinMonths() != null)
		{
			row.add(statusLine(CHECK + " Time requirement met", ALLOWED_COLOR));
		}

		addSpecialRequirementBlocker(row, requirementText);

		if (missingPoints == 0 && missingMonths == 0 && requirementMet)
		{
			row.add(statusLine(CHECK + " All requirements met", ALLOWED_COLOR));
		}
	}

	private void addSpecialRequirementBlocker(JPanel row, String requirementText)
	{
		if (!hasText(requirementText))
		{
			return;
		}
		if (currentRequirementEvaluation == null)
		{
			row.add(statusLine("? " + requirementText + " not verified", KEY_COLOR));
			return;
		}

		final RankRequirementEvaluation.Status status = currentRequirementEvaluation.getStatus();
		if (status == RankRequirementEvaluation.Status.MET)
		{
			row.add(statusLine(CHECK + " " + currentRequirementEvaluation.getMessage(), ALLOWED_COLOR));
		}
		else if (status == RankRequirementEvaluation.Status.MISSING)
		{
			row.add(statusLine(CROSS + " " + currentRequirementEvaluation.getMessage(), DENIED_COLOR));
		}
		else
		{
			row.add(statusLine("? " + currentRequirementEvaluation.getMessage(), KEY_COLOR));
		}
	}

	private String specialRequirementText(FeedNextRank nextRank)
	{
		if (nextRank != null && hasText(nextRank.getRequirements()))
		{
			return nextRank.getRequirements().trim();
		}
		if (currentRequirementEvaluation != null && hasText(currentRequirementEvaluation.getRequirementText()))
		{
			return currentRequirementEvaluation.getRequirementText().trim();
		}
		return null;
	}

	private void addPendingClaims(JPanel row, FeedMember member)
	{
		final FeedPendingClaim pendingRank = member.getPendingClaim();
		if (pendingRank != null)
		{
			row.add(verticalGap(6));
			row.add(wrappedCardLine("Rank claim pending: " + valueOrUnknown(pendingRank.getRank())
				+ pendingDate(pendingRank.getCreatedAt()), WARNING_COLOR));
		}

		final FeedPendingClaim pendingJoinDate = member.getPendingJoinDateClaim();
		if (pendingJoinDate != null)
		{
			row.add(verticalGap(6));
			row.add(wrappedCardLine("Join-date claim pending: "
				+ formatDateLike(pendingJoinDate.getRequestedJoinDate())
				+ pendingDate(pendingJoinDate.getCreatedAt()), WARNING_COLOR));
		}
	}

	private void showBroadcasts(List<FeedBroadcast> broadcasts)
	{
		broadcastList.removeAll();
		if (broadcasts == null || broadcasts.isEmpty())
		{
			setBroadcastsMessage("No active broadcasts");
			return;
		}

		broadcastStatus.setVisible(false);
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
			setEventsMessage("No upcoming events");
			return;
		}

		eventsStatus.setVisible(false);
		for (FeedEvent event : events)
		{
			eventsList.add(eventRow(event));
			eventsList.add(verticalGap(6));
		}
		revalidate();
		repaint();
	}

	private void showBounties(List<FeedBounty> bounties, FeedMember member)
	{
		renderBountySummary(bountyList, bountyStatus, bounties);
		renderActiveBounties(bountyClaimList, bountyClaimStatus, bounties, member);
		showBountyHistory(member);
	}

	// Overview shows a read-only, at-a-glance list of active bounties; claiming and details live in
	// the Bounties tab (renderActiveBounties). No note field, submit button, or per-member status here.
	private void renderBountySummary(JPanel listPanel, JLabel statusLabel, List<FeedBounty> bounties)
	{
		listPanel.removeAll();

		if (currentPlayerName == null || currentPlayerName.trim().isEmpty())
		{
			setListMessage(listPanel, statusLabel, "Log in to view clan bounties.");
			return;
		}
		if (!currentAccessAllowed)
		{
			setListMessage(listPanel, statusLabel, "Available for LobsterPot clan members.");
			return;
		}

		int shown = 0;
		final JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		rows.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (bounties != null)
		{
			for (FeedBounty bounty : bounties)
			{
				if (bounty == null || !Boolean.TRUE.equals(bounty.getActive()) || !hasText(bounty.getId()))
				{
					continue;
				}
				rows.add(bountySummaryRow(bounty));
				rows.add(verticalGap(6));
				shown++;
			}
		}

		if (shown == 0)
		{
			setListMessage(listPanel, statusLabel, "No active bounties");
			return;
		}

		statusLabel.setVisible(false);
		listPanel.add(statusLine("Claim bounties and view details in the Bounties tab.", KEY_COLOR));
		listPanel.add(verticalGap(6));
		listPanel.add(rows);
		revalidate();
		repaint();
	}

	private JPanel bountySummaryRow(FeedBounty bounty)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(bounty.getName()) + pointsSuffix(bounty)));
		return row;
	}

	private void renderActiveBounties(JPanel listPanel, JLabel statusLabel, List<FeedBounty> bounties, FeedMember member)
	{
		listPanel.removeAll();

		if (currentPlayerName == null || currentPlayerName.trim().isEmpty())
		{
			setListMessage(listPanel, statusLabel, "Log in to view clan bounties.");
			return;
		}
		if (!currentAccessAllowed)
		{
			setListMessage(listPanel, statusLabel, "Available for LobsterPot clan members.");
			return;
		}

		statusLabel.setVisible(false);

		if (hasText(bountyActionMessage))
		{
			listPanel.add(wrappedCardLine(bountyActionMessage, WARNING_COLOR));
			listPanel.add(verticalGap(6));
		}

		int shown = 0;
		if (bounties != null)
		{
			for (FeedBounty bounty : bounties)
			{
				if (bounty == null || !Boolean.TRUE.equals(bounty.getActive()) || !hasText(bounty.getId()))
				{
					continue;
				}
				listPanel.add(bountyRow(bounty, member));
				listPanel.add(verticalGap(6));
				shown++;
			}
		}

		if (shown == 0 && !hasText(bountyActionMessage))
		{
			setListMessage(listPanel, statusLabel, "No active bounties");
			return;
		}
		revalidate();
		repaint();
	}

	private void showBountyHistory(FeedMember member)
	{
		bountyHistoryList.removeAll();

		if (!currentAccessAllowed || member == null)
		{
			setListMessage(bountyHistoryList, bountyHistoryStatus, "Your decided bounty claims will show here.");
			return;
		}

		bountyHistoryStatus.setVisible(false);
		int shown = 0;
		for (FeedPendingBounty pending : member.getPendingBounties())
		{
			if (pending == null)
			{
				continue;
			}
			final String status = pending.getStatus();
			if (!"approved".equalsIgnoreCase(status) && !"rejected".equalsIgnoreCase(status))
			{
				continue;
			}
			bountyHistoryList.add(bountyHistoryRow(pending));
			bountyHistoryList.add(verticalGap(6));
			shown++;
		}

		if (shown == 0)
		{
			setListMessage(bountyHistoryList, bountyHistoryStatus, "No decided bounty claims yet.");
			return;
		}
		revalidate();
		repaint();
	}

	private JPanel bountyHistoryRow(FeedPendingBounty pending)
	{
		final JPanel row = card();
		final boolean approved = "approved".equalsIgnoreCase(pending.getStatus());
		row.add(boldLine(valueOrUnknown(pending.getName())));
		row.add(statusLine((approved ? CHECK + " Approved" : CROSS + " Rejected") + pendingDate(pending.getCreatedAt()),
			approved ? ALLOWED_COLOR : DENIED_COLOR));
		if (hasText(pending.getReason()))
		{
			row.add(wrappedCardLine("Reason: " + pending.getReason().trim(), Color.LIGHT_GRAY));
		}
		return row;
	}

	private void setListMessage(JPanel listPanel, JLabel statusLabel, String message)
	{
		listPanel.removeAll();
		statusLabel.setVisible(true);
		statusLabel.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private JPanel bountyRow(FeedBounty bounty, FeedMember member)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(bounty.getName()) + pointsSuffix(bounty)));
		if (hasText(bounty.getDescription()))
		{
			row.add(wrappedCardLine(bounty.getDescription().trim(), Color.LIGHT_GRAY));
		}

		// A submission for this bounty is already being sent - don't offer the button again.
		if (inFlightBounties.contains(bounty.getId()))
		{
			row.add(verticalGap(4));
			row.add(statusLine("Submitting...", WARNING_COLOR));
			return row;
		}

		// Optimistic "just submitted" state bridges feed lag, but only until the feed catches up: a
		// feed generated after we submitted already reflects the outcome (pending/approved/rejected),
		// so we drop the optimistic flag and let the feed status below take over.
		final Long submittedAt = locallySubmitted.get(bounty.getId());
		if (submittedAt != null)
		{
			if (feedReflectsSubmit(submittedAt))
			{
				locallySubmitted.remove(bounty.getId());
			}
			else
			{
				row.add(verticalGap(4));
				row.add(statusLine("Submitted - pending approval", WARNING_COLOR));
				return row;
			}
		}

		final FeedPendingBounty pending = findPendingBounty(member, bounty.getId());
		final String status = pending == null ? null : pending.getStatus();
		if ("approved".equalsIgnoreCase(status))
		{
			row.add(verticalGap(4));
			row.add(statusLine(CHECK + " Approved" + pendingDate(pending.getCreatedAt()), ALLOWED_COLOR));
			return row;
		}
		if (pending != null && !"rejected".equalsIgnoreCase(status))
		{
			// Awaiting a decision - block another submission until it's decided.
			row.add(verticalGap(4));
			row.add(statusLine("Pending approval" + pendingDate(pending.getCreatedAt()), WARNING_COLOR));
			return row;
		}
		if ("rejected".equalsIgnoreCase(status))
		{
			// A rejected claim can be retried, so show why and keep the submit control below.
			row.add(verticalGap(4));
			row.add(statusLine(CROSS + " Rejected - you can claim again", DENIED_COLOR));
			if (hasText(pending.getReason()))
			{
				row.add(wrappedCardLine("Reason: " + pending.getReason().trim(), Color.LIGHT_GRAY));
			}
		}

		if (!bountiesEnabled)
		{
			return row;
		}

		final JTextField noteField = new JTextField();
		noteField.setToolTipText("Optional note or evidence link");
		noteField.setAlignmentX(Component.LEFT_ALIGNMENT);
		noteField.setMaximumSize(new Dimension(Integer.MAX_VALUE, noteField.getPreferredSize().height));
		row.add(verticalGap(4));
		row.add(noteField);

		final JButton submit = new JButton("Submit completion");
		submit.setAlignmentX(Component.LEFT_ALIGNMENT);
		submit.setMaximumSize(new Dimension(Integer.MAX_VALUE, submit.getPreferredSize().height));
		final String bountyId = bounty.getId();
		submit.addActionListener(e ->
		{
			// Guard against double-fire (rapid clicks, or the same bounty's button in another tab).
			if (!inFlightBounties.add(bountyId))
			{
				return;
			}
			final String note = noteField.getText();
			bountyActionMessage = null;
			submitAction.accept(bountyId, note);
			refreshBounties();
		});
		row.add(verticalGap(4));
		row.add(submit);
		return row;
	}

	private static String pointsSuffix(FeedBounty bounty)
	{
		return bounty.getPoints() == null ? "" : " (" + formatInt(bounty.getPoints()) + " pts)";
	}

	private static FeedPendingBounty findPendingBounty(FeedMember member, String bountyId)
	{
		if (member == null || !hasText(bountyId))
		{
			return null;
		}
		for (FeedPendingBounty pending : member.getPendingBounties())
		{
			if (pending != null && bountyId.equals(pending.getBountyId()))
			{
				return pending;
			}
		}
		return null;
	}

	private void refreshBounties()
	{
		final List<FeedBounty> bounties = currentFeed == null ? null : currentFeed.getBounties();
		final FeedMember member = currentFeed == null ? null : findMember(currentFeed, currentPlayerName);
		showBounties(bounties, member);
	}

	public void setBountiesEnabled(boolean enabled)
	{
		this.bountiesEnabled = enabled;
		refreshBounties();
	}

	public void markBountySubmitted(String bountyId)
	{
		if (hasText(bountyId))
		{
			locallySubmitted.put(bountyId, System.currentTimeMillis());
			inFlightBounties.remove(bountyId);
		}
		bountyActionMessage = null;
		refreshBounties();
	}

	// True once the loaded feed was generated at/after the given submit time, i.e. it already reflects
	// that submission's outcome. Unparseable/absent feed -> false, so the optimistic state persists.
	private boolean feedReflectsSubmit(long submittedAtMillis)
	{
		if (currentFeed == null)
		{
			return false;
		}
		final Instant generatedAt = parseInstant(currentFeed.getGeneratedAt());
		return generatedAt != null && generatedAt.toEpochMilli() >= submittedAtMillis;
	}

	public void markBountyFailed(String bountyId, String message)
	{
		if (hasText(bountyId))
		{
			inFlightBounties.remove(bountyId);
		}
		bountyActionMessage = hasText(message) ? message : "Could not submit bounty.";
		refreshBounties();
	}

	private void showProfileDetails()
	{
		profileList.removeAll();

		if (currentPlayerName == null || currentPlayerName.trim().isEmpty())
		{
			setListMessage(profileList, profileStatus, "Log in to view your profile.");
			setListMessage(pointHistoryList, pointHistoryStatus, "Log in to view point history.");
			return;
		}
		if (!currentAccessAllowed)
		{
			setListMessage(profileList, profileStatus, "Available for LobsterPot clan members.");
			setListMessage(pointHistoryList, pointHistoryStatus, "Available for LobsterPot clan members.");
			return;
		}
		if (currentFeed == null)
		{
			setListMessage(profileList, profileStatus, "Loading profile...");
			setListMessage(pointHistoryList, pointHistoryStatus, "Loading point history...");
			return;
		}

		final FeedMember member = findMember(currentFeed, currentPlayerName);
		if (member == null)
		{
			setListMessage(profileList, profileStatus, "No clan profile found for " + currentPlayerName + ".");
			setListMessage(pointHistoryList, pointHistoryStatus, "No clan profile found.");
			return;
		}

		profileStatus.setVisible(false);
		profileList.add(detailsCard(member));
		showPointHistory(member);
		revalidate();
		repaint();
	}

	private void showPointHistory(FeedMember member)
	{
		pointHistoryList.removeAll();
		final List<PointHistoryEntry> history = member.getPointsHistory();
		if (history.isEmpty())
		{
			setListMessage(pointHistoryList, pointHistoryStatus, "No point history yet.");
			return;
		}

		pointHistoryStatus.setVisible(false);
		for (int i = history.size() - 1; i >= 0; i--)
		{
			final PointHistoryEntry entry = history.get(i);
			if (entry == null)
			{
				continue;
			}
			pointHistoryList.add(pointHistoryRow(entry));
			pointHistoryList.add(verticalGap(6));
		}
		revalidate();
		repaint();
	}

	private JPanel pointHistoryRow(PointHistoryEntry entry)
	{
		final JPanel row = card();
		final Integer delta = entry.getDelta();
		final String deltaText = delta == null ? "Points" : (delta >= 0 ? "+" : "") + formatInt(delta) + " pts";
		final String balanceText = entry.getBalance() == null ? "" : "  (balance " + formatInt(entry.getBalance()) + ")";
		row.add(statusLine(deltaText + balanceText, delta != null && delta < 0 ? DENIED_COLOR : ALLOWED_COLOR));
		if (hasText(entry.getReason()))
		{
			row.add(wrappedCardLine(entry.getReason().trim(), Color.LIGHT_GRAY));
		}
		if (hasText(entry.getTimestamp()))
		{
			row.add(grayLine(formatDateLike(entry.getTimestamp())));
		}
		return row;
	}

	private JPanel broadcastRow(FeedBroadcast broadcast)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(broadcast.getTitle())));
		row.add(wrappedCardLine(valueOrUnknown(broadcast.getMessage()), Color.LIGHT_GRAY));
		if (hasText(broadcast.getExpiresAt()))
		{
			row.add(grayLine("Expires " + formatIso(broadcast.getExpiresAt())));
		}
		return row;
	}

	private JPanel eventRow(FeedEvent event)
	{
		final JPanel row = card();
		row.add(boldLine(valueOrUnknown(event.getTitle())));
		row.add(grayLine(eventMeta(event)));
		if (hasText(event.getLocation()))
		{
			row.add(grayLine("@ " + event.getLocation().trim()));
		}
		final String relativeTiming = relativeTiming(event);
		if (relativeTiming != null)
		{
			row.add(wrappedCardLine(relativeTiming, relativeTiming.equals("Live now") ? ALLOWED_COLOR : KEY_COLOR));
		}
		if (hasText(event.getDescription()))
		{
			row.add(wrappedCardLine(event.getDescription(), Color.LIGHT_GRAY));
		}
		return row;
	}

	private static String eventMeta(FeedEvent event)
	{
		return eventType(event) + " - " + formatEventDateTime(event.getStartsAt());
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

	private static JPanel createProgressRow(String label, Integer current, Integer required, String suffix)
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JPanel textRow = new JPanel(new BorderLayout(6, 0));
		textRow.setOpaque(false);
		textRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel labelText = new JLabel(label);
		labelText.setForeground(KEY_COLOR);
		final JLabel valueText = new JLabel(progressText(current, required, suffix));
		valueText.setForeground(VALUE_COLOR);
		textRow.add(labelText, BorderLayout.WEST);
		textRow.add(valueText, BorderLayout.EAST);
		row.add(textRow);
		row.add(verticalGap(3));

		final JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(progressPercent(current, required));
		bar.setStringPainted(false);
		bar.setForeground(isRequirementMet(current, required) ? ALLOWED_COLOR : WARNING_COLOR);
		bar.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.setPreferredSize(new Dimension(1, 8));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(bar);
		return row;
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

	private static JLabel smallHeading(String text)
	{
		final JLabel label = wrappedCardLine(text, HEADING_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private static JLabel grayLine(String text)
	{
		return wrappedCardLine(text, KEY_COLOR);
	}

	private static JLabel statusLine(String text, Color color)
	{
		final JLabel label = wrappedCardLine(text, color);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
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
		broadcastStatus.setVisible(true);
		broadcastStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private void setNextRankMessage(String message)
	{
		nextRankList.removeAll();
		nextRankStatus.setVisible(true);
		nextRankStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private void setDetailsMessage(String message)
	{
		detailsList.removeAll();
		detailsStatus.setVisible(true);
		detailsStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private void setEventsMessage(String message)
	{
		eventsList.removeAll();
		eventsStatus.setVisible(true);
		eventsStatus.setText(wrapped(message));
		revalidate();
		repaint();
	}

	private void setBountiesMessage(String message)
	{
		setListMessage(bountyList, bountyStatus, message);
		setListMessage(bountyClaimList, bountyClaimStatus, message);
		setListMessage(bountyHistoryList, bountyHistoryStatus, message);
	}

	private void setDetailsExpanded(boolean expanded)
	{
		detailsExpanded = expanded;
		detailsToggle.setText((detailsExpanded ? "\u25be" : "\u25b8") + " Clan details");
		detailsContent.setVisible(detailsExpanded);
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

	private static JPanel plainSection()
	{
		final JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		return section;
	}

	private static JLabel field()
	{
		final JLabel label = new JLabel();
		label.setForeground(VALUE_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel mutedLabel()
	{
		final JLabel label = field();
		label.setForeground(KEY_COLOR);
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
		return hasText(value) ? value.trim() : "Unknown";
	}

	private static String formatDateLike(String value)
	{
		if (!hasText(value))
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
		if (!hasText(iso))
		{
			return "Date TBC";
		}
		final Instant instant = parseInstant(iso);
		return instant == null ? iso.trim() : DATE_TIME_FORMAT.format(instant);
	}

	private static String formatEventDateTime(String iso)
	{
		if (!hasText(iso))
		{
			return "Date TBC";
		}
		final Instant instant = parseInstant(iso);
		if (instant == null)
		{
			return iso.trim();
		}
		return EVENT_DATE_FORMAT.format(instant) + " - " + EVENT_TIME_FORMAT.format(instant);
	}

	private static String relativeTiming(FeedEvent event)
	{
		final Instant start = parseInstant(event.getStartsAt());
		if (start == null)
		{
			return null;
		}

		final Instant now = Instant.now();
		final Instant end = parseInstant(event.getEndsAt());
		if (end != null && !now.isBefore(start) && now.isBefore(end))
		{
			return "Live now";
		}

		final ZoneId zone = ZoneId.systemDefault();
		final LocalDate today = LocalDate.now(zone);
		final LocalDate startDate = LocalDate.ofInstant(start, zone);
		final long days = ChronoUnit.DAYS.between(today, startDate);
		if (days < 0)
		{
			return null;
		}
		if (days == 0)
		{
			return "Starts today";
		}
		if (days == 1)
		{
			return "Starts tomorrow";
		}
		if (days < 14)
		{
			return "Starts in " + days + " days";
		}
		if (days <= 45)
		{
			final long weeks = (days + 6) / 7;
			return "Starts in " + weeks + (weeks == 1 ? " week" : " weeks");
		}

		long months = ChronoUnit.MONTHS.between(today.withDayOfMonth(1), startDate.withDayOfMonth(1));
		months = Math.max(1, months);
		return "Starts in " + months + (months == 1 ? " month" : " months");
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

	private static String firstNonBlank(String... values)
	{
		for (String value : values)
		{
			if (hasText(value))
			{
				return value.trim();
			}
		}
		return null;
	}

	private static Integer currentRankPoints(FeedMember member, FeedNextRank nextRank)
	{
		if (member.getPointsAvailable() != null)
		{
			return member.getPointsAvailable();
		}
		if (member.getPointsTotal() != null)
		{
			return member.getPointsTotal();
		}
		if (nextRank.getPointCost() != null && nextRank.getMissingPoints() != null)
		{
			return Math.max(0, nextRank.getPointCost() - positive(nextRank.getMissingPoints()));
		}
		return null;
	}

	private static String moreMonthsText(int months)
	{
		return months == 1 ? "1 more month" : formatInt(months) + " more months";
	}

	private static String progressText(Integer current, Integer required, String suffix)
	{
		final String currentText = current == null ? "?" : formatInt(current);
		final String requiredText = required == null || required <= 0 ? "?" : formatInt(required);
		final String units = hasText(suffix) ? " " + suffix.trim() : "";
		return currentText + " / " + requiredText + units;
	}

	private static int progressPercent(Integer current, Integer required)
	{
		if (current == null || required == null || required <= 0)
		{
			return 0;
		}
		return Math.min(100, Math.max(0, (int) Math.round((current * 100.0) / required)));
	}

	private static boolean isRequirementMet(Integer current, Integer required)
	{
		return current != null && required != null && required > 0 && current >= required;
	}

	private static String formatInt(Integer value)
	{
		return String.format(Locale.US, "%,d", value == null ? 0 : value);
	}

	private static String formatMonths(Integer months)
	{
		if (months == null)
		{
			return "Unknown months";
		}
		return months == 1 ? "1 month" : formatInt(months) + " months";
	}

	private static int positive(Integer value)
	{
		return value == null ? 0 : Math.max(0, value);
	}

	private static boolean hasText(String value)
	{
		return value != null && !value.trim().isEmpty();
	}

	private static String pendingDate(String createdAt)
	{
		if (!hasText(createdAt))
		{
			return "";
		}
		return " since " + formatDateLike(createdAt);
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String hex(Color color)
	{
		return String.format("#%06x", color.getRGB() & 0xFFFFFF);
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
