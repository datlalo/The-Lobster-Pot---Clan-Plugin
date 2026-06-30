package com.lobsterpot.worldmap;

import com.google.gson.Gson;
import com.lobsterpot.ClanMembershipService.ClanAccess;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public class ClanPositionService
{
	static final String BACKEND_URL = "https://lobsterpot-positions.lobsterpot.workers.dev";

	private static final int TICKS_PER_UPDATE = 8;
	private static final int MAX_HOP_ATTEMPTS = 3;
	private static final int MAX_POSITION_DISTANCE = 64_000;
	private static final int MAX_ACTIVITY_LENGTH = 80;
	private static final int MAP_MARKER_HOVER_PADDING = 6;
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
	private int tickCounter = 0;
	private int displaySwitcherAttempts = 0;
	private World quickHopTargetWorld;
	private final Map<String, ClanMemberWorldMapPoint> trackedPoints = new HashMap<>();

	public void start()
	{
		active = true;
		tickCounter = 0;
	}

	public void stop()
	{
		active = false;
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
			clearPoints();
			return;
		}

		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || localPlayer.getName() == null)
		{
			return;
		}

		final String rsn = localPlayer.getName();
		final WorldPoint wp = localPlayer.getWorldLocation();
		if (wp == null)
		{
			return;
		}

		final int world = client.getWorld();
		if (shareLocation)
		{
			final String activity = currentPlayerActivity(localPlayer, world);
			postPosition(rsn, wp, world, activity);
		}

		fetchPositions(rsn);
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

	private void postPosition(String rsn, WorldPoint wp, int world, String activity)
	{
		final String json = gson.toJson(new ClanPosition(rsn, wp.getX(), wp.getY(), wp.getPlane(), world, activity));
		final Request request = new Request.Builder()
			.url(BACKEND_URL + "/position")
			.post(RequestBody.create(MediaType.parse("application/json"), json))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}

	private void fetchPositions(String localRsn)
	{
		final String encodedRsn = URLEncoder.encode(localRsn, StandardCharsets.UTF_8);
		final Request request = new Request.Builder()
			.url(BACKEND_URL + "/positions?viewer=" + encodedRsn)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody body = response.body())
				{
					if (!active || !response.isSuccessful() || body == null)
					{
						return;
					}

					final ClanPosition[] positions = gson.fromJson(body.charStream(), ClanPosition[].class);
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
		final Map<Integer, String> activities = new LinkedHashMap<>();
		for (Field field : AnimationID.class.getFields())
		{
			if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers()))
			{
				continue;
			}

			final String activity = activityForAnimationName(field.getName());
			if (!hasText(activity))
			{
				continue;
			}

			try
			{
				activities.put(field.getInt(null), activity);
			}
			catch (IllegalAccessException ignored)
			{
			}
		}
		return Collections.unmodifiableMap(activities);
	}

	private static String activityForAnimationName(String animationName)
	{
		if (animationName.startsWith("WOODCUTTING"))
		{
			return "Woodcutting";
		}
		if (animationName.startsWith("MINING") || animationName.startsWith("DENSE_ESSENCE"))
		{
			return "Mining";
		}
		if (animationName.startsWith("FISHING"))
		{
			return "Fishing";
		}
		if (animationName.startsWith("COOKING"))
		{
			return "Cooking";
		}
		if (animationName.startsWith("SMITHING") || animationName.startsWith("GIANTS_FOUNDRY"))
		{
			return "Smithing";
		}
		if (animationName.startsWith("FLETCHING"))
		{
			return "Fletching";
		}
		if (animationName.startsWith("CRAFTING") || animationName.startsWith("GEM_CUTTING") || animationName.startsWith("CHURN_MILK"))
		{
			return "Crafting";
		}
		if (animationName.startsWith("HERBLORE"))
		{
			return "Herblore";
		}
		if (animationName.startsWith("FIREMAKING"))
		{
			return "Firemaking";
		}
		if (animationName.startsWith("MAGIC"))
		{
			return "Casting Magic";
		}
		if (animationName.startsWith("AGILITY"))
		{
			return "Agility";
		}
		if (animationName.startsWith("THIEVING"))
		{
			return "Thieving";
		}
		if (animationName.startsWith("HUNTER"))
		{
			return "Hunter";
		}
		if (animationName.startsWith("RUNECRAFT"))
		{
			return "Runecraft";
		}
		if (animationName.startsWith("CONSTRUCTION") || animationName.startsWith("HOME_MAKE_TABLET") || animationName.startsWith("PISCARILIUS_CRANE_REPAIR"))
		{
			return "Construction";
		}
		if (animationName.startsWith("FARMING"))
		{
			return "Farming";
		}
		if (animationName.startsWith("BURYING_BONES") || animationName.startsWith("USING_GILDED_ALTAR") || animationName.startsWith("ECTOFUNTUS"))
		{
			return "Prayer";
		}
		if (animationName.startsWith("DIG"))
		{
			return "Digging";
		}
		return "";
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
