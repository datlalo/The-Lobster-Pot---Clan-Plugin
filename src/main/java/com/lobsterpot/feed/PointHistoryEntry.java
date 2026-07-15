package com.lobsterpot.feed;

public class PointHistoryEntry
{
	private String timestamp;
	private Integer delta;
	private String reason;
	private Integer balance;

	public String getTimestamp()
	{
		return timestamp;
	}

	public Integer getDelta()
	{
		return delta;
	}

	public String getReason()
	{
		return reason;
	}

	public Integer getBalance()
	{
		return balance;
	}
}
