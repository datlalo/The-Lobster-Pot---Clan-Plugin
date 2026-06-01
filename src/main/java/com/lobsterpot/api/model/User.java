package com.lobsterpot.api.model;

import lombok.Data;

/** Authenticated user identity returned by {@code POST /login}. */
@Data
public class User
{
	private String id;
	private String username;
}
