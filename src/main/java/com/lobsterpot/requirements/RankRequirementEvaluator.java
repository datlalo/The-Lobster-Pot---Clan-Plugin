package com.lobsterpot.requirements;

import com.lobsterpot.feed.FeedNextRank;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;

@Singleton
public class RankRequirementEvaluator
{
	private static final InventoryID[] ITEM_CONTAINERS = {
		InventoryID.INVENTORY,
		InventoryID.EQUIPMENT,
		InventoryID.BANK
	};

	private static final int[] FIRE_CAPE_ITEMS = {
		ItemID.FIRE_CAPE,
		ItemID.FIRE_CAPE_10566,
		ItemID.FIRE_CAPE_L,
		ItemID.FIRE_CAPE_BROKEN,
		ItemID.FIRE_MAX_CAPE,
		ItemID.FIRE_MAX_CAPE_21186,
		ItemID.FIRE_MAX_CAPE_L,
		ItemID.FIRE_MAX_CAPE_BROKEN,
		ItemID.INFERNAL_CAPE,
		ItemID.INFERNAL_CAPE_21297,
		ItemID.INFERNAL_CAPE_23622,
		ItemID.INFERNAL_CAPE_L,
		ItemID.INFERNAL_CAPE_BROKEN,
		ItemID.INFERNAL_MAX_CAPE,
		ItemID.INFERNAL_MAX_CAPE_21285,
		ItemID.INFERNAL_MAX_CAPE_L,
		ItemID.INFERNAL_MAX_CAPE_BROKEN
	};

	private static final int[] BARROWS_GLOVE_ITEMS = {
		ItemID.BARROWS_GLOVES,
		ItemID.BARROWS_GLOVES_23593,
		ItemID.BARROWS_GLOVES_WRAPPED
	};

	private static final int[] QUEST_CAPE_ITEMS = {
		ItemID.QUEST_POINT_CAPE,
		ItemID.QUEST_POINT_CAPE_T
	};

	private static final int[] TRIMMED_QUEST_CAPE_ITEMS = {
		ItemID.QUEST_POINT_CAPE_T
	};

	private static final int[] MAX_CAPE_ITEMS = {
		ItemID.MAX_CAPE,
		ItemID.MAX_CAPE_13342,
		ItemID.FIRE_MAX_CAPE,
		ItemID.FIRE_MAX_CAPE_21186,
		ItemID.FIRE_MAX_CAPE_L,
		ItemID.FIRE_MAX_CAPE_BROKEN,
		ItemID.INFERNAL_MAX_CAPE,
		ItemID.INFERNAL_MAX_CAPE_21285,
		ItemID.INFERNAL_MAX_CAPE_L,
		ItemID.INFERNAL_MAX_CAPE_BROKEN,
		ItemID.SARADOMIN_MAX_CAPE,
		ItemID.ZAMORAK_MAX_CAPE,
		ItemID.GUTHIX_MAX_CAPE,
		ItemID.ACCUMULATOR_MAX_CAPE,
		ItemID.ARDOUGNE_MAX_CAPE,
		ItemID.IMBUED_SARADOMIN_MAX_CAPE,
		ItemID.IMBUED_ZAMORAK_MAX_CAPE,
		ItemID.IMBUED_GUTHIX_MAX_CAPE,
		ItemID.IMBUED_SARADOMIN_MAX_CAPE_L,
		ItemID.IMBUED_ZAMORAK_MAX_CAPE_L,
		ItemID.IMBUED_GUTHIX_MAX_CAPE_L,
		ItemID.IMBUED_SARADOMIN_MAX_CAPE_BROKEN,
		ItemID.IMBUED_ZAMORAK_MAX_CAPE_BROKEN,
		ItemID.IMBUED_GUTHIX_MAX_CAPE_BROKEN,
		ItemID.ASSEMBLER_MAX_CAPE,
		ItemID.ASSEMBLER_MAX_CAPE_L,
		ItemID.ASSEMBLER_MAX_CAPE_BROKEN,
		ItemID.MYTHICAL_MAX_CAPE,
		ItemID.MASORI_ASSEMBLER_MAX_CAPE,
		ItemID.MASORI_ASSEMBLER_MAX_CAPE_L,
		ItemID.MASORI_ASSEMBLER_MAX_CAPE_BROKEN,
		ItemID.DIZANAS_MAX_CAPE,
		ItemID.DIZANAS_MAX_CAPE_L,
		ItemID.DIZANAS_MAX_CAPE_BROKEN
	};

	@Inject
	private Client client;

	@Inject
	private HiscoreClient hiscoreClient;

