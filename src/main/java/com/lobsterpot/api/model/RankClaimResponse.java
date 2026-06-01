package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** Response from {@code POST /rank/claim} on success (HTTP 201). */
@Data
public class RankClaimResponse
{
	private boolean success;
	private Claim claim;

	@Data
	public static class Claim
	{
		private String id;
		private String status;

		@SerializedName("created_at")
		private String createdAt;

		private RankInfo rank;
	}
}
