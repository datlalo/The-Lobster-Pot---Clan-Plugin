package com.lobsterpot.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** Response from {@code POST /login}. */
@Data
public class LoginResponse
{
	@SerializedName("access_token")
	private String accessToken;

	@SerializedName("refresh_token")
	private String refreshToken;

	/** Token lifetime in seconds. */
	@SerializedName("expires_in")
	private long expiresIn;

	/** Absolute expiry as a unix epoch second. */
	@SerializedName("expires_at")
	private long expiresAt;

	@SerializedName("token_type")
	private String tokenType;

	private User user;
}
