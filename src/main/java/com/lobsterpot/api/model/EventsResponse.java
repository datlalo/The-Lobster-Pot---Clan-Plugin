package com.lobsterpot.api.model;

import java.util.List;
import lombok.Data;

/** Wrapper for {@code GET /events}. */
@Data
public class EventsResponse
{
	private List<ClanEvent> events;
}
