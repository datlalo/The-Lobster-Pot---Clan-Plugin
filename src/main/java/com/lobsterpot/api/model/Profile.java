package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Data;

/** The authenticated member's profile, from {@code GET /me}. */
@Data
public class Profile
{
	private String id;
	private String username;
	private String rsn;

	@SerializedName("discord_id")
	private String discordId;

	@SerializedName("join_date")
	private String joinDate;

	@SerializedName("points_total")
	private int pointsTotal;

	@SerializedName("points_spent")
	private int pointsSpent;

	@SerializedName("points_available")
	private int pointsAvailable;

	@SerializedName("current_rank")
	private RankInfo currentRank;

	@SerializedName("linked_rsns")
	private List<LinkedRsn> linkedRsns;

	@Data
	public static class LinkedRsn
	{
		private String id;
		private String rsn;

		@SerializedName("is_primary")
		private boolean primary;
	}
}