	public RankRequirementEvaluation evaluateLocal(FeedNextRank nextRank)
	{
		if (nextRank == null)
		{
			return null;
		}

		final String requirement = requirementText(nextRank);
		if (!hasText(requirement))
		{
			return null;
		}

		final String requirementKey = normalize(requirement);
		if (isDiamondRequirement(nextRank, requirementKey))
		{
			return evaluateFireCape(requirement);
		}
		if (isDragonstoneRequirement(nextRank, requirementKey))
		{
			return evaluateBarrowsGloves(requirement);
		}
		if (isOnyxRequirement(nextRank, requirementKey))
		{
			return evaluateOnyx(requirement);
		}
		if (isZenyteRequirement(nextRank, requirementKey))
		{
			return evaluateZenyte(requirement);
		}
		if (isLegacyVeteranRequirement(nextRank, requirementKey))
		{
			return evaluateLegacyVeteran(requirement);
		}

		return RankRequirementEvaluation.unknown(requirement, requirement + " not verified");
	}

	public boolean shouldCheckJadKillCount(FeedNextRank nextRank, RankRequirementEvaluation evaluation)
	{
		return nextRank != null
			&& evaluation != null
			&& evaluation.getStatus() != RankRequirementEvaluation.Status.MET
			&& isDiamondRequirement(nextRank, normalize(requirementText(nextRank)));
	}

	public CompletableFuture<RankRequirementEvaluation> evaluateJadKillCount(String playerName, FeedNextRank nextRank)
	{
		final String requirement = requirementText(nextRank);
		final HiscoreEndpoint endpoint = hiscoreEndpoint();
		return hiscoreClient.lookupAsync(playerName, endpoint)
			.thenApply(result -> evaluateJadResult(requirement, result));
	}

	private RankRequirementEvaluation evaluateFireCape(String requirement)
	{
		if (hasAnyItem(FIRE_CAPE_ITEMS))
		{
			return RankRequirementEvaluation.met(requirement, "Fire Cape complete");
		}
		return RankRequirementEvaluation.unknown(requirement, "Checking Jad KC...");
	}

	private RankRequirementEvaluation evaluateBarrowsGloves(String requirement)
	{
		if (hasAnyItem(BARROWS_GLOVE_ITEMS)
			|| isQuestFinished(Quest.RECIPE_FOR_DISASTER)
			|| isQuestFinished(Quest.RECIPE_FOR_DISASTER__CULINAROMANCER))
		{
			return RankRequirementEvaluation.met(requirement, "Barrows Gloves complete");
		}
		return RankRequirementEvaluation.missing(requirement, "Barrows Gloves needed");
	}

	private RankRequirementEvaluation evaluateOnyx(String requirement)
	{
		if (hasAnyItem(QUEST_CAPE_ITEMS))
		{
			return RankRequirementEvaluation.met(requirement, "Quest Cape complete");
		}
		if (client.getTotalLevel() >= 1800)
		{
			return RankRequirementEvaluation.met(requirement, "1800 total met");
		}
		if (hasCombatAchievementTier(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD))
		{
			return RankRequirementEvaluation.met(requirement, "Hard CA complete");
		}
		return RankRequirementEvaluation.missing(requirement, "Need Quest Cape, 1800 total, or Hard CA");
	}

	private RankRequirementEvaluation evaluateZenyte(String requirement)
	{
		if (hasAnyItem(TRIMMED_QUEST_CAPE_ITEMS))
		{
			return RankRequirementEvaluation.met(requirement, "Trimmed QC complete");
		}
		if (client.getTotalLevel() >= 2000)
		{
			return RankRequirementEvaluation.met(requirement, "2000 total met");
		}
		if (hasCombatAchievementTier(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE))
		{
			return RankRequirementEvaluation.met(requirement, "Elite CA complete");
		}
		return RankRequirementEvaluation.missing(requirement, "Need Trimmed QC, 2000 total, or Elite CA");
	}

	private RankRequirementEvaluation evaluateLegacyVeteran(String requirement)
	{
		if (hasAnyItem(MAX_CAPE_ITEMS) || hasMaxedSkills())
		{
			return RankRequirementEvaluation.met(requirement, "Max account complete");
		}
		if (hasCombatAchievementTier(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER))
		{
			return RankRequirementEvaluation.met(requirement, "Master CA complete");
		}
		return RankRequirementEvaluation.missing(requirement, "Need Max Account or Master CA");
	}

	private RankRequirementEvaluation evaluateJadResult(String requirement, HiscoreResult result)
	{
		if (hasKillCount(result, HiscoreSkill.TZTOK_JAD) || hasKillCount(result, HiscoreSkill.TZKAL_ZUK))
		{
			return RankRequirementEvaluation.met(requirement, "Jad KC found");
		}
		return RankRequirementEvaluation.missing(requirement, "Fire Cape needed");
	}

