package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** A news post from {@code GET /news} (inside the {@code news} array). */
@Data
public class NewsPost
{
	private String id;
	private String title;
	private String excerpt;
	private String content;

	@SerializedName("image_url")
	private String imageUrl;

	@SerializedName("created_at")
	private String createdAt;

	@SerializedName("updated_at")
	private String updatedAt;
}
