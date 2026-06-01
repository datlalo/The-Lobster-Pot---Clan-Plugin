package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** A broadcast (MOTD) from {@code GET /broadcasts} (inside the {@code broadcasts} array). */
@Data
public class Broadcast
{
	private String id;
	private String message;

	@SerializedName("is_active")
	private boolean active;

	@SerializedName("created_at")
	private String createdAt;
}
