package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedBroadcast
{
	private String id;
	private String title;
	private String message;

	@SerializedName("starts_at")
	private String startsAt;

	@SerializedName("expires_at")
	private String expiresAt;

	@SerializedName("updated_at")
	private String updatedAt;

	public String getId()
	{
		return id;
	}

	public String getTitle()
	{
		return title;
	}

	public String getMessage()
	{
		return message;
	}

	public String getStartsAt()
	{
		return startsAt;
	}

	public String getExpiresAt()
	{
		return expiresAt;
	}

	public String getUpdatedAt()
	{
		return updatedAt;
	}
}
