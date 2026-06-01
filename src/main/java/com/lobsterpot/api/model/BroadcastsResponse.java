package com.lobsterpot.api.model;

import java.util.List;
import lombok.Data;

/** Wrapper for {@code GET /broadcasts}. */
@Data
public class BroadcastsResponse
{
	private List<Broadcast> broadcasts;
}
