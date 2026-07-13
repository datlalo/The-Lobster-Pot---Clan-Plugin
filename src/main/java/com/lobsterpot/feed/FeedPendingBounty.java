package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedPendingBounty
{
	@SerializedName("bounty_id")
	private String bountyId;

	private String name;
	private String status;

	@SerializedName("created_at")
	private String createdAt;

	public String getBountyId()
	{
		return bountyId;
	}

	public String getName()
	{
		return name;
	}

	public String getStatus()
	{
		return status;
	}

	public String getCreatedAt()
	{
		return createdAt;
	}
}
