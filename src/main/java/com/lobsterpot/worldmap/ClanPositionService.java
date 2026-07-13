package com.lobsterpot.worldmap;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.AnimationID;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Player;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.Text;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.WorldResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@Slf4j
@Singleton
public class ClanPositionService
{
	static final String BACKEND_URL = com.lobsterpot.Backend.URL;

	// Evaluate sharing every ~9.6s (16 game ticks) rather than every tick, to keep backend request
	// volume down. A clan map marker that updates ~every 10s is indistinguishable in practice.
	private static final int TICKS_PER_UPDATE = 16;
	// When standing still, resend at this interval so the position doesn't expire on the backend
	// (server TTL is 90s). Movement/world/activity changes are sent as soon as they happen.
	private static final long POSITION_HEARTBEAT_MS = 45_000L;
	private static final int MAX_HOP_ATTEMPTS = 3;
	private static final int MAX_POSITION_DISTANCE = 64_000;
	private static final int MAX_ACTIVITY_LENGTH = 80;
	private static final int MAP_MARKER_HOVER_PADDING = 6;
	private static final int FINDER_BUTTON_SIZE = 28;
	private static final int FINDER_MARGIN = 12;
	private static final int FINDER_PANEL_GAP = 6;
	private static final int FINDER_PANEL_PADDING = 6;
	private static final int FINDER_HEADER_HEIGHT = 20;
	private static final int FINDER_ROW_HEIGHT = 18;
	private static final int FINDER_COLUMN_WIDTH = 142;
	private static final Color FINDER_BACKGROUND = new Color(25, 25, 25, 225);
	private static final Color FINDER_BUTTON_BACKGROUND = new Color(38, 38, 38, 230);
	private static final Color FINDER_BUTTON_ACTIVE_BACKGROUND = new Color(69, 54, 22, 235);
	private static final Color FINDER_HOVER_BACKGROUND = new Color(78, 78, 78, 210);
	private static final Color FINDER_BORDER = new Color(94, 94, 94, 230);
	private static final Color FINDER_TEXT = new Color(238, 238, 238);
	private static final Color FINDER_MUTED_TEXT = new Color(174, 174, 174);
	private static final Color FINDER_ACCENT = new Color(255, 184, 47);
	private static final long SOCKET_RECONNECT_DELAY_MS = 10_000L;
	// World map "map list" dropdown at the bottom of the world map (gameval component ids).
	private static final int MAPLIST_LIST = net.runelite.api.gameval.InterfaceID.Worldmap.MAPLIST_LIST;
	// The default overworld map layer, used as a fallback when a target can't be located.
	private static final String SURFACE_MAP_NAME = "Gielinor Surface";
	// Op index for a map list entry's "Select" action (action 0 -> op 1). The entries carry an
	// onOp listener even while the dropdown is collapsed, so we can select a map without opening it.
	private static final int MAPLIST_SELECT_OP = 1;
	// The layer search runs on client ticks (~20ms) rather than game ticks (~600ms) so it is fast.
	// Client ticks to wait for a selected map to finish loading before testing the next entry.
	private static final int LAYER_FOCUS_MAX_WAIT_TICKS = 2;
	// Hard cap (client ticks) on how long the whole layer search may run, as a safety net.
	private static final int LAYER_FOCUS_MAX_TOTAL_TICKS = 800;
	// Max candidate layers (nearest map center first) to try before concluding the target isn't on
	// the map - almost always they're inside an instance. A real dungeon resolves in the first one
	// or two, so this avoids flickering through every layer for an unmappable target.
	private static final int LAYER_FOCUS_MAX_CANDIDATES = 6;
	private static final String CONFIG_GROUP = "lobsterpot";
	private static final String LAYER_CACHE_KEY = "worldMapLayerCache";
	private static final Color LAYER_COVER_BACKGROUND = new Color(18, 18, 18, 236);
	private static final int CONFIRM_WIDTH = 264;
	private static final int CONFIRM_HEIGHT = 92;
	private static final int CONFIRM_BUTTON_HEIGHT = 24;
	private static final int CONFIRM_PADDING = 10;
	private static final Map<Integer, String> ACTIVITY_BY_ANIMATION = buildActivityByAnimation();

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private WorldService worldService;

	@Inject
	private ConfigManager configManager;

	private volatile boolean active = false;
	private volatile WebSocket positionSocket;
	private volatile boolean socketConnecting = false;
	private volatile String socketPlayerKey = "";
	private long lastSocketAttemptAt = 0L;
	private volatile boolean sharedPositionOnSocket = false;
	// Last position actually sent, so we only resend when it changes (plus a periodic heartbeat).
	private int lastSentX = Integer.MIN_VALUE;
	private int lastSentY;
	private int lastSentPlane;
	private int lastSentWorld;
	private String lastSentActivity = "";
	private long lastSentAtMs;
	private int tickCounter = 0;
	private int displaySwitcherAttempts = 0;
	private World quickHopTargetWorld;
	private boolean finderExpanded = false;
	private final Map<String, ClanMemberWorldMapPoint> trackedPoints = new HashMap<>();

	// Layer focus: when a clanmate is on a map that isn't the currently displayed one, we walk
	// the world map's bottom "map list" dropdown, load each entry, and stop on the one that
	// contains the target. Null phase means no search is in progress.
	private LayerFocusPhase layerFocusPhase;
	private WorldPoint layerFocusTarget;
	private String layerFocusMemberName;
	private int layerFocusRegion;
	private List<String> layerFocusCandidates = new ArrayList<>();
	private String layerFocusCurrentEntryName;
	private int layerFocusIndex;
	private int layerFocusEntryTotal;
	private int layerFocusWaitTicks;
	private int layerFocusTotalTicks;
	private boolean layerFocusScanLogged;
	// Confirmation prompt shown before a world hop, so an accidental marker click doesn't hop.
	private ClanMemberWorldMapPoint pendingHopPoint;
	// Persisted cache: world map region id -> map list entry name that contains it. Lets a
	// previously resolved layer become a single clean switch instead of a full scan.
	private final Map<Integer, String> layerMapNameByRegion = new HashMap<>();

	private enum LayerFocusPhase
	{
		LOAD,
		WAIT
	}

	public void start()
	{
		active = true;
		tickCounter = 0;
		sharedPositionOnSocket = false;
		loadLayerCache();
	}

	public void stop()
	{
		active = false;
		finderExpanded = false;
		cancelLayerFocus();
		clearHopPrompt();
		disconnectPositionSocket();
		resetQuickHopper();
		clientThread.invokeLater(this::clearPoints);
	}

	public void onTick(ClanAccess access, boolean shareLocation)
	{
		if (!active)
		{
			return;
		}

		continueWorldHop();

		if (++tickCounter < TICKS_PER_UPDATE)
		{
			return;
		}
		tickCounter = 0;

		if (access == null || !access.isAllowed())
		{
			disconnectPositionSocket();
			clearPoints();
			return;
		}

		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || localPlayer.getName() == null)
		{
			disconnectPositionSocket();
			clearPoints();
			return;
		}

		final String rsn = localPlayer.getName();
		ensurePositionSocket(rsn);

		final WorldPoint wp = localPlayer.getWorldLocation();
		if (wp == null)
		{
			return;
		}

		if (!shareLocation)
		{
			clearSharedPosition();
			return;
		}

