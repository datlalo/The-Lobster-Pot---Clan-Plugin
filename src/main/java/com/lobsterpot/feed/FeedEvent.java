package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedEvent
{
	private String id;
	private String type;
	private String title;
	private String location;
	private String description;
	private String metric;

	@SerializedName("metric_label")
	private String metricLabel;

	@SerializedName("starts_at")
	private String startsAt;

	@SerializedName("ends_at")
	private String endsAt;

	@SerializedName("thread_id")
	private String threadId;

	@SerializedName("wom_id")
	private Integer womId;

	public String getId()
	{
		return id;
	}

	public String getType()
	{
		return type;
	}

	public String getTitle()
	{
		return title;
	}

	public String getLocation()
	{
		return location;
	}

	public String getDescription()
	{
		return description;
	}

	public String getMetric()
	{
		return metric;
	}

	public String getMetricLabel()
	{
		return metricLabel;
	}

	public String getStartsAt()
	{
		return startsAt;
	}

	public String getEndsAt()
	{
		return endsAt;
	}

	public String getThreadId()
	{
		return threadId;
	}

	public Integer getWomId()
	{
		return womId;
	}
}
