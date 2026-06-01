package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** An event from {@code GET /events} (inside the {@code events} array). */
@Data
public class ClanEvent
{
	private String id;
	private String title;
	private String description;
	private String location;

	@SerializedName("image_url")
	private String imageUrl;

	/** ISO-8601 timestamp, e.g. 2026-06-04T19:00:00Z. */
	@SerializedName("event_start")
	private String eventStart;

	/** ISO-8601 timestamp, may be null. */
	@SerializedName("event_end")
	private String eventEnd;

	@SerializedName("created_at")
	private String createdAt;
}