		final int world = client.getWorld();
		final String activity = currentPlayerActivity(localPlayer, world);
		if (shouldSendPosition(wp, world, activity))
		{
			sendPosition(rsn, wp, world, activity);
		}
	}

	// Only send when the position, world, or activity has changed since the last send, or when the
	// heartbeat interval has elapsed. This avoids a steady stream of identical updates while idle.
	private boolean shouldSendPosition(WorldPoint wp, int world, String activity)
	{
		if (!sharedPositionOnSocket
			|| wp.getX() != lastSentX
			|| wp.getY() != lastSentY
			|| wp.getPlane() != lastSentPlane
			|| world != lastSentWorld
			|| !sameText(activity, lastSentActivity))
		{
			return true;
		}
		return System.currentTimeMillis() - lastSentAtMs >= POSITION_HEARTBEAT_MS;
	}

	// Driven from the client tick (not the game tick) so the layer scan is fast.
	public void onClientTick()
	{
		if (active)
		{
			continueLayerFocus();
		}
	}

	public void addHopMenuEntry(MenuEntryAdded event)
	{
		if (!active || event == null || !"Focus on".equals(event.getOption()))
		{
			return;
		}

		rewriteHopMenuEntry(event.getMenuEntry());
	}

	public void rewriteHopMenuEntries()
	{
		if (!active)
		{
			return;
		}

		final MenuEntry[] entries = client.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}

		final List<MenuEntry> keptEntries = new ArrayList<>(entries.length);
		boolean changed = false;
		for (MenuEntry entry : entries)
		{
			final boolean focusEntry = entry != null && "Focus on".equals(entry.getOption());
			final MenuEntry rewrittenEntry = rewriteHopMenuEntry(entry);
			if (rewrittenEntry == null)
			{
				changed = true;
				continue;
			}
			if (focusEntry && !"Focus on".equals(rewrittenEntry.getOption()))
			{
				changed = true;
			}
			keptEntries.add(rewrittenEntry);
		}

		if (changed)
		{
			client.setMenuEntries(keptEntries.toArray(new MenuEntry[0]));
		}
	}

	private MenuEntry rewriteHopMenuEntry(MenuEntry entry)
	{
		if (entry == null || !"Focus on".equals(entry.getOption()))
		{
			return entry;
		}

		final String target = Text.removeTags(entry.getTarget());
		final ClanMemberWorldMapPoint point = trackedPoints.get(playerKey(target));
		if (point == null)
		{
			return entry;
		}

		if (point.getWorld() <= 0 || point.getWorld() == client.getWorld())
		{
			return null;
		}

		return entry
			.setOption("Hop-to")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.setForceLeftClick(true)
			.onClick(menuEntry -> hopTo(point));
	}

	public void addHoveredMapMenuEntry()
	{
		if (!active || client.isMenuOpen())
		{
			return;
		}

		final Point mouse = client.getMouseCanvasPosition();

		// While the hop confirmation is showing only its buttons are interactive, and while a layer
		// scan is running the map isn't clickable at all.
		if (pendingHopPoint != null)
		{
			addHopConfirmMenuEntries(mouse);
			return;
		}
		if (layerFocusPhase != null)
		{
			return;
		}

		if (isMouseOverFinder(mouse))
		{
			addFinderMenuEntries();
			return;
		}

		final ClanMemberWorldMapPoint point = hoveredPoint();
		if (point == null || point.getWorld() <= 0 || point.getWorld() == client.getWorld() || hasHopMenuEntry(point))
		{
			return;
		}

		client.createMenuEntry(-1)
			.setOption("Hop-to")
			.setTarget(point.getMemberName())
			.setType(MenuAction.RUNELITE)
			.setForceLeftClick(true)
			.onClick(entry -> hopTo(point));
	}

	public void renderMapFinder(Graphics2D graphics)
	{
		if (!active || graphics == null)
		{
			return;
		}

		final Rectangle mapBounds = worldMapViewBounds();
		if (mapBounds == null)
		{
			finderExpanded = false;
			return;
		}

		final List<ClanMemberWorldMapPoint> points = sortedFinderPoints();
		final Rectangle buttonBounds = finderButtonBounds(mapBounds);
		if (finderExpanded)
		{
			drawFinderPanel(graphics, mapBounds, points);
		}
		drawFinderButton(graphics, buttonBounds);
	}

	// Hides the rapid map switching during a layer search behind a simple loading cover so the
	// user sees a brief "Locating..." panel instead of the world map flickering through layers.
	public void renderLayerFocusCover(Graphics2D graphics)
	{
		if (!active || layerFocusPhase == null || graphics == null)
		{
			return;
		}

		final Rectangle bounds = worldMapViewBounds();
		if (bounds == null)
		{
			return;
		}

		graphics.setColor(LAYER_COVER_BACKGROUND);
		graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

		final FontMetrics metrics = graphics.getFontMetrics();
		final String name = hasText(layerFocusMemberName) ? layerFocusMemberName : "clanmate";
		final String title = "Locating " + name + "…";
		graphics.setColor(FINDER_ACCENT);
		graphics.drawString(title, bounds.x + (bounds.width - metrics.stringWidth(title)) / 2,
			bounds.y + bounds.height / 2);

		if (layerFocusScanLogged && layerFocusEntryTotal > 0)
		{
			final String progress = "Searching map layers (" + Math.min(layerFocusIndex, layerFocusEntryTotal)
				+ "/" + layerFocusEntryTotal + ")";
			graphics.setColor(FINDER_MUTED_TEXT);
			graphics.drawString(progress, bounds.x + (bounds.width - metrics.stringWidth(progress)) / 2,
				bounds.y + bounds.height / 2 + metrics.getHeight());
		}
	}

	// Prompt shown before hopping worlds, so an accidental marker click can't world-hop you.
	public void renderHopConfirmPrompt(Graphics2D graphics)
	{
		if (!active || graphics == null || pendingHopPoint == null || layerFocusPhase != null)
		{
			return;
		}

		final Rectangle mapBounds = worldMapViewBounds();
		if (mapBounds == null)
		{
			clearHopPrompt();
			return;
		}

		final String name = hasText(pendingHopPoint.getMemberName()) ? pendingHopPoint.getMemberName() : "Clanmate";
		drawConfirmPanel(graphics, confirmPanelBounds(mapBounds),
			"Hop to " + name + "?", "Quick-hop to World " + pendingHopPoint.getWorld() + "?", "Hop");
	}

	// Shared drawing for the two confirmation prompts: a centered panel with two text lines and a
	// left "confirm" button (labelled per caller) plus a right "Cancel" button.
	private void drawConfirmPanel(Graphics2D graphics, Rectangle panel, String line1, String line2, String confirmLabel)
	{
		graphics.setColor(FINDER_BACKGROUND);
		graphics.fillRoundRect(panel.x, panel.y, panel.width, panel.height, 8, 8);
		graphics.setColor(FINDER_BORDER);
		graphics.drawRoundRect(panel.x, panel.y, panel.width, panel.height, 8, 8);

		final FontMetrics metrics = graphics.getFontMetrics();
		graphics.setColor(FINDER_TEXT);
		graphics.drawString(fitText(line1, metrics, panel.width - CONFIRM_PADDING * 2),
			panel.x + CONFIRM_PADDING, panel.y + CONFIRM_PADDING + metrics.getAscent());
		graphics.setColor(FINDER_MUTED_TEXT);
		graphics.drawString(fitText(line2, metrics, panel.width - CONFIRM_PADDING * 2),
			panel.x + CONFIRM_PADDING, panel.y + CONFIRM_PADDING + metrics.getHeight() + metrics.getAscent());

		final Point mouse = client.getMouseCanvasPosition();
		drawConfirmButton(graphics, confirmSearchBounds(panel), confirmLabel, mouse, true);
		drawConfirmButton(graphics, confirmCancelBounds(panel), "Cancel", mouse, false);
	}

	private void drawConfirmButton(Graphics2D graphics, Rectangle bounds, String label, Point mouse, boolean accent)
	{
		final boolean hovered = contains(bounds, mouse);
		graphics.setColor(hovered ? FINDER_HOVER_BACKGROUND : FINDER_BUTTON_BACKGROUND);
		graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
		graphics.setColor(accent ? FINDER_ACCENT : FINDER_BORDER);
		graphics.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);

		final FontMetrics metrics = graphics.getFontMetrics();
		graphics.setColor(accent ? FINDER_ACCENT : FINDER_TEXT);
		graphics.drawString(label, bounds.x + (bounds.width - metrics.stringWidth(label)) / 2,
			bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent());
	}

	private void addHopConfirmMenuEntries(Point mouse)
	{
		if (pendingHopPoint == null)
		{
			return;
		}

		final Rectangle mapBounds = worldMapViewBounds();
		if (mapBounds == null)
		{
			return;
		}

		final Rectangle panel = confirmPanelBounds(mapBounds);
		if (contains(confirmSearchBounds(panel), mouse))
		{
			client.createMenuEntry(-1)
				.setOption("Hop")
				.setTarget(hasText(pendingHopPoint.getMemberName()) ? pendingHopPoint.getMemberName() : "")
				.setType(MenuAction.RUNELITE)
				.setForceLeftClick(true)
				.onClick(entry -> confirmHop());
		}
		else if (contains(confirmCancelBounds(panel), mouse))
		{
			client.createMenuEntry(-1)
				.setOption("Cancel")
				.setTarget("")
				.setType(MenuAction.RUNELITE)
				.setForceLeftClick(true)
				.onClick(entry -> clearHopPrompt());
		}
	}

	private Rectangle confirmPanelBounds(Rectangle mapBounds)
	{
		final int width = Math.min(CONFIRM_WIDTH, mapBounds.width - 20);
		final int x = mapBounds.x + (mapBounds.width - width) / 2;
		final int y = mapBounds.y + (mapBounds.height - CONFIRM_HEIGHT) / 2;
		return new Rectangle(x, y, width, CONFIRM_HEIGHT);
	}

	private static Rectangle confirmSearchBounds(Rectangle panel)
	{
		final int width = (panel.width - CONFIRM_PADDING * 3) / 2;
		return new Rectangle(panel.x + CONFIRM_PADDING,
			panel.y + panel.height - CONFIRM_BUTTON_HEIGHT - CONFIRM_PADDING, width, CONFIRM_BUTTON_HEIGHT);
	}

	private static Rectangle confirmCancelBounds(Rectangle panel)
	{
		final int width = (panel.width - CONFIRM_PADDING * 3) / 2;
		return new Rectangle(panel.x + panel.width - CONFIRM_PADDING - width,
			panel.y + panel.height - CONFIRM_BUTTON_HEIGHT - CONFIRM_PADDING, width, CONFIRM_BUTTON_HEIGHT);
	}

	public void clearPoints()
	{
		for (ClanMemberWorldMapPoint point : trackedPoints.values())
		{
			worldMapPointManager.remove(point);
		}
		trackedPoints.clear();
		finderExpanded = false;
		clearHopPrompt();
		cancelLayerFocus();
	}

	private void addFinderMenuEntries(Rectangle mapBounds, Rectangle buttonBounds, List<ClanMemberWorldMapPoint> points)
	{
		if (client.isMenuOpen())
		{
			return;
		}

		final Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return;
		}

		if (contains(buttonBounds, mouse))
		{
			client.createMenuEntry(-1)
				.setOption(finderExpanded ? "Close" : "Open")
				.setTarget("clan map list")
				.setType(MenuAction.RUNELITE)
				.setForceLeftClick(true)
				.onClick(entry -> finderExpanded = !finderExpanded);
			return;
		}

		if (!finderExpanded || points.isEmpty())
		{
			return;
		}

		final FinderLayout layout = finderLayout(mapBounds, points.size());
		final ClanMemberWorldMapPoint point = finderPointAt(mouse, layout, points);
		if (point == null)
		{
			return;
		}

		client.createMenuEntry(-1)
			.setOption("Focus")
			.setTarget(point.getMemberName())
			.setType(MenuAction.RUNELITE)
			.setForceLeftClick(true)
			.onClick(entry -> focusMapOn(point));
	}

	private void addFinderMenuEntries()
	{
		final Rectangle mapBounds = worldMapViewBounds();
		if (mapBounds == null)
		{
			return;
		}

		addFinderMenuEntries(mapBounds, finderButtonBounds(mapBounds), sortedFinderPoints());
	}

	private void drawFinderButton(Graphics2D graphics, Rectangle bounds)
	{
		graphics.setColor(finderExpanded ? FINDER_BUTTON_ACTIVE_BACKGROUND : FINDER_BUTTON_BACKGROUND);
		graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
		graphics.setColor(finderExpanded ? FINDER_ACCENT : FINDER_BORDER);
		graphics.setStroke(new BasicStroke(1f));
		graphics.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);

		final int lineX = bounds.x + 8;
		final int lineWidth = bounds.width - 16;
		graphics.setColor(FINDER_TEXT);
		for (int i = 0; i < 3; i++)
		{
			final int y = bounds.y + 9 + i * 5;
			graphics.drawLine(lineX, y, lineX + lineWidth, y);
		}
	}

	private void drawFinderPanel(Graphics2D graphics, Rectangle mapBounds, List<ClanMemberWorldMapPoint> points)
	{
		final FinderLayout layout = finderLayout(mapBounds, Math.max(1, points.size()));
		final Rectangle panel = layout.panelBounds;
		final Point mouse = client.getMouseCanvasPosition();

		graphics.setColor(FINDER_BACKGROUND);
		graphics.fillRoundRect(panel.x, panel.y, panel.width, panel.height, 6, 6);
		graphics.setColor(FINDER_BORDER);
		graphics.drawRoundRect(panel.x, panel.y, panel.width, panel.height, 6, 6);

		final FontMetrics metrics = graphics.getFontMetrics();
		final String title = "Clanmates (" + points.size() + ")";
		final int titleY = panel.y + FINDER_PANEL_PADDING + (FINDER_HEADER_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.setColor(FINDER_ACCENT);
		graphics.drawString(fitText(title, metrics, panel.width - FINDER_PANEL_PADDING * 2), panel.x + FINDER_PANEL_PADDING, titleY);
		graphics.setColor(FINDER_BORDER);
		graphics.drawLine(panel.x + FINDER_PANEL_PADDING, panel.y + FINDER_PANEL_PADDING + FINDER_HEADER_HEIGHT - 1,
			panel.x + panel.width - FINDER_PANEL_PADDING, panel.y + FINDER_PANEL_PADDING + FINDER_HEADER_HEIGHT - 1);

		if (points.isEmpty())
		{
			final Rectangle row = finderRowBounds(layout, 0);
			drawFinderText(graphics, "No clanmates", "", row, false);
			return;
		}

		for (int i = 0; i < points.size(); i++)
		{
			final ClanMemberWorldMapPoint point = points.get(i);
			final Rectangle row = finderRowBounds(layout, i);
			final boolean hovered = mouse != null && contains(row, mouse);
			drawFinderText(graphics, point.getMemberName(), finderLocationSuffix(point), row, hovered);
		}
	}

	private void drawFinderText(Graphics2D graphics, String name, String worldText, Rectangle row, boolean hovered)
	{
		if (hovered)
		{
			graphics.setColor(FINDER_HOVER_BACKGROUND);
			graphics.fillRect(row.x, row.y, row.width, row.height);
		}

		final FontMetrics metrics = graphics.getFontMetrics();
		final int textY = row.y + (row.height - metrics.getHeight()) / 2 + metrics.getAscent();
		final int worldWidth = hasText(worldText) ? metrics.stringWidth(worldText) + 8 : 0;
		graphics.setColor(FINDER_TEXT);
		graphics.drawString(fitText(name, metrics, row.width - worldWidth - 6), row.x + 4, textY);
		if (hasText(worldText))
		{
			graphics.setColor(FINDER_MUTED_TEXT);
			graphics.drawString(worldText, row.x + row.width - metrics.stringWidth(worldText) - 4, textY);
		}
	}

	private String finderLocationSuffix(ClanMemberWorldMapPoint point)
	{
		final StringBuilder suffix = new StringBuilder();
		if (point.getWorld() > 0)
		{
			suffix.append("W").append(point.getWorld());
		}

		final WorldPoint worldPoint = point.getWorldPoint();
		if (worldPoint != null && worldPoint.getPlane() > 0)
		{
			if (suffix.length() > 0)
			{
				suffix.append(" ");
			}
			suffix.append("P").append(worldPoint.getPlane());
		}
		return suffix.toString();
	}

	private void focusMapOn(ClanMemberWorldMapPoint point)
	{
		if (point == null || point.getWorldPoint() == null)
		{
			return;
		}

		final WorldPoint target = point.getWorldPoint();
		final String memberName = point.getMemberName();
		clientThread.invoke(() ->
		{
			final WorldMap worldMap = client.getWorldMap();
			if (worldMap == null)
			{
				return;
			}

			if (currentMapContains(worldMap, target))
			{
				cancelLayerFocus();
				worldMap.setWorldMapPositionTarget(target);
				return;
			}

			// The layer is resolved from the region cache or the nearest-center table, so this is a
			// single clean switch; if the guess is wrong beginLayerFocus falls back to a full scan.
			cancelLayerFocus();
			beginLayerFocus(target, memberName);
		});
	}

	private void beginLayerFocus(WorldPoint target, String memberName)
	{
		layerFocusPhase = LayerFocusPhase.LOAD;
		layerFocusTarget = target;
		layerFocusMemberName = memberName;
		layerFocusRegion = target.getRegionID();
		layerFocusCandidates = buildLayerCandidates(target);
		layerFocusCurrentEntryName = null;
		layerFocusIndex = 0;
		layerFocusEntryTotal = layerFocusCandidates.size();
		layerFocusWaitTicks = 0;
		layerFocusTotalTicks = 0;
		layerFocusScanLogged = false;
	}

	// The layers worth trying for this target, nearest map center first and capped, so an unmappable
	// target (e.g. a clanmate inside an instance) fails fast instead of flickering through every
	// layer. A cached layer for the region, if any, is tried first as an authoritative single switch.
	private List<String> buildLayerCandidates(WorldPoint target)
	{
		final List<String> candidates = new ArrayList<>();
		final String cached = layerMapNameByRegion.get(target.getRegionID());
		if (hasText(cached))
		{
			candidates.add(cached);
		}
		for (String name : WorldMapLayers.nearestLayerNames(target.getX(), target.getY(), LAYER_FOCUS_MAX_CANDIDATES))
		{
			if (!candidates.contains(name))
			{
				candidates.add(name);
			}
		}
		return candidates;
	}

	private void cancelLayerFocus()
	{
		layerFocusPhase = null;
		layerFocusTarget = null;
		layerFocusMemberName = null;
	}

	// Runs each game tick while a layer search is in progress. Opens the world map "map list"
	// dropdown, loads each entry in turn, and focuses the target once its map is displayed.
	private void continueLayerFocus()
	{
		if (layerFocusPhase == null)
		{
			return;
		}

		if (++layerFocusTotalTicks > LAYER_FOCUS_MAX_TOTAL_TICKS)
		{
			log.debug("[LobsterPot] layer focus timed out for {}", layerFocusMemberName);
			abortLayerFocus();
			return;
		}

		final WorldMap worldMap = client.getWorldMap();
		if (worldMap == null || worldMapViewBounds() == null)
		{
			// World map was closed; give up silently.
			cancelLayerFocus();
			return;
		}

		// Whatever map is loaded now, if it contains the target we are done.
		if (currentMapContains(worldMap, layerFocusTarget))
		{
			worldMap.setWorldMapPositionTarget(layerFocusTarget);
			rememberLayer(layerFocusRegion, layerFocusCurrentEntryName);
			log.debug("[LobsterPot] focused {} on map layer '{}'", layerFocusMemberName, layerFocusCurrentEntryName);
			cancelLayerFocus();
			return;
		}

		if (layerFocusPhase == LayerFocusPhase.WAIT)
		{
			if (++layerFocusWaitTicks >= LAYER_FOCUS_MAX_WAIT_TICKS)
			{
				layerFocusPhase = LayerFocusPhase.LOAD;
			}
			return;
		}

		loadNextLayerEntry();
	}

	private void loadNextLayerEntry()
	{
		// The map list entries live under MAPLIST_LIST and keep their "Select" onOp listeners even
		// while the dropdown is collapsed, so we can load each map without opening the dropdown.
		final Widget list = client.getWidget(MAPLIST_LIST);
		final Widget[] entries = list == null ? new Widget[0] : mapListEntries(list);
		if (entries.length == 0)
		{
			// List not available yet; keep waiting (bounded by the total tick budget).
			return;
		}

		if (!layerFocusScanLogged)
		{
			log.debug("[LobsterPot] trying {} candidate map layers for {}", layerFocusCandidates.size(), layerFocusMemberName);
			layerFocusScanLogged = true;
		}

		// Try each candidate layer (nearest map center first) until one contains the target. If the
		// short candidate list is exhausted, the target isn't on any nearby map - almost certainly
		// they're inside an instance - so give up rather than flickering through every layer.
		while (layerFocusIndex < layerFocusCandidates.size())
		{
			final String name = layerFocusCandidates.get(layerFocusIndex);
			layerFocusIndex++;
			final Widget entry = findEntryByName(entries, name);
			if (entry == null)
			{
				// This map isn't in the live list (e.g. name drift); skip to the next candidate.
				continue;
			}
			layerFocusCurrentEntryName = name;
			layerFocusWaitTicks = 0;
			if (invokeWidgetOp(entry))
			{
				layerFocusPhase = LayerFocusPhase.WAIT;
			}
			return;
		}

		log.debug("[LobsterPot] {} not found on any nearby map layer", layerFocusMemberName);
		abortLayerFocus();
	}

	private static Widget findEntryByName(Widget[] entries, String name)
	{
		for (Widget entry : entries)
		{
			if (entry != null && name.equals(entry.getText()))
			{
				return entry;
			}
		}
		return null;
	}

	private void rememberLayer(int region, String entryName)
	{
		if (!hasText(entryName) || entryName.equals(layerMapNameByRegion.get(region)))
		{
			return;
		}
		layerMapNameByRegion.put(region, entryName);
		saveLayerCache();
	}

	private void loadLayerCache()
	{
		layerMapNameByRegion.clear();
		final String json = configManager.getConfiguration(CONFIG_GROUP, LAYER_CACHE_KEY);
		if (!hasText(json))
		{
			return;
		}

		try
		{
			final Map<String, String> stored = gson.fromJson(json,
				new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
			if (stored == null)
			{
				return;
			}
			for (Map.Entry<String, String> entry : stored.entrySet())
			{
				if (!hasText(entry.getValue()))
				{
					continue;
				}
				try
				{
					layerMapNameByRegion.put(Integer.parseInt(entry.getKey()), entry.getValue());
				}
				catch (NumberFormatException ignored)
				{
					// Skip malformed keys.
				}
			}
		}
		catch (Exception ex)
		{
			log.debug("[LobsterPot] could not load world map layer cache", ex);
		}
	}

	private void saveLayerCache()
	{
		final Map<String, String> stored = new HashMap<>();
		for (Map.Entry<Integer, String> entry : layerMapNameByRegion.entrySet())
		{
			stored.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		configManager.setConfiguration(CONFIG_GROUP, LAYER_CACHE_KEY, gson.toJson(stored));
	}

	private void abortLayerFocus()
	{
		final String memberName = layerFocusMemberName;
		cancelLayerFocus();
		// Leave the user on the main surface map rather than stranded on whatever layer was last
		// tried during the (failed) search.
		switchToSurfaceMap();
		if (hasText(memberName))
		{
			client.addChatMessage(ChatMessageType.CONSOLE, "", memberName
				+ " isn't on any nearby map layer - they may be inside an instance,"
				+ " so their spot can't be shown on the world map.", null);
		}
	}

	private void switchToSurfaceMap()
	{
		final Widget list = client.getWidget(MAPLIST_LIST);
		final Widget[] entries = list == null ? new Widget[0] : mapListEntries(list);
		final Widget surface = findEntryByName(entries, SURFACE_MAP_NAME);
		if (surface != null)
		{
			invokeWidgetOp(surface);
		}
	}

	private boolean invokeWidgetOp(Widget widget)
	{
		if (widget == null)
		{
			return false;
		}

		final Object[] listener = widget.getOnOpListener();
		if (listener == null)
		{
			log.debug("[LobsterPot] widget {} has no onOp listener", Integer.toHexString(widget.getId()));
			return false;
		}

		client.createScriptEventBuilder(listener)
			.setSource(widget)
			.setOp(MAPLIST_SELECT_OP)
			.build()
			.run();
		return true;
	}

	// The map list entries are the dynamic children of MAPLIST_LIST that carry a "Select" onOp
	// listener (one per selectable map). Filtering on the listener skips any non-entry children.
	private static Widget[] mapListEntries(Widget list)
	{
		final Widget[] children = list.getDynamicChildren();
		if (children == null)
		{
			return new Widget[0];
		}

		final List<Widget> entries = new ArrayList<>(children.length);
		for (Widget child : children)
		{
			if (child != null && child.getOnOpListener() != null)
			{
				entries.add(child);
			}
		}
		return entries.toArray(new Widget[0]);
	}

	private static boolean currentMapContains(WorldMap worldMap, WorldPoint target)
	{
		return worldMap.getWorldMapData() != null
			&& worldMap.getWorldMapData().surfaceContainsPosition(target.getX(), target.getY());
	}

	private boolean isMouseOverFinder(Point mouse)
	{
		if (mouse == null)
		{
			return false;
		}

		final Rectangle mapBounds = worldMapViewBounds();
		if (mapBounds == null)
		{
			return false;
		}

		if (contains(finderButtonBounds(mapBounds), mouse))
		{
			return true;
		}

		if (!finderExpanded)
		{
			return false;
		}

		return contains(finderLayout(mapBounds, Math.max(1, trackedPoints.size())).panelBounds, mouse);
	}

	private ClanMemberWorldMapPoint finderPointAt(Point mouse, FinderLayout layout, List<ClanMemberWorldMapPoint> points)
	{
		for (int i = 0; i < points.size(); i++)
		{
			if (contains(finderRowBounds(layout, i), mouse))
			{
				return points.get(i);
			}
		}
		return null;
	}

	private FinderLayout finderLayout(Rectangle mapBounds, int itemCount)
	{
		final Rectangle button = finderButtonBounds(mapBounds);
		final int maxPanelHeight = Math.max(FINDER_HEADER_HEIGHT + FINDER_ROW_HEIGHT + FINDER_PANEL_PADDING * 2,
			button.y - FINDER_PANEL_GAP - mapBounds.y - FINDER_MARGIN);
		final int maxRows = Math.max(1, (maxPanelHeight - FINDER_HEADER_HEIGHT - FINDER_PANEL_PADDING * 2) / FINDER_ROW_HEIGHT);
		final int rowsPerColumn = Math.max(1, Math.min(Math.max(1, itemCount), maxRows));
		final int columns = Math.max(1, (int) Math.ceil((double) Math.max(1, itemCount) / rowsPerColumn));
		final int maxPanelWidth = Math.max(FINDER_COLUMN_WIDTH, mapBounds.width - FINDER_MARGIN * 2);
		final int rowWidth = Math.max(92, Math.min(FINDER_COLUMN_WIDTH, (maxPanelWidth - FINDER_PANEL_PADDING * 2) / columns));
		final int panelWidth = FINDER_PANEL_PADDING * 2 + rowWidth * columns;
		final int panelHeight = FINDER_PANEL_PADDING * 2 + FINDER_HEADER_HEIGHT + rowsPerColumn * FINDER_ROW_HEIGHT;
		final int x = Math.max(mapBounds.x + FINDER_MARGIN, button.x + button.width - panelWidth);
		final int y = Math.max(mapBounds.y + FINDER_MARGIN, button.y - FINDER_PANEL_GAP - panelHeight);
		return new FinderLayout(new Rectangle(x, y, panelWidth, panelHeight), rowsPerColumn, rowWidth);
	}

	private Rectangle finderButtonBounds(Rectangle mapBounds)
	{
		return new Rectangle(
			mapBounds.x + mapBounds.width - FINDER_MARGIN - FINDER_BUTTON_SIZE,
			mapBounds.y + mapBounds.height - FINDER_MARGIN - FINDER_BUTTON_SIZE,
			FINDER_BUTTON_SIZE,
			FINDER_BUTTON_SIZE);
	}

	private Rectangle finderRowBounds(FinderLayout layout, int index)
	{
		final int column = index / layout.rowsPerColumn;
		final int row = index % layout.rowsPerColumn;
		return new Rectangle(
			layout.panelBounds.x + FINDER_PANEL_PADDING + column * layout.rowWidth,
			layout.panelBounds.y + FINDER_PANEL_PADDING + FINDER_HEADER_HEIGHT + row * FINDER_ROW_HEIGHT,
			layout.rowWidth,
			FINDER_ROW_HEIGHT);
	}

	private Rectangle worldMapViewBounds()
	{
		final Widget map = client.getWidget(ComponentID.WORLD_MAP_MAPVIEW);
		if (map == null || map.isHidden() || map.getBounds() == null)
		{
			return null;
		}
		return map.getBounds();
	}

	private List<ClanMemberWorldMapPoint> sortedFinderPoints()
	{
		final List<ClanMemberWorldMapPoint> points = new ArrayList<>(trackedPoints.values());
		points.sort(Comparator.comparing(ClanMemberWorldMapPoint::getMemberName, String.CASE_INSENSITIVE_ORDER));
		return points;
	}

	private static boolean contains(Rectangle rectangle, Point point)
	{
		return rectangle != null && point != null && rectangle.contains(point.getX(), point.getY());
	}

	private static String fitText(String text, FontMetrics metrics, int maxWidth)
	{
		if (text == null || maxWidth <= 0)
		{
			return "";
		}
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}

		final String suffix = "...";
		final int suffixWidth = metrics.stringWidth(suffix);
		if (suffixWidth >= maxWidth)
		{
			return "";
		}

		for (int i = text.length() - 1; i > 0; i--)
		{
			final String candidate = text.substring(0, i) + suffix;
			if (metrics.stringWidth(candidate) <= maxWidth)
			{
				return candidate;
			}
		}
		return "";
	}

	private void ensurePositionSocket(String rsn)
	{
		final String key = playerKey(rsn);
		if (key.isEmpty())
		{
			disconnectPositionSocket();
			return;
		}

		final WebSocket socket = positionSocket;
		if (socket != null && key.equals(socketPlayerKey))
		{
			return;
		}

		if (socket != null)
		{
			disconnectPositionSocket();
		}

		if (socketConnecting && key.equals(socketPlayerKey))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		if (now - lastSocketAttemptAt < SOCKET_RECONNECT_DELAY_MS)
		{
			return;
		}

		final HttpUrl backendUrl = HttpUrl.parse(BACKEND_URL + "/positions");
		if (backendUrl == null)
		{
			return;
		}

		final Request request = new Request.Builder()
			.url(backendUrl.newBuilder().addQueryParameter("viewer", rsn).build())
			.build();

		socketConnecting = true;
		socketPlayerKey = key;
		lastSocketAttemptAt = now;
		positionSocket = httpClient.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket webSocket, Response response)
			{
				socketConnecting = false;
			}

			@Override
			public void onMessage(WebSocket webSocket, String text)
			{
				handlePositionSnapshot(rsn, text);
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason)
			{
				handleSocketClosed(webSocket);
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable t, Response response)
			{
				handleSocketClosed(webSocket);
			}
		});
	}

	private void sendPosition(String rsn, WorldPoint wp, int world, String activity)
	{
		final WebSocket socket = positionSocket;
		if (socket == null || !playerKey(rsn).equals(socketPlayerKey))
		{
			ensurePositionSocket(rsn);
			return;
		}

		final String json = gson.toJson(new ClanPosition(rsn, wp.getX(), wp.getY(), wp.getPlane(), world, activity));
		if (socket.send(json))
		{
			sharedPositionOnSocket = true;
			lastSentX = wp.getX();
			lastSentY = wp.getY();
			lastSentPlane = wp.getPlane();
			lastSentWorld = world;
			lastSentActivity = activity == null ? "" : activity;
			lastSentAtMs = System.currentTimeMillis();
			return;
		}

		handleSocketClosed(socket);
	}

	private void clearSharedPosition()
	{
		if (!sharedPositionOnSocket)
		{
			return;
		}

		final WebSocket socket = positionSocket;
		if (socket != null)
		{
			socket.send("{\"type\":\"clear\"}");
		}
		sharedPositionOnSocket = false;
	}

	private void disconnectPositionSocket()
	{
		final WebSocket socket = positionSocket;
		final boolean shouldClearPosition = sharedPositionOnSocket;
		positionSocket = null;
		socketConnecting = false;
		socketPlayerKey = "";
		sharedPositionOnSocket = false;
		if (socket != null)
		{
			if (shouldClearPosition)
			{
				socket.send("{\"type\":\"clear\"}");
			}
			socket.close(1000, "Plugin stopped");
		}
	}

	private void handleSocketClosed(WebSocket webSocket)
	{
		if (webSocket != positionSocket)
		{
			return;
		}

		positionSocket = null;
		socketConnecting = false;
		sharedPositionOnSocket = false;
		clientThread.invokeLater(() ->
		{
			if (active)
			{
				clearPoints();
			}
		});
	}

	private void handlePositionSnapshot(String localRsn, String message)
	{
		final ClanPosition[] positions;
		try
		{
			positions = gson.fromJson(message, ClanPosition[].class);
		}
		catch (JsonSyntaxException ex)
		{
			return;
		}

		if (positions == null)
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			if (active)
			{
				updateMapPoints(localRsn, positions);
			}
		});
	}

	private void updateMapPoints(String localRsn, ClanPosition[] positions)
	{
		final ClanChannel clanChannel = client.getClanChannel();
		final ClanSettings clanSettings = client.getClanSettings();
		final Set<String> clanMemberKeys = clanMemberKeys(clanChannel, clanSettings);
		final String localKey = playerKey(localRsn);
		final Set<String> activeKeys = new HashSet<>();
		if (clanMemberKeys.isEmpty())
		{
			clearPoints();
			return;
		}

		for (ClanPosition pos : positions)
		{
			if (pos.rsn == null)
			{
				continue;
			}

			final String key = playerKey(pos.rsn);
			if (key.isEmpty() || key.equals(localKey) || !clanMemberKeys.contains(key) || !isValidPosition(pos))
			{
				continue;
			}

			activeKeys.add(key);

			final WorldPoint wp = new WorldPoint(pos.x, pos.y, pos.plane);
			final ClanTitle title = resolveTitle(clanChannel, clanSettings, key);

			ClanMemberWorldMapPoint point = trackedPoints.get(key);
			if (point == null)
			{
				point = new ClanMemberWorldMapPoint(wp, pos.rsn, pos.world, pos.activity);
				applyRankIcon(point, title);
				trackedPoints.put(key, point);
				worldMapPointManager.add(point);
			}
			else
			{
				point.setWorldPoint(wp);
				if (pos.world != point.getWorld())
				{
					point.setWorld(pos.world);
				}
				if (!sameText(pos.activity, point.getActivity()))
				{
					point.setActivity(pos.activity);
				}
				final int titleId = title == null ? ClanMemberWorldMapPoint.NO_TITLE : title.getId();
				if (titleId != point.getTitleId())
				{
					applyRankIcon(point, title);
				}
			}
		}

		trackedPoints.entrySet().removeIf(entry ->
		{
			if (!activeKeys.contains(entry.getKey()))
			{
				worldMapPointManager.remove(entry.getValue());
				return true;
			}
			return false;
		});
	}

	private void applyRankIcon(ClanMemberWorldMapPoint point, ClanTitle title)
	{
		final BufferedImage rankImage = title == null ? null : chatIconManager.getRankImage(title);
		point.applyRank(rankImage, rankImage != null ? title.getId() : ClanMemberWorldMapPoint.NO_TITLE);
	}

	private ClanTitle resolveTitle(ClanChannel clanChannel, ClanSettings clanSettings, String memberKey)
	{
		if (clanSettings == null)
		{
			return null;
		}

		if (clanChannel != null)
		{
			for (ClanChannelMember member : clanChannel.getMembers())
			{
				if (member != null && member.getName() != null && memberKey.equals(playerKey(member.getName())))
				{
					final ClanRank rank = member.getRank();
					return rank == null ? null : clanSettings.titleForRank(rank);
				}
			}
		}

		for (net.runelite.api.clan.ClanMember member : clanSettings.getMembers())
		{
			if (member != null && member.getName() != null && memberKey.equals(playerKey(member.getName())))
			{
				final ClanRank rank = member.getRank();
				return rank == null ? null : clanSettings.titleForRank(rank);
			}
		}
		return null;
	}

	private void hopTo(ClanMemberWorldMapPoint point)
	{
		// Don't hop straight away - it's easy to click a marker by accident. Show a confirmation
		// prompt on the map first; the actual hop only happens once the user clicks "Hop".
		pendingHopPoint = point;
	}

	private void confirmHop()
	{
		final ClanMemberWorldMapPoint point = pendingHopPoint;
		clearHopPrompt();
		if (point != null)
		{
			final int worldId = point.getWorld();
			clientThread.invoke(() -> startWorldHop(worldId));
		}
	}

	private void clearHopPrompt()
	{
		pendingHopPoint = null;
	}

	private void startWorldHop(int worldId)
	{
		if (worldId <= 0 || worldId == client.getWorld())
		{
			return;
		}

		final World world = worldById(worldId);
		if (world == null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Could not find world " + worldId + " in the world list.", null);
			return;
		}

		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			client.changeWorld(world);
			return;
		}

		client.addChatMessage(ChatMessageType.CONSOLE, "", "Quick-hopping to World " + worldId + "..", null);
		quickHopTargetWorld = world;
		displaySwitcherAttempts = 0;
	}

	private void continueWorldHop()
	{
		if (quickHopTargetWorld == null)
		{
			return;
		}

		if (client.getWidget(ComponentID.WORLD_SWITCHER_WORLD_LIST) == null)
		{
			client.openWorldHopper();
			if (++displaySwitcherAttempts >= MAX_HOP_ATTEMPTS)
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", "Failed to quick-hop after " + displaySwitcherAttempts + " attempts.", null);
				resetQuickHopper();
			}
			return;
		}

		client.hopToWorld(quickHopTargetWorld);
		resetQuickHopper();
	}

	private void resetQuickHopper()
	{
		displaySwitcherAttempts = 0;
		quickHopTargetWorld = null;
	}

	private ClanMemberWorldMapPoint hoveredPoint()
	{
		final Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return null;
		}

		for (ClanMemberWorldMapPoint point : trackedPoints.values())
		{
			final BufferedImage image = point.getImage();
			final Point mapPoint = mapWorldPointToGraphicsPoint(point.getWorldPoint());
			if (image == null || mapPoint == null)
			{
				continue;
			}

			final Point imagePoint = point.getImagePoint();
			final int imageX = imagePoint == null ? mapPoint.getX() - image.getWidth() / 2 : mapPoint.getX() - imagePoint.getX();
			final int imageY = imagePoint == null ? mapPoint.getY() - image.getHeight() / 2 : mapPoint.getY() - imagePoint.getY();

			final Rectangle bounds = new Rectangle(
				imageX,
				imageY,
				image.getWidth(),
				image.getHeight());
			bounds.grow(MAP_MARKER_HOVER_PADDING, MAP_MARKER_HOVER_PADDING);
			if (bounds.contains(mouse.getX(), mouse.getY()))
			{
				return point;
			}
		}
		return null;
	}

	private Point mapWorldPointToGraphicsPoint(WorldPoint worldPoint)
	{
		final WorldMap worldMap = client.getWorldMap();
		final Widget map = client.getWidget(ComponentID.WORLD_MAP_MAPVIEW);
		if (worldMap == null || worldMap.getWorldMapData() == null || map == null || map.isHidden()
			|| !worldMap.getWorldMapData().surfaceContainsPosition(worldPoint.getX(), worldPoint.getY()))
		{
			return null;
		}

		final float zoom = worldMap.getWorldMapZoom();
		if (zoom <= 0)
		{
			return null;
		}

		final Rectangle bounds = map.getBounds();
		final Point mapPosition = worldMap.getWorldMapPosition();
		if (bounds == null || mapPosition == null)
		{
			return null;
		}

		final int widthInTiles = (int) Math.ceil(bounds.getWidth() / zoom);
		final int heightInTiles = (int) Math.ceil(bounds.getHeight() / zoom);

		final int yTileMax = mapPosition.getY() - heightInTiles / 2;
		final int xTileOffset = worldPoint.getX() + widthInTiles / 2 - mapPosition.getX();
		final int yTileOffset = (yTileMax - worldPoint.getY() - 1) * -1;
		final int halfZoom = (int) Math.ceil(zoom / 2.0f);

		final int x = (int) (xTileOffset * zoom + zoom - halfZoom) + (int) bounds.getX();
		final int y = bounds.height - (int) (yTileOffset * zoom - zoom - halfZoom) + (int) bounds.getY();
		return new Point(x, y);
	}

	private boolean hasHopMenuEntry(ClanMemberWorldMapPoint point)
	{
		final MenuEntry[] entries = client.getMenuEntries();
		if (entries == null)
		{
			return false;
		}

		final String key = playerKey(point.getMemberName());
		for (MenuEntry entry : entries)
		{
			if (entry != null && ("Hop-to".equals(entry.getOption()) || "Hop to".equals(entry.getOption()) || "Focus on".equals(entry.getOption()))
				&& key.equals(playerKey(Text.removeTags(entry.getTarget()))))
			{
				return true;
			}
		}
		return false;
	}

	private World worldById(int worldId)
	{
		final World[] worlds = client.getWorldList();
		if (worlds != null)
		{
			for (World world : worlds)
			{
				if (world != null && world.getId() == worldId)
				{
					return world;
				}
			}
		}

		final WorldResult worldResult = worldService.getWorlds();
		final net.runelite.http.api.worlds.World serviceWorld = worldResult == null ? null : worldResult.findWorld(worldId);
		if (serviceWorld != null)
		{
			return createWorld(serviceWorld.getId(), serviceWorld.getAddress(), serviceWorld.getActivity(), serviceWorld.getLocation(),
				serviceWorld.getPlayers(), WorldUtil.toWorldTypes(serviceWorld.getTypes()));
		}

		final World world = client.createWorld();
		world.setId(worldId);
		world.setAddress("oldschool" + worldId + ".runescape.com");
		world.setActivity("");
		world.setLocation(0);
		world.setPlayerCount(0);
		world.setTypes(EnumSet.noneOf(WorldType.class));
		return world;
	}

	private World createWorld(int id, String address, String activity, int location, int playerCount, EnumSet<WorldType> types)
	{
		final World world = client.createWorld();
		world.setId(id);
		world.setAddress(address);
		world.setActivity(activity == null ? "" : activity);
		world.setLocation(location);
		world.setPlayerCount(playerCount);
		world.setTypes(types == null ? EnumSet.noneOf(WorldType.class) : types);
		return world;
	}

	private String currentPlayerActivity(Player localPlayer, int worldId)
	{
		final Actor interacting = localPlayer.getInteracting();
		if (interacting != null && interacting != localPlayer && interacting.getCombatLevel() > 0 && hasText(interacting.getName()))
		{
			final String targetName = sanitizeActivity(interacting.getName());
			if (hasText(targetName))
			{
				return "Fighting " + targetName;
			}
		}

		final String animationActivity = ACTIVITY_BY_ANIMATION.get(localPlayer.getAnimation());
		if (hasText(animationActivity))
		{
			return animationActivity;
		}

		return "";
	}

	private static Set<String> clanMemberKeys(ClanChannel clanChannel, ClanSettings clanSettings)
	{
		final Set<String> memberKeys = new HashSet<>();
		if (clanChannel != null)
		{
			for (ClanChannelMember member : clanChannel.getMembers())
			{
				if (member != null && member.getName() != null)
				{
					memberKeys.add(playerKey(member.getName()));
				}
			}
		}
		if (clanSettings != null)
		{
			for (net.runelite.api.clan.ClanMember member : clanSettings.getMembers())
			{
				if (member != null && member.getName() != null)
				{
					memberKeys.add(playerKey(member.getName()));
				}
			}
		}
		memberKeys.remove("");
		return memberKeys;
	}

	private static boolean isValidPosition(ClanPosition position)
	{
		return position.x >= 0 && position.x <= MAX_POSITION_DISTANCE
			&& position.y >= 0 && position.y <= MAX_POSITION_DISTANCE
			&& position.plane >= 0 && position.plane <= 3
			&& position.world > 0;
	}

	private static boolean sameText(String left, String right)
	{
		return (left == null ? "" : left.trim()).equals(right == null ? "" : right.trim());
	}

	private static class FinderLayout
	{
		private final Rectangle panelBounds;
		private final int rowsPerColumn;
		private final int rowWidth;

		private FinderLayout(Rectangle panelBounds, int rowsPerColumn, int rowWidth)
		{
			this.panelBounds = panelBounds;
			this.rowsPerColumn = rowsPerColumn;
			this.rowWidth = rowWidth;
		}
	}

	private static Map<Integer, String> buildActivityByAnimation()
	{
		final Map<Integer, String> activities = new HashMap<>();
		addActivity(activities, "Woodcutting",
			AnimationID.WOODCUTTING_BRONZE,
			AnimationID.WOODCUTTING_IRON,
			AnimationID.WOODCUTTING_STEEL,
			AnimationID.WOODCUTTING_BLACK,
			AnimationID.WOODCUTTING_MITHRIL,
			AnimationID.WOODCUTTING_ADAMANT,
			AnimationID.WOODCUTTING_RUNE,
			AnimationID.WOODCUTTING_GILDED,
			AnimationID.WOODCUTTING_DRAGON,
			AnimationID.WOODCUTTING_DRAGON_OR,
			AnimationID.WOODCUTTING_INFERNAL,
			AnimationID.WOODCUTTING_3A_AXE,
			AnimationID.WOODCUTTING_CRYSTAL,
			AnimationID.WOODCUTTING_TRAILBLAZER,
			AnimationID.WOODCUTTING_2H_BRONZE,
			AnimationID.WOODCUTTING_2H_IRON,
			AnimationID.WOODCUTTING_2H_STEEL,
			AnimationID.WOODCUTTING_2H_BLACK,
			AnimationID.WOODCUTTING_2H_MITHRIL,
			AnimationID.WOODCUTTING_2H_ADAMANT,
			AnimationID.WOODCUTTING_2H_RUNE,
			AnimationID.WOODCUTTING_2H_DRAGON,
			AnimationID.WOODCUTTING_2H_CRYSTAL,
			AnimationID.WOODCUTTING_2H_CRYSTAL_INACTIVE,
			AnimationID.WOODCUTTING_2H_3A,
			AnimationID.WOODCUTTING_ENT_BRONZE,
			AnimationID.WOODCUTTING_ENT_IRON,
			AnimationID.WOODCUTTING_ENT_STEEL,
			AnimationID.WOODCUTTING_ENT_BLACK,
			AnimationID.WOODCUTTING_ENT_MITHRIL,
			AnimationID.WOODCUTTING_ENT_ADAMANT,
			AnimationID.WOODCUTTING_ENT_RUNE,
			AnimationID.WOODCUTTING_ENT_GILDED,
			AnimationID.WOODCUTTING_ENT_DRAGON,
			AnimationID.WOODCUTTING_ENT_DRAGON_OR,
			AnimationID.WOODCUTTING_ENT_INFERNAL,
			AnimationID.WOODCUTTING_ENT_INFERNAL_OR,
			AnimationID.WOODCUTTING_ENT_3A,
			AnimationID.WOODCUTTING_ENT_CRYSTAL,
			AnimationID.WOODCUTTING_ENT_CRYSTAL_INACTIVE,
			AnimationID.WOODCUTTING_ENT_TRAILBLAZER,
			AnimationID.WOODCUTTING_ENT_2H_BRONZE,
			AnimationID.WOODCUTTING_ENT_2H_IRON,
			AnimationID.WOODCUTTING_ENT_2H_STEEL,
			AnimationID.WOODCUTTING_ENT_2H_BLACK,
			AnimationID.WOODCUTTING_ENT_2H_MITHRIL,
			AnimationID.WOODCUTTING_ENT_2H_ADAMANT,
			AnimationID.WOODCUTTING_ENT_2H_RUNE,
			AnimationID.WOODCUTTING_ENT_2H_DRAGON,
			AnimationID.WOODCUTTING_ENT_2H_CRYSTAL,
			AnimationID.WOODCUTTING_ENT_2H_CRYSTAL_INACTIVE,
			AnimationID.WOODCUTTING_ENT_2H_3A);
		addActivity(activities, "Mining",
			AnimationID.MINING_BRONZE_PICKAXE,
			AnimationID.MINING_IRON_PICKAXE,
			AnimationID.MINING_STEEL_PICKAXE,
			AnimationID.MINING_BLACK_PICKAXE,
			AnimationID.MINING_MITHRIL_PICKAXE,
			AnimationID.MINING_ADAMANT_PICKAXE,
			AnimationID.MINING_RUNE_PICKAXE,
			AnimationID.MINING_GILDED_PICKAXE,
			AnimationID.MINING_DRAGON_PICKAXE,
			AnimationID.MINING_DRAGON_PICKAXE_UPGRADED,
			AnimationID.MINING_DRAGON_PICKAXE_OR,
			AnimationID.MINING_DRAGON_PICKAXE_OR_TRAILBLAZER,
			AnimationID.MINING_INFERNAL_PICKAXE,
			AnimationID.MINING_3A_PICKAXE,
			AnimationID.MINING_CRYSTAL_PICKAXE,
			AnimationID.MINING_TRAILBLAZER_PICKAXE,
			AnimationID.MINING_TRAILBLAZER_PICKAXE_2,
			AnimationID.MINING_TRAILBLAZER_PICKAXE_3,
			AnimationID.MINING_MOTHERLODE_BRONZE,
			AnimationID.MINING_MOTHERLODE_IRON,
			AnimationID.MINING_MOTHERLODE_STEEL,
			AnimationID.MINING_MOTHERLODE_BLACK,
			AnimationID.MINING_MOTHERLODE_MITHRIL,
			AnimationID.MINING_MOTHERLODE_ADAMANT,
			AnimationID.MINING_MOTHERLODE_RUNE,
			AnimationID.MINING_MOTHERLODE_GILDED,
			AnimationID.MINING_MOTHERLODE_DRAGON,
			AnimationID.MINING_MOTHERLODE_DRAGON_UPGRADED,
			AnimationID.MINING_MOTHERLODE_DRAGON_OR,
			AnimationID.MINING_MOTHERLODE_DRAGON_OR_TRAILBLAZER,
			AnimationID.MINING_MOTHERLODE_INFERNAL,
			AnimationID.MINING_MOTHERLODE_3A,
			AnimationID.MINING_MOTHERLODE_CRYSTAL,
			AnimationID.MINING_MOTHERLODE_TRAILBLAZER,
			AnimationID.MINING_CRASHEDSTAR_BRONZE,
			AnimationID.MINING_CRASHEDSTAR_IRON,
			AnimationID.MINING_CRASHEDSTAR_STEEL,
			AnimationID.MINING_CRASHEDSTAR_BLACK,
			AnimationID.MINING_CRASHEDSTAR_MITHRIL,
			AnimationID.MINING_CRASHEDSTAR_ADAMANT,
			AnimationID.MINING_CRASHEDSTAR_RUNE,
			AnimationID.MINING_CRASHEDSTAR_GILDED,
			AnimationID.MINING_CRASHEDSTAR_DRAGON,
			AnimationID.MINING_CRASHEDSTAR_DRAGON_UPGRADED,
			AnimationID.MINING_CRASHEDSTAR_DRAGON_OR,
			AnimationID.MINING_CRASHEDSTAR_DRAGON_OR_TRAILBLAZER,
			AnimationID.MINING_CRASHEDSTAR_INFERNAL,
			AnimationID.MINING_CRASHEDSTAR_3A,
			AnimationID.MINING_CRASHEDSTAR_CRYSTAL,
			AnimationID.DENSE_ESSENCE_CHIPPING,
			AnimationID.DENSE_ESSENCE_CHISELING);
		addActivity(activities, "Fishing",
			AnimationID.FISHING_BIG_NET,
			AnimationID.FISHING_NET,
			AnimationID.FISHING_POLE_CAST,
			AnimationID.FISHING_CAGE,
			AnimationID.FISHING_HARPOON,
			AnimationID.FISHING_BARBTAIL_HARPOON,
			AnimationID.FISHING_DRAGON_HARPOON,
			AnimationID.FISHING_DRAGON_HARPOON_OR,
			AnimationID.FISHING_INFERNAL_HARPOON,
			AnimationID.FISHING_CRYSTAL_HARPOON,
			AnimationID.FISHING_TRAILBLAZER_HARPOON,
			AnimationID.FISHING_OILY_ROD,
			AnimationID.FISHING_KARAMBWAN,
			AnimationID.FISHING_CRUSHING_INFERNAL_EELS,
			AnimationID.FISHING_CRUSHING_INFERNAL_EELS_IMCANDO_HAMMER,
			AnimationID.FISHING_CUTTING_SACRED_EELS,
			AnimationID.FISHING_BAREHAND,
			AnimationID.FISHING_BAREHAND_WINDUP_1,
			AnimationID.FISHING_BAREHAND_WINDUP_2,
			AnimationID.FISHING_BAREHAND_CAUGHT_SHARK_1,
			AnimationID.FISHING_BAREHAND_CAUGHT_SHARK_2,
			AnimationID.FISHING_BAREHAND_CAUGHT_SWORDFISH_1,
			AnimationID.FISHING_BAREHAND_CAUGHT_SWORDFISH_2,
			AnimationID.FISHING_BAREHAND_CAUGHT_TUNA_1,
			AnimationID.FISHING_BAREHAND_CAUGHT_TUNA_2,
			AnimationID.FISHING_PEARL_ROD,
			AnimationID.FISHING_PEARL_FLY_ROD,
			AnimationID.FISHING_PEARL_BARBARIAN_ROD,
			AnimationID.FISHING_PEARL_ROD_2,
			AnimationID.FISHING_PEARL_FLY_ROD_2,
			AnimationID.FISHING_PEARL_BARBARIAN_ROD_2,
			AnimationID.FISHING_PEARL_OILY_ROD,
			AnimationID.FISHING_BARBARIAN_ROD);
		addActivity(activities, "Cooking",
			AnimationID.COOKING_FIRE,
			AnimationID.COOKING_RANGE,
			AnimationID.COOKING_WINE);
		addActivity(activities, "Smithing",
			AnimationID.SMITHING_SMELTING,
			AnimationID.SMITHING_CANNONBALL,
			AnimationID.SMITHING_ANVIL,
			AnimationID.SMITHING_IMCANDO_HAMMER,
			AnimationID.GIANTS_FOUNDRY_WATER_WHEEL_SPINNING);
		addActivity(activities, "Fletching",
			AnimationID.FLETCHING_BOW_CUTTING,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_BRONZE_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_BLURITE_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_IRON_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_STEEL_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_MITHRIL_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_ADAMANTITE_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_RUNITE_LIMBS,
			AnimationID.FLETCHING_ATTACH_STOCK_TO_DRAGON_LIMBS,
			AnimationID.FLETCHING_STRING_NORMAL_SHORTBOW,
			AnimationID.FLETCHING_STRING_NORMAL_LONGBOW,
			AnimationID.FLETCHING_STRING_OAK_SHORTBOW,
			AnimationID.FLETCHING_STRING_OAK_LONGBOW,
			AnimationID.FLETCHING_STRING_WILLOW_SHORTBOW,
			AnimationID.FLETCHING_STRING_WILLOW_LONGBOW,
			AnimationID.FLETCHING_STRING_MAPLE_SHORTBOW,
			AnimationID.FLETCHING_STRING_MAPLE_LONGBOW,
			AnimationID.FLETCHING_STRING_YEW_SHORTBOW,
			AnimationID.FLETCHING_STRING_YEW_LONGBOW,
			AnimationID.FLETCHING_STRING_MAGIC_SHORTBOW,
			AnimationID.FLETCHING_STRING_MAGIC_LONGBOW,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_BRONZE_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_IRON_BROAD_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_BLURITE_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_STEEL_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_MITHRIL_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_ADAMANT_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_RUNE_BOLT,
			AnimationID.FLETCHING_ATTACH_BOLT_TIPS_TO_DRAGON_BOLT,
			AnimationID.FLETCHING_ATTACH_HEADS,
			AnimationID.FLETCHING_ATTACH_FEATHERS_TO_ARROWSHAFT);
		addActivity(activities, "Crafting",
			AnimationID.GEM_CUTTING_OPAL,
			AnimationID.GEM_CUTTING_JADE,
			AnimationID.GEM_CUTTING_REDTOPAZ,
			AnimationID.GEM_CUTTING_SAPPHIRE,
			AnimationID.GEM_CUTTING_EMERALD,
			AnimationID.GEM_CUTTING_RUBY,
			AnimationID.GEM_CUTTING_DIAMOND,
			AnimationID.GEM_CUTTING_AMETHYST,
			AnimationID.CRAFTING_LEATHER,
			AnimationID.CRAFTING_GLASSBLOWING,
			AnimationID.CRAFTING_SPINNING,
			AnimationID.CRAFTING_POTTERS_WHEEL,
			AnimationID.CRAFTING_POTTERY_OVEN,
			AnimationID.CRAFTING_LOOM,
			AnimationID.CRAFTING_CRUSH_BLESSED_BONES,
			AnimationID.CRAFTING_BATTLESTAVES,
			AnimationID.CHURN_MILK_SHORT,
			AnimationID.CHURN_MILK_MEDIUM,
			AnimationID.CHURN_MILK_LONG);
		addActivity(activities, "Herblore",
			AnimationID.HERBLORE_PESTLE_AND_MORTAR,
			AnimationID.HERBLORE_MAKE_TAR,
			AnimationID.HERBLORE_POTIONMAKING,
			AnimationID.HERBLORE_MIXOLOGY_CONCENTRATE,
			AnimationID.HERBLORE_MIXOLOGY_CRYSTALIZE,
			AnimationID.HERBLORE_MIXOLOGY_HOMOGENIZE,
			AnimationID.HERBLORE_MIXOLOGY_REFINER);
		addActivity(activities, "Firemaking",
			AnimationID.FIREMAKING,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_ARCTIC_PINE,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_BLISTERWOOD,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_LOGS,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_MAGIC,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_MAHOGANY,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_MAPLE,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_OAK,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_REDWOOD,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_TEAK,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_WILLOW,
			AnimationID.FIREMAKING_FORESTERS_CAMPFIRE_YEW);
		addActivity(activities, "Casting Magic",
			AnimationID.MAGIC_CHARGING_ORBS,
			AnimationID.MAGIC_MAKE_TABLET,
			AnimationID.MAGIC_ENCHANTING_JEWELRY,
			AnimationID.MAGIC_ENCHANTING_AMULET_1,
			AnimationID.MAGIC_ENCHANTING_AMULET_2,
			AnimationID.MAGIC_ENCHANTING_AMULET_3,
			AnimationID.MAGIC_ENCHANTING_BOLTS,
			AnimationID.MAGIC_LUNAR_SHARED,
			AnimationID.MAGIC_LUNAR_CURE_PLANT,
			AnimationID.MAGIC_LUNAR_PLANK_MAKE,
			AnimationID.MAGIC_LUNAR_STRING_JEWELRY,
			AnimationID.MAGIC_ARCEUUS_RESURRECT_CROPS,
			AnimationID.MAGIC_ARCEUUS_DEMONBANE);
		addActivity(activities, "Thieving",
			AnimationID.THIEVING_VARLAMORE_STEALING_VALUABLES);
		addActivity(activities, "Hunter",
			AnimationID.HUNTER_LAY_BOXTRAP_BIRDSNARE,
			AnimationID.HUNTER_LAY_NETTRAP,
			AnimationID.HUNTER_LAY_MANIACAL_MONKEY_BOULDER_TRAP,
			AnimationID.HUNTER_CHECK_BIRD_SNARE);
		addActivity(activities, "Construction",
			AnimationID.CONSTRUCTION,
			AnimationID.CONSTRUCTION_IMCANDO,
			AnimationID.HOME_MAKE_TABLET,
			AnimationID.PISCARILIUS_CRANE_REPAIR);
		addActivity(activities, "Farming",
			AnimationID.FARMING_HARVEST_FRUIT_TREE,
			AnimationID.FARMING_HARVEST_BUSH,
			AnimationID.FARMING_HARVEST_HERB,
			AnimationID.FARMING_USE_COMPOST,
			AnimationID.FARMING_CURE_WITH_POTION,
			AnimationID.FARMING_PLANT_SEED,
			AnimationID.FARMING_HARVEST_FLOWER,
			AnimationID.FARMING_MIX_ULTRACOMPOST,
			AnimationID.FARMING_HARVEST_ALLOTMENT);
		addActivity(activities, "Prayer",
			AnimationID.BURYING_BONES,
			AnimationID.USING_GILDED_ALTAR,
			AnimationID.ECTOFUNTUS_FILL_SLIME_BUCKET,
			AnimationID.ECTOFUNTUS_GRIND_BONES,
			AnimationID.ECTOFUNTUS_INSERT_BONES,
			AnimationID.ECTOFUNTUS_EMPTY_BIN);
		addActivity(activities, "Digging", AnimationID.DIG);
		return Collections.unmodifiableMap(activities);
	}

	private static void addActivity(Map<Integer, String> activities, String activity, int... animationIds)
	{
		for (int animationId : animationIds)
		{
			activities.put(animationId, activity);
		}
	}

	private static String sanitizeActivity(String activity)
	{
		if (activity == null)
		{
			return "";
		}

		final String cleanActivity = Text.removeTags(activity).replaceAll("\\s+", " ").trim();
		if (cleanActivity.length() <= MAX_ACTIVITY_LENGTH)
		{
			return cleanActivity;
		}
		return cleanActivity.substring(0, MAX_ACTIVITY_LENGTH);
	}

	private static boolean hasText(String value)
	{
		return value != null && !value.trim().isEmpty();
	}

	private static String playerKey(String name)
	{
		if (name == null)
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++)
		{
			final char c = Character.toLowerCase(name.charAt(i));
			if (c != ' ' && c != '_' && c != '-' && c != '\u00A0')
			{
				sb.append(c);
			}
		}
		return sb.toString().toLowerCase(Locale.ENGLISH);
	}
}
