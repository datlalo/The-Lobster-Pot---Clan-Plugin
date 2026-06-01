package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import javax.annotation.Nullable;
import lombok.Data;

/** Response from {@code GET /rank/available}. */
@Data
public class RankAvailable
{
	@SerializedName("current_rank")
	private RankInfo currentRank;

	@SerializedName("next_rank")
	private RankInfo nextRank;

	@SerializedName("points_available")
	private int pointsAvailable;

	@SerializedName("eligible_date")
	private String eligibleDate;

	private boolean eligible;

	private Reasons reasons;

	@Nullable
	@SerializedName("pending_claim")
	private PendingClaim pendingClaim;

	@Data
	public static class Reasons
	{
		@SerializedName("enough_points")
		private boolean enoughPoints;

		@SerializedName("enough_time")
		private boolean enoughTime;

		@SerializedName("admin_only")
		private boolean adminOnly;
	}

	@Data
	public static class PendingClaim
	{
		private String id;
		private String status;

		@SerializedName("created_at")
		private String createdAt;
	}
}
