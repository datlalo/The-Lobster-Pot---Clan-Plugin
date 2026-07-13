package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedBounty
{
	private String id;
	private String name;
	private Integer points;
	private String description;
	private Boolean active;

	@SerializedName("expires_at")
	private String expiresAt;

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public Integer getPoints()
	{
		return points;
	}

	public String getDescription()
	{
		return description;
	}

	public Boolean getActive()
	{
		return active;
	}

	public String getExpiresAt()
	{
		return expiresAt;
	}
}