	private boolean hasAnyItem(int... itemIds)
	{
		for (InventoryID inventoryID : ITEM_CONTAINERS)
		{
			final ItemContainer container = client.getItemContainer(inventoryID);
			if (container == null)
			{
				continue;
			}

			for (int itemId : itemIds)
			{
				if (container.contains(itemId))
				{
					return true;
				}
			}
		}
		return false;
	}

	private boolean isQuestFinished(Quest quest)
	{
		try
		{
			return quest.getState(client) == QuestState.FINISHED;
		}
		catch (Exception ignored)
		{
			return false;
		}
	}

	private boolean hasCombatAchievementTier(int minimumTierVarbit)
	{
		if (minimumTierVarbit == Varbits.COMBAT_ACHIEVEMENT_TIER_HARD)
		{
			return hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD)
				|| hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE)
				|| hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER)
				|| hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER);
		}
		if (minimumTierVarbit == Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE)
		{
			return hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE)
				|| hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER)
				|| hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER);
		}
		if (minimumTierVarbit == Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER)
		{
			return hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER)
				|| hasVarbit(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER);
		}
		return hasVarbit(minimumTierVarbit);
	}

	private boolean hasVarbit(int varbit)
	{
		try
		{
			return client.getVarbitValue(varbit) > 0;
		}
		catch (Exception ignored)
		{
			return false;
		}
	}

	private boolean hasMaxedSkills()
	{
		boolean foundSkill = false;
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}

			final int level = client.getRealSkillLevel(skill);
			if (level <= 0)
			{
				continue;
			}

			foundSkill = true;
			if (level < 99)
			{
				return false;
			}
		}
		return foundSkill;
	}

	private boolean hasKillCount(HiscoreResult result, HiscoreSkill hiscoreSkill)
	{
		if (result == null)
		{
			return false;
		}

		final net.runelite.client.hiscore.Skill skill = result.getSkill(hiscoreSkill);
		return skill != null && skill.getLevel() > 0;
	}

	private HiscoreEndpoint hiscoreEndpoint()
	{
		try
		{
			final HiscoreEndpoint endpoint = HiscoreEndpoint.fromWorldTypes(client.getWorldType());
			return endpoint == null ? HiscoreEndpoint.NORMAL : endpoint;
		}
		catch (Exception ignored)
		{
			return HiscoreEndpoint.NORMAL;
		}
	}

	private static boolean isDiamondRequirement(FeedNextRank nextRank, String requirementKey)
	{
		return "diamond".equals(normalize(nextRank.getName()))
			|| requirementKey.contains("fire cape");
	}

	private static boolean isDragonstoneRequirement(FeedNextRank nextRank, String requirementKey)
	{
		return "dragonstone".equals(normalize(nextRank.getName()))
			|| requirementKey.contains("barrows gloves");
	}

	private static boolean isOnyxRequirement(FeedNextRank nextRank, String requirementKey)
	{
		return "onyx".equals(normalize(nextRank.getName()))
			|| requirementKey.contains("quest cape")
			|| requirementKey.contains("1800 total")
			|| requirementKey.contains("hard ca");
	}

	private static boolean isZenyteRequirement(FeedNextRank nextRank, String requirementKey)
	{
		return "zenyte".equals(normalize(nextRank.getName()))
			|| requirementKey.contains("trimmed qc")
			|| requirementKey.contains("trimmed quest")
			|| requirementKey.contains("2000 total")
			|| requirementKey.contains("elite ca");
	}

	private static boolean isLegacyVeteranRequirement(FeedNextRank nextRank, String requirementKey)
	{
		return "legacy veteran".equals(normalize(nextRank.getName()))
			|| requirementKey.contains("max account")
			|| requirementKey.contains("master ca");
	}

	private static String requirementText(FeedNextRank nextRank)
	{
		if (nextRank == null)
		{
			return null;
		}

		if (hasText(nextRank.getRequirements()))
		{
			return nextRank.getRequirements().trim();
		}

		final String rankName = normalize(nextRank.getName());
		switch (rankName)
		{
			case "diamond":
				return "Fire Cape";
			case "dragonstone":
				return "Barrows Gloves";
			case "onyx":
				return "Quest Cape, 1800 Total, or Hard CA";
			case "zenyte":
				return "Trimmed QC, 2000 Total, or Elite CA";
			case "legacy veteran":
				return "Max Account or Master CA";
			default:
				return null;
		}
	}

	private static boolean hasText(String value)
	{
		return value != null && !value.trim().isEmpty();
	}

	private static String normalize(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.toLowerCase(Locale.US)
			.replaceAll("[^a-z0-9]+", " ")
			.trim();
	}
}
