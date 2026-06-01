package com.lobsterpot.api.model;

import java.util.List;
import lombok.Data;

/** Wrapper for {@code GET /news}. */
@Data
public class NewsResponse
{
	private List<NewsPost> news;
}
