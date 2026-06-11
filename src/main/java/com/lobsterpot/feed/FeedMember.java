package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedMember
{
	private String rsn;

	@SerializedName("rsn_key")
	private String rsnKey;

	@SerializedName("bot_rank")
	private String botRank;

	@SerializedName("progression_rank")
	private String progressionRank;

	@SerializedName("join_date")
	private String joinDate;

	@SerializedName("points_total")
	private Integer pointsTotal;

	@SerializedName("points_spent")
	private Integer pointsSpent;

	@SerializedName("points_available")
	private Integer pointsAvailable;

	@SerializedName("months_in_clan")
	private Integer monthsInClan;

	@SerializedName("next_rank")
	private FeedNextRank nextRank;

	@SerializedName("pending_claim")
	private FeedPendingClaim pendingClaim;

	@SerializedName("pending_join_date_claim")
	private FeedPendingClaim pendingJoinDateClaim;

	public String getRsn()
	{
		return rsn;
	}

	public String getRsnKey()
	{
		return rsnKey;
	}

	public String getBotRank()
	{
		return botRank;
	}

	public String getProgressionRank()
	{
		return progressionRank;
	}

	public String getJoinDate()
	{
		return joinDate;
	}

	public Integer getPointsTotal()
	{
		return pointsTotal;
	}

	public Integer getPointsSpent()
	{
		return pointsSpent;
	}

	public Integer getPointsAvailable()
	{
		return pointsAvailable;
	}

	public Integer getMonthsInClan()
	{
		return monthsInClan;
	}

	public FeedNextRank getNextRank()
	{
		return nextRank;
	}

	public FeedPendingClaim getPendingClaim()
	{
		return pendingClaim;
	}

	public FeedPendingClaim getPendingJoinDateClaim()
	{
		return pendingJoinDateClaim;
	}
}
