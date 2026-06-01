package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * A rank, as embedded in {@code /me} and {@code /rank/available}. Fields not present in a given
 * context are left null/zero by Gson.
 */
@Data
public class RankInfo
{
	private String id;
	private String name;

	@SerializedName("order_index")
	private int orderIndex;

	@SerializedName("point_cost")
	private Integer pointCost;

	@SerializedName("min_months")
	private Integer minMonths;

	@SerializedName("requirements_description")
	private String requirementsDescription;

	@SerializedName("admin_only")
	private boolean adminOnly;
}
