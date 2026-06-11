package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedPendingClaim
{
	private String type;
	private String rank;

	@SerializedName("requested_join_date")
	private String requestedJoinDate;

	@SerializedName("created_at")
	private String createdAt;

	public String getType()
	{
		return type;
	}

	public String getRank()
	{
		return rank;
	}

	public String getRequestedJoinDate()
	{
		return requestedJoinDate;
	}

	public String getCreatedAt()
	{
		return createdAt;
	}
}
