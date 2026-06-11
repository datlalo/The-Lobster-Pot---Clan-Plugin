package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

public class PluginFeed
{
	private int version;

	@SerializedName("generated_at")
	private String generatedAt;

	private List<FeedBroadcast> broadcasts;
	private List<FeedEvent> events;
	private List<FeedMember> members;

	public int getVersion()
	{
		return version;
	}

	public String getGeneratedAt()
	{
		return generatedAt;
	}

	public List<FeedBroadcast> getBroadcasts()
	{
		return broadcasts == null ? Collections.emptyList() : broadcasts;
	}

	public List<FeedEvent> getEvents()
	{
		return events == null ? Collections.emptyList() : events;
	}

	public List<FeedMember> getMembers()
	{
		return members == null ? Collections.emptyList() : members;
	}
}
