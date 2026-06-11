package com.lobsterpot.feed;

import com.google.gson.annotations.SerializedName;

public class FeedNextRank
{
	private String name;

	@SerializedName("point_cost")
	private Integer pointCost;

	@SerializedName("min_months")
	private Integer minMonths;

	private String requirements;

	@SerializedName("missing_points")
	private Integer missingPoints;

	@SerializedName("missing_months")
	private Integer missingMonths;

	@SerializedName("can_claim")
	private Boolean canClaim;

	public String getName()
	{
		return name;
	}

	public Integer getPointCost()
	{
		return pointCost;
	}

	public Integer getMinMonths()
	{
		return minMonths;
	}

	public String getRequirements()
	{
		return requirements;
	}

	public Integer getMissingPoints()
	{
		return missingPoints;
	}

	public Integer getMissingMonths()
	{
		return missingMonths;
	}

	public Boolean getCanClaim()
	{
		return canClaim;
	}
}
