package com.lobsterpot.worldmap;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
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

@Singleton
public class ClanPositionService
{
	static final String BACKEND_URL = "https://lobsterpot-positions.lobsterpot.workers.dev";

	private static final int TICKS_PER_UPDATE = 8;
	private static final int MAX_HOP_ATTEMPTS = 3;
	private static final int MAX_POSITION_DISTANCE = 64_000;
	private static final int MAX_ACTIVITY_LENGTH = 80;
	private static final int MAP_MARKER_HOVER_PADDING = 6;
	private static final long SOCKET_RECONNECT_DELAY_MS = 10_000L;
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

	private volatile boolean active = false;
	private volatile WebSocket positionSocket;
	private volatile boolean socketConnecting = false;
	private volatile String socketPlayerKey = "";
	private long lastSocketAttemptAt = 0L;
	private volatile boolean sharedPositionOnSocket = false;
	private int tickCounter = 0;
	private int displaySwitcherAttempts = 0;
	private World quickHopTargetWorld;
	private final Map<String, ClanMemberWorldMapPoint> trackedPoints = new HashMap<>();

	public void start()
	{
		active = true;
		tickCounter = 0;
		sharedPositionOnSocket = false;
	}

	public void stop()
	{
		active = false;
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
		sendPosition(rsn, wp, world, activity);
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

	public void clearPoints()
	{
		for (ClanMemberWorldMapPoint point : trackedPoints.values())
		{
			worldMapPointManager.remove(point);
		}
		trackedPoints.clear();
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
		positionSocket = null;
		socketConnecting = false;
		socketPlayerKey = "";
		sharedPositionOnSocket = false;
		if (socket != null)
		{
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
		final int worldId = point.getWorld();
		clientThread.invoke(() -> startWorldHop(worldId));
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

	private void removeMenuEntry(MenuEntry menuEntry)
	{
		final MenuEntry[] entries = client.getMenuEntries();
		if (menuEntry == null || entries == null || entries.length == 0)
		{
			return;
		}

		final List<MenuEntry> keptEntries = new ArrayList<>(entries.length);
		boolean removed = false;
		for (MenuEntry entry : entries)
		{
			if (!removed && sameMenuEntry(entry, menuEntry))
			{
				removed = true;
				continue;
			}
			keptEntries.add(entry);
		}

		if (removed)
		{
			client.setMenuEntries(keptEntries.toArray(new MenuEntry[0]));
		}
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

	private String currentWorldActivity(int worldId)
	{
		final World world = worldById(worldId);
		if (world == null || world.getActivity() == null)
		{
			return "";
		}
		return world.getActivity().trim();
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

	private static boolean sameMenuEntry(MenuEntry left, MenuEntry right)
	{
		return left == right
			|| left != null
			&& right != null
			&& sameText(left.getOption(), right.getOption())
			&& sameText(left.getTarget(), right.getTarget())
			&& left.getIdentifier() == right.getIdentifier()
			&& left.getParam0() == right.getParam0()
			&& left.getParam1() == right.getParam1()
			&& left.getType() == right.getType();
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
