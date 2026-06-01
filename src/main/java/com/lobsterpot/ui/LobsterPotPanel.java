package com.lobsterpot.ui;

import com.lobsterpot.LobsterPotConfig;
import com.lobsterpot.SessionManager;
import com.lobsterpot.api.ApiCallback;
import com.lobsterpot.api.LobsterPotApiClient;
import com.lobsterpot.api.model.ClanEvent;
import com.lobsterpot.api.model.LoginResponse;
import com.lobsterpot.api.model.NewsPost;
import com.lobsterpot.api.model.Profile;
import com.lobsterpot.api.model.RankAvailable;
import com.lobsterpot.api.model.RankClaimResponse;
import com.lobsterpot.api.model.RankInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.laf.RuneLiteScrollBarUI;

@Singleton
public class LobsterPotPanel extends PluginPanel
{
	private static final Color MET_COLOR = new Color(0x4CAF50);
	private static final Color UNMET_COLOR = new Color(0xE53935);
	private static final Color ACCENT_COLOR = ColorScheme.BRAND_ORANGE;
	private static final Color HEADING_COLOR = Color.WHITE;
	private static final Color VALUE_COLOR = Color.WHITE;
	private static final Color KEY_COLOR = new Color(0x9E9E9E);
	/** Width (px) to wrap labels to; fits the 225px plugin panel minus our insets and the scrollbar. */
	private static final int WRAP_WIDTH = 150;
	/** Narrower wrap for text inside cards, which add their own horizontal padding. */
	private static final int CARD_WRAP_WIDTH = 142;
	private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.ENGLISH);
	private static final DateTimeFormatter DATE_FORMAT =
		DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

	private final LobsterPotApiClient apiClient;
	private final SessionManager session;
	private final LobsterPotConfig config;

	// Top-level views
	private final JPanel scrollContent = new ScrollableContentPanel();
	private final JPanel loginView = new JPanel();
	private final JPanel memberView = new JPanel();

	// Login view
	private final JTextField usernameField = new JTextField(14);
	private final JPasswordField passwordField = new JPasswordField(14);
	private final JButton loginButton = new JButton("Log in");
	private final JLabel loginStatus = new JLabel(" ");

	// Member header
	private final JLabel accountLabel = new JLabel();

	// Rank section
	private final JLabel rankStatus = new JLabel();
	private final JLabel currentRank = field();
	private final JLabel points = field();
	private final JLabel nextRank = field();
	private final JLabel eligibility = field();
	private final JPanel reasons = new JPanel();

	// Events / News
	private final JLabel eventsStatus = new JLabel();
	private final JPanel eventsList = new JPanel();
	private final JLabel newsStatus = new JLabel();
	private final JPanel newsList = new JPanel();

	// Rank-up
	private JPanel rankUpSection;
	private final JButton rankUpButton = new JButton("Request rank-up");
	private final JLabel rankUpStatus = new JLabel(" ");
	private boolean hasPendingClaim;

	@Inject
	public LobsterPotPanel(LobsterPotApiClient apiClient, SessionManager session, LobsterPotConfig config)
	{
		// Do not let PluginPanel wrap us in its own scroll pane; we provide our own (styled) one.
		super(false);
		this.apiClient = apiClient;
		this.session = session;
		this.config = config;
	}

	public void init()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		buildLoginView();
		buildMemberView();

		scrollContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Extra right padding so content clears the overlay scrollbar that floats over the viewport.
		scrollContent.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 14));

		final JScrollPane scrollPane = new JScrollPane(scrollContent,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUI(new RuneLiteScrollBarUI());
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);

		session.addListener(() -> SwingUtilities.invokeLater(this::renderSession));
		renderSession();
	}

	// ------------------------------------------------------------------ session/view switching

	private void renderSession()
	{
		scrollContent.removeAll();
		if (session.isLoggedIn())
		{
			accountLabel.setText("<html><div style='width:" + WRAP_WIDTH + "px'>Logged in as "
				+ escape(safe(session.getUsername())) + "</div></html>");
			scrollContent.add(memberView, BorderLayout.NORTH);
			refreshAll();
		}
		else
		{
			loginStatus.setText(" ");
			passwordField.setText("");
			loginButton.setEnabled(true);
			scrollContent.add(loginView, BorderLayout.NORTH);
		}
		scrollContent.revalidate();
		scrollContent.repaint();
	}

	// ------------------------------------------------------------------ login view

	private void buildLoginView()
	{
		loginView.setLayout(new BoxLayout(loginView, BoxLayout.Y_AXIS));
		loginView.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("The Lobster Pot");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		loginView.add(title);
		loginView.add(verticalGap(8));

		final JLabel hint = new JLabel("<html><body style='width:150px'>Log in with your clan website account.</body></html>");
		hint.setForeground(Color.LIGHT_GRAY);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		loginView.add(hint);
		loginView.add(verticalGap(8));

		loginView.add(leftLabel("Username"));
		usernameField.setAlignmentX(Component.LEFT_ALIGNMENT);
		usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, usernameField.getPreferredSize().height));
		loginView.add(usernameField);
		loginView.add(verticalGap(6));

		loginView.add(leftLabel("Password"));
		passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
		passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, passwordField.getPreferredSize().height));
		passwordField.addActionListener(e -> doLogin());
		loginView.add(passwordField);
		loginView.add(verticalGap(8));

		loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		loginButton.addActionListener(e -> doLogin());
		loginView.add(loginButton);
		loginView.add(verticalGap(4));

		loginStatus.setForeground(UNMET_COLOR);
		loginStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		loginView.add(loginStatus);
	}

	private void doLogin()
	{
		final String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
		final char[] pw = passwordField.getPassword();
		final String password = new String(pw);
		java.util.Arrays.fill(pw, '\0');

		if (username.isEmpty() || password.isEmpty())
		{
			setLoginStatus("Enter your username and password.", UNMET_COLOR);
			return;
		}
		if (!apiClient.isConfigured())
		{
			setLoginStatus("Clan API URL is not set in plugin settings.", UNMET_COLOR);
			return;
		}

		loginButton.setEnabled(false);
		setLoginStatus("Signing in…", Color.LIGHT_GRAY);
		apiClient.login(username, password, new ApiCallback<LoginResponse>()
		{
			@Override
			public void onSuccess(LoginResponse result)
			{
				if (result == null || result.getAccessToken() == null)
				{
					SwingUtilities.invokeLater(() ->
					{
						setLoginStatus("Login failed: empty response.", UNMET_COLOR);
						loginButton.setEnabled(true);
					});
					return;
				}
				// applyLogin fires the session listener, which switches to the member view on the EDT.
				session.applyLogin(result);
			}

			@Override
			public void onFailure(String error, int httpCode)
			{
				SwingUtilities.invokeLater(() ->
				{
					setLoginStatus(error, UNMET_COLOR);
					loginButton.setEnabled(true);
				});
			}
		});
	}

	// ------------------------------------------------------------------ member view

	private void buildMemberView()
	{
		memberView.setLayout(new BoxLayout(memberView, BoxLayout.Y_AXIS));
		memberView.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("The Lobster Pot");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		memberView.add(title);
		memberView.add(verticalGap(4));

		accountLabel.setForeground(VALUE_COLOR);
		accountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		memberView.add(accountLabel);
		memberView.add(verticalGap(6));

		final JButton refresh = new JButton("Refresh");
		refresh.setHorizontalAlignment(SwingConstants.CENTER);
		refresh.addActionListener(e -> refreshAll());
		final JButton logout = new JButton("Log out");
		logout.setHorizontalAlignment(SwingConstants.CENTER);
		logout.addActionListener(e -> session.clear());

		final JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		// Constrain height so the BoxLayout doesn't stretch the buttons tall.
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, refresh.getPreferredSize().height));
		buttons.add(refresh);
		buttons.add(logout);
		memberView.add(buttons);
		memberView.add(verticalGap(10));

		memberView.add(buildRankSection());
		memberView.add(verticalGap(10));
		memberView.add(buildRankUpSection());
		memberView.add(verticalGap(10));
		memberView.add(buildEventsSection());
		memberView.add(verticalGap(10));
		memberView.add(buildNewsSection());
	}

	private JPanel buildRankSection()
	{
		final JPanel section = section("Rank progress");
		rankStatus.setForeground(Color.LIGHT_GRAY);
		section.add(rankStatus);

		for (JLabel line : new JLabel[]{currentRank, points, nextRank, eligibility})
		{
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			section.add(line);
			section.add(verticalGap(2));
		}

		reasons.setLayout(new BoxLayout(reasons, BoxLayout.Y_AXIS));
		reasons.setOpaque(false);
		reasons.setAlignmentX(Component.LEFT_ALIGNMENT);
		reasons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		section.add(reasons);
		return section;
	}

	private JPanel buildRankUpSection()
	{
		final JPanel section = section("Request a rank-up");
		rankUpButton.addActionListener(e -> onRankUpClicked());
		rankUpButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(rankUpButton);
		section.add(verticalGap(4));
		rankUpStatus.setForeground(Color.LIGHT_GRAY);
		rankUpStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(rankUpStatus);
		// Hidden until a rank-up is actually available (see showRankAvailable).
		section.setVisible(false);
		rankUpSection = section;
		return section;
	}

	private JPanel buildEventsSection()
	{
		final JPanel section = section("Upcoming events");
		eventsStatus.setForeground(Color.LIGHT_GRAY);
		section.add(eventsStatus);
		eventsList.setLayout(new BoxLayout(eventsList, BoxLayout.Y_AXIS));
		eventsList.setOpaque(false);
		eventsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(eventsList);
		return section;
	}

	private JPanel buildNewsSection()
	{
		final JPanel section = section("News");
		newsStatus.setForeground(Color.LIGHT_GRAY);
		section.add(newsStatus);
		newsList.setLayout(new BoxLayout(newsList, BoxLayout.Y_AXIS));
		newsList.setOpaque(false);
		newsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(newsList);
		return section;
	}

	// ------------------------------------------------------------------ refresh

	public void refreshAll()
	{
		if (!session.isLoggedIn())
		{
			return;
		}
		refreshRank();
		refreshEvents();
		refreshNews();
	}

	private void refreshRank()
	{
		setRankMessage("Loading…");
		hasPendingClaim = false;
		apiClient.getProfile(new ApiCallback<Profile>()
		{
			@Override
			public void onSuccess(Profile profile)
			{
				SwingUtilities.invokeLater(() -> showProfile(profile));
				// Rank eligibility comes from a second endpoint.
				apiClient.getRankAvailable(new ApiCallback<RankAvailable>()
				{
					@Override
					public void onSuccess(RankAvailable rank)
					{
						SwingUtilities.invokeLater(() -> showRankAvailable(rank));
					}

					@Override
					public void onFailure(String error, int httpCode)
					{
						SwingUtilities.invokeLater(() -> nextRank.setText(error));
					}
				});
			}

			@Override
			public void onFailure(String error, int httpCode)
			{
				setRankMessage(error);
			}
		});
	}

	private void refreshEvents()
	{
		setEventsMessage("Loading…");
		apiClient.getUpcomingEvents(new ApiCallback<List<ClanEvent>>()
		{
			@Override
			public void onSuccess(List<ClanEvent> events)
			{
				SwingUtilities.invokeLater(() -> showEvents(events));
			}

			@Override
			public void onFailure(String error, int httpCode)
			{
				setEventsMessage(error);
			}
		});
	}

	private void refreshNews()
	{
		setNewsMessage("Loading…");
		apiClient.getNews(new ApiCallback<List<NewsPost>>()
		{
			@Override
			public void onSuccess(List<NewsPost> news)
			{
				SwingUtilities.invokeLater(() -> showNews(news));
			}

			@Override
			public void onFailure(String error, int httpCode)
			{
				setNewsMessage(error);
			}
		});
	}

	// ------------------------------------------------------------------ rendering

	private void showProfile(Profile p)
	{
		if (p == null)
		{
			setRankMessage("No profile returned.");
			return;
		}
		rankStatus.setText(" ");
		setInfo(currentRank, "Current rank:", p.getCurrentRank() != null ? p.getCurrentRank().getName() : "Unknown", VALUE_COLOR);
		setInfo(points, "Points:",
			NUMBER.format(p.getPointsAvailable()) + " available · " + NUMBER.format(p.getPointsTotal()) + " earned",
			VALUE_COLOR);
		revalidate();
		repaint();
	}

	private void showRankAvailable(RankAvailable rank)
	{
		reasons.removeAll();
		if (rank == null)
		{
			nextRank.setText("—");
			eligibility.setText("—");
			return;
		}

		final RankInfo next = rank.getNextRank();
		if (next == null)
		{
			setInfo(nextRank, "Next rank:", "Max rank reached", VALUE_COLOR);
		}
		else
		{
			final StringBuilder sb = new StringBuilder(next.getName());
			if (next.getPointCost() != null)
			{
				sb.append(" (").append(NUMBER.format(next.getPointCost())).append(" pts)");
			}
			setInfo(nextRank, "Next rank:", sb.toString(), VALUE_COLOR);

			if (next.getRequirementsDescription() != null && !next.getRequirementsDescription().isEmpty())
			{
				reasons.add(wrappedGray(next.getRequirementsDescription()));
				reasons.add(verticalGap(4));
			}
		}

		setInfo(eligibility, "Eligible:", rank.isEligible() ? "Yes" : "Not yet", rank.isEligible() ? MET_COLOR : UNMET_COLOR);

		final RankAvailable.Reasons r = rank.getReasons();
		if (r != null && next != null)
		{
			reasons.add(reasonRow("Enough points", r.isEnoughPoints()));
			reasons.add(reasonRow("Time in clan met", r.isEnoughTime()));
			if (r.isAdminOnly())
			{
				final JLabel admin = new JLabel("Next rank is assigned by staff only");
				admin.setForeground(UNMET_COLOR);
				reasons.add(admin);
			}
		}

		hasPendingClaim = rank.getPendingClaim() != null;
		final boolean canRequest = config.enableRankRequests() && rank.isEligible() && next != null && !hasPendingClaim;

		// Only show the rank-up request section when a rank-up is actually available to claim.
		rankUpSection.setVisible(canRequest);
		rankUpButton.setEnabled(canRequest);
		if (canRequest)
		{
			rankUpStatus.setText(" ");
		}

		if (hasPendingClaim)
		{
			rankStatus.setForeground(ACCENT_COLOR);
			rankStatus.setText("<html><div style='width:" + WRAP_WIDTH + "px'>Rank-up request pending review.</div></html>");
		}
		else
		{
			rankStatus.setForeground(Color.LIGHT_GRAY);
			rankStatus.setText(" ");
		}

		revalidate();
		repaint();
	}

	private void showEvents(List<ClanEvent> events)
	{
		eventsList.removeAll();
		if (events == null || events.isEmpty())
		{
			setEventsMessage("No upcoming events.");
			revalidate();
			repaint();
			return;
		}
		eventsStatus.setText(" ");
		for (ClanEvent event : events)
		{
			eventsList.add(eventRow(event));
			eventsList.add(verticalGap(6));
		}
		revalidate();
		repaint();
	}

	private void showNews(List<NewsPost> news)
	{
		newsList.removeAll();
		if (news == null || news.isEmpty())
		{
			setNewsMessage("No news yet.");
			revalidate();
			repaint();
			return;
		}
		newsStatus.setText(" ");
		for (NewsPost post : news)
		{
			newsList.add(newsRow(post));
			newsList.add(verticalGap(6));
		}
		revalidate();
		repaint();
	}

	private void onRankUpClicked()
	{
		if (!config.enableRankRequests() || hasPendingClaim)
		{
			return;
		}

		final JTextArea input = new JTextArea(5, 24);
		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		final int choice = JOptionPane.showConfirmDialog(this, new JScrollPane(input),
			"Describe why you're eligible (proof / justification):",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}

		final String claimText = input.getText() == null ? "" : input.getText().trim();
		if (claimText.isEmpty())
		{
			setRankUpStatus("Please include some justification.", UNMET_COLOR);
			return;
		}

		rankUpButton.setEnabled(false);
		setRankUpStatus("Submitting…", Color.LIGHT_GRAY);
		apiClient.submitRankClaim(claimText, new ApiCallback<RankClaimResponse>()
		{
			@Override
			public void onSuccess(RankClaimResponse response)
			{
				SwingUtilities.invokeLater(() ->
				{
					setRankUpStatus("Request submitted — staff will review it.", MET_COLOR);
					refreshRank();
				});
			}

			@Override
			public void onFailure(String error, int httpCode)
			{
				SwingUtilities.invokeLater(() ->
				{
					setRankUpStatus(error, UNMET_COLOR);
					rankUpButton.setEnabled(config.enableRankRequests() && !hasPendingClaim);
				});
			}
		});
	}

	// ------------------------------------------------------------------ row builders

	private JPanel reasonRow(String label, boolean met)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		final JLabel mark = new JLabel(met ? "✓" : "✗");
		mark.setForeground(met ? MET_COLOR : UNMET_COLOR);
		final JLabel text = new JLabel("<html><div style='width:" + (WRAP_WIDTH - 18) + "px'>" + escape(label) + "</div></html>");
		text.setForeground(Color.LIGHT_GRAY);
		row.add(mark, BorderLayout.WEST);
		row.add(text, BorderLayout.CENTER);
		return row;
	}

	private JPanel eventRow(ClanEvent event)
	{
		final JPanel row = card();
		row.add(boldLine(event.getTitle()));
		row.add(grayLine(formatWhen(event.getEventStart())));
		if (event.getLocation() != null && !event.getLocation().isEmpty())
		{
			row.add(grayLine("@ " + event.getLocation()));
		}
		return row;
	}

	private JPanel newsRow(NewsPost post)
	{
		final JPanel row = card();
		row.add(boldLine(post.getTitle()));
		row.add(grayLine(formatWhen(post.getCreatedAt())));
		final String summary = post.getExcerpt() != null && !post.getExcerpt().isEmpty()
			? post.getExcerpt() : post.getContent();
		if (summary != null && !summary.isEmpty())
		{
			final JLabel body = new JLabel("<html><div style='width:" + CARD_WRAP_WIDTH + "px'>" + escape(summary) + "</div></html>");
			body.setForeground(Color.LIGHT_GRAY);
			body.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(body);
			row.add(verticalGap(2));
		}
		return row;
	}

	// ------------------------------------------------------------------ small helpers

	private JPanel card()
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
		final JLabel label = new JLabel("<html><div style='width:" + CARD_WRAP_WIDTH + "px'>" + escape(text) + "</div></html>");
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(HEADING_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel grayLine(String text)
	{
		final JLabel label = new JLabel("<html><div style='width:" + CARD_WRAP_WIDTH + "px'>" + escape(text) + "</div></html>");
		label.setForeground(KEY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return label;
	}

	private static String formatWhen(String iso)
	{
		if (iso == null || iso.isEmpty())
		{
			return "Date TBC";
		}
		try
		{
			return DATE_FORMAT.format(OffsetDateTime.parse(iso));
		}
		catch (Exception ignored)
		{
			try
			{
				return DATE_FORMAT.format(Instant.parse(iso));
			}
			catch (Exception alsoIgnored)
			{
				return iso;
			}
		}
	}

	private JPanel section(String heading)
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

	/** Renders a wrapping "key value" line with a muted key and a coloured value. */
	private static void setInfo(JLabel label, String key, String value, Color valueColor)
	{
		label.setText("<html><div style='width:" + WRAP_WIDTH + "px'>"
			+ "<span style='color:" + hex(KEY_COLOR) + "'>" + escape(key) + " </span>"
			+ "<span style='color:" + hex(valueColor) + "'>" + escape(value) + "</span></div></html>");
	}

	private static JLabel wrappedGray(String text)
	{
		final JLabel label = new JLabel("<html><div style='width:" + WRAP_WIDTH + "px'>" + escape(text) + "</div></html>");
		label.setForeground(KEY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static String hex(Color color)
	{
		return String.format("#%06x", color.getRGB() & 0xFFFFFF);
	}

	private static JLabel leftLabel(String text)
	{
		final JLabel label = new JLabel(text);
		label.setForeground(Color.GRAY);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel field()
	{
		final JLabel label = new JLabel("—");
		label.setForeground(Color.WHITE);
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

	private void setRankMessage(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			rankStatus.setForeground(Color.LIGHT_GRAY);
			rankStatus.setText("<html><body style='width:150px'>" + escape(message) + "</body></html>");
			currentRank.setText("—");
			points.setText("—");
			nextRank.setText("—");
			eligibility.setText("—");
			eligibility.setForeground(Color.WHITE);
			reasons.removeAll();
			if (rankUpSection != null)
			{
				rankUpSection.setVisible(false);
			}
			revalidate();
			repaint();
		});
	}

	private void setEventsMessage(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			eventsList.removeAll();
			eventsStatus.setText("<html><body style='width:150px'>" + escape(message) + "</body></html>");
			revalidate();
			repaint();
		});
	}

	private void setNewsMessage(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			newsList.removeAll();
			newsStatus.setText("<html><body style='width:150px'>" + escape(message) + "</body></html>");
			revalidate();
			repaint();
		});
	}

	private void setLoginStatus(String message, Color color)
	{
		loginStatus.setForeground(color);
		loginStatus.setText("<html><body style='width:150px'>" + escape(message) + "</body></html>");
	}

	private void setRankUpStatus(String message, Color color)
	{
		rankUpStatus.setForeground(color);
		rankUpStatus.setText("<html><body style='width:150px'>" + escape(message) + "</body></html>");
	}

	private static String safe(String s)
	{
		return s == null ? "" : s;
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * Content panel that always matches the scroll viewport's width, so nothing overflows
	 * horizontally (which would push the right padding under the scrollbar and clip content).
	 */
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
