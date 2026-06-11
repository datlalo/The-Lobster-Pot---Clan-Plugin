package com.lobsterpot;

import java.time.LocalDate;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;

@Singleton
public class ClanMembershipService
{
	public static final String REQUIRED_CLAN_NAME = "LobsterPot";

	private static final String REQUIRED_CLAN_KEY = clanKey(REQUIRED_CLAN_NAME);

	private final Client client;

	@Inject
	public ClanMembershipService(Client client)
	{
		this.client = client;
	}

	public ClanAccess checkAccess()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return ClanAccess.denied("Log into Old School RuneScape to check clan membership.", null, null, null, null);
		}

		final Player localPlayer = client.getLocalPlayer();
		final String playerName = localPlayer == null ? null : localPlayer.getName();
		final ClanSettings settings = client.getClanSettings();
		final ClanChannel channel = client.getClanChannel();

		final String settingsName = nameOf(settings);
		final String channelName = nameOf(channel);
		final String clanName = firstNonBlank(settingsName, channelName);

		if (!isRequiredClanName(settingsName) && !isRequiredClanName(channelName))
		{
			if (clanName == null)
			{
				return ClanAccess.denied("Join the LobsterPot clan to use this plugin.", playerName, null, null, null);
			}

			return ClanAccess.denied("This plugin is only for LobsterPot clan members.", playerName, clanName, null, null);
		}

		final ClanMember settingsMember = findMember(settings, playerName);
		final ClanChannelMember channelMember = findMember(channel, playerName);
		final ClanRank rank = settingsMember != null ? settingsMember.getRank()
			: channelMember != null ? channelMember.getRank() : null;
		final String rankName = rankName(settings, rank);
		final LocalDate joinDate = settingsMember == null ? null : settingsMember.getJoinDate();

		return ClanAccess.allowed("Allowed: LobsterPot clan member.", playerName, firstNonBlank(clanName, REQUIRED_CLAN_NAME),
			rankName, joinDate);
	}

	static boolean isRequiredClanName(@Nullable String clanName)
	{
		return REQUIRED_CLAN_KEY.equals(clanKey(clanName));
	}

	private static String clanKey(@Nullable String clanName)
	{
		if (clanName == null)
		{
			return "";
		}

		final StringBuilder sb = new StringBuilder(clanName.length());
		for (int i = 0; i < clanName.length(); i++)
		{
			final char c = clanName.charAt(i);
			if (Character.isLetterOrDigit(c))
			{
				sb.append(Character.toLowerCase(c));
			}
		}
		return sb.toString();
	}

	@Nullable
	private static ClanMember findMember(@Nullable ClanSettings settings, @Nullable String playerName)
	{
		if (settings == null || playerName == null)
		{
			return null;
		}

		final ClanMember direct = settings.findMember(playerName);
		if (direct != null)
		{
			return direct;
		}

		final String playerKey = playerKey(playerName);
		for (ClanMember member : settings.getMembers())
		{
			if (member != null && playerKey.equals(playerKey(member.getName())))
			{
				return member;
			}
		}
		return null;
	}

	@Nullable
	private static ClanChannelMember findMember(@Nullable ClanChannel channel, @Nullable String playerName)
	{
		if (channel == null || playerName == null)
		{
			return null;
		}

		final ClanChannelMember direct = channel.findMember(playerName);
		if (direct != null)
		{
			return direct;
		}

		final String playerKey = playerKey(playerName);
		for (ClanChannelMember member : channel.getMembers())
		{
			if (member != null && playerKey.equals(playerKey(member.getName())))
			{
				return member;
			}
		}
		return null;
	}

	private static String playerKey(String name)
	{
		return name.replace('\u00A0', ' ').trim().toLowerCase(Locale.ENGLISH);
	}

	@Nullable
	private static String rankName(@Nullable ClanSettings settings, @Nullable ClanRank rank)
	{
		if (rank == null)
		{
			return null;
		}

		if (settings != null)
		{
			final ClanTitle title = settings.titleForRank(rank);
			if (title != null && title.getName() != null && !title.getName().trim().isEmpty())
			{
				return title.getName().trim();
			}
		}

		if (ClanRank.OWNER.equals(rank))
		{
			return "Owner";
		}
		if (ClanRank.DEPUTY_OWNER.equals(rank))
		{
			return "Deputy Owner";
		}
		if (ClanRank.ADMINISTRATOR.equals(rank))
		{
			return "Administrator";
		}
		if (ClanRank.GUEST.equals(rank))
		{
			return "Guest";
		}
		if (ClanRank.JMOD.equals(rank))
		{
			return "Jagex Moderator";
		}
		return "Rank " + rank.getRank();
	}

	@Nullable
	private static String nameOf(@Nullable ClanSettings settings)
	{
		return settings == null || settings.getName() == null || settings.getName().trim().isEmpty()
			? null : settings.getName().trim();
	}

	@Nullable
	private static String nameOf(@Nullable ClanChannel channel)
	{
		return channel == null || channel.getName() == null || channel.getName().trim().isEmpty()
			? null : channel.getName().trim();
	}

	@Nullable
	private static String firstNonBlank(@Nullable String first, @Nullable String second)
	{
		if (first != null && !first.trim().isEmpty())
		{
			return first;
		}
		if (second != null && !second.trim().isEmpty())
		{
			return second;
		}
		return null;
	}

	public static final class ClanAccess
	{
		private final boolean allowed;
		private final String message;
		private final String playerName;
		private final String clanName;
		private final String rankName;
		private final LocalDate joinDate;

		private ClanAccess(boolean allowed, String message, @Nullable String playerName, @Nullable String clanName,
			@Nullable String rankName, @Nullable LocalDate joinDate)
		{
			this.allowed = allowed;
			this.message = message;
			this.playerName = playerName;
			this.clanName = clanName;
			this.rankName = rankName;
			this.joinDate = joinDate;
		}

		private static ClanAccess allowed(String message, @Nullable String playerName, @Nullable String clanName,
			@Nullable String rankName, @Nullable LocalDate joinDate)
		{
			return new ClanAccess(true, message, playerName, clanName, rankName, joinDate);
		}

		private static ClanAccess denied(String message, @Nullable String playerName, @Nullable String clanName,
			@Nullable String rankName, @Nullable LocalDate joinDate)
		{
			return new ClanAccess(false, message, playerName, clanName, rankName, joinDate);
		}

		public boolean isAllowed()
		{
			return allowed;
		}

		public String getMessage()
		{
			return message;
		}

		@Nullable
		public String getPlayerName()
		{
			return playerName;
		}

		@Nullable
		public String getClanName()
		{
			return clanName;
		}

		@Nullable
		public String getRankName()
		{
			return rankName;
		}

		@Nullable
		public LocalDate getJoinDate()
		{
			return joinDate;
		}
	}
}
