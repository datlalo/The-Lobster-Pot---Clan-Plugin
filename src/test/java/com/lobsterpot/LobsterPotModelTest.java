package com.lobsterpot;

import com.google.gson.Gson;
import com.lobsterpot.api.model.BroadcastsResponse;
import com.lobsterpot.api.model.EventsResponse;
import com.lobsterpot.api.model.LoginResponse;
import com.lobsterpot.api.model.Profile;
import com.lobsterpot.api.model.RankAvailable;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the member-API JSON contract maps onto the model POJOs (snake_case fields, nested
 * ranks, list wrappers).
 */
public class LobsterPotModelTest
{
	private final Gson gson = new Gson();

	@Test
	public void parsesLogin()
	{
		final String json = "{\"access_token\":\"abc\",\"refresh_token\":\"ref\",\"expires_in\":3600,"
			+ "\"expires_at\":1735000000,\"token_type\":\"bearer\",\"user\":{\"id\":\"u1\",\"username\":\"larry\"}}";
		final LoginResponse r = gson.fromJson(json, LoginResponse.class);
		assertEquals("abc", r.getAccessToken());
		assertEquals(3600, r.getExpiresIn());
		assertEquals(1735000000L, r.getExpiresAt());
		assertEquals("larry", r.getUser().getUsername());
	}

	@Test
	public void parsesProfile()
	{
		final String json = "{\"id\":\"u1\",\"username\":\"larry\",\"rsn\":\"Larry\",\"join_date\":\"2024-03-12\","
			+ "\"points_total\":1500,\"points_spent\":250,\"points_available\":1250,"
			+ "\"current_rank\":{\"id\":\"r3\",\"name\":\"Lobster\",\"order_index\":3,\"point_cost\":250,\"min_months\":3},"
			+ "\"linked_rsns\":[{\"id\":\"m1\",\"rsn\":\"Larry\",\"is_primary\":true}]}";
		final Profile p = gson.fromJson(json, Profile.class);
		assertEquals(1250, p.getPointsAvailable());
		assertEquals("Lobster", p.getCurrentRank().getName());
		assertEquals(3, p.getCurrentRank().getOrderIndex());
		assertEquals(1, p.getLinkedRsns().size());
		assertTrue(p.getLinkedRsns().get(0).isPrimary());
	}

	@Test
	public void parsesRankAvailable()
	{
		final String json = "{\"current_rank\":{\"name\":\"Lobster\",\"order_index\":3},"
			+ "\"next_rank\":{\"name\":\"Crab\",\"order_index\":4,\"point_cost\":500,\"min_months\":6,"
			+ "\"requirements_description\":\"Be active for 6 months\",\"admin_only\":false},"
			+ "\"points_available\":1250,\"eligible_date\":\"2024-09-12T00:00:00Z\",\"eligible\":true,"
			+ "\"reasons\":{\"enough_points\":true,\"enough_time\":true,\"admin_only\":false},\"pending_claim\":null}";
		final RankAvailable r = gson.fromJson(json, RankAvailable.class);
		assertTrue(r.isEligible());
		assertEquals("Crab", r.getNextRank().getName());
		assertEquals(Integer.valueOf(500), r.getNextRank().getPointCost());
		assertTrue(r.getReasons().isEnoughPoints());
		assertNull(r.getPendingClaim());
	}

	@Test
	public void parsesEventsWrapper()
	{
		final String json = "{\"events\":[{\"id\":\"e1\",\"title\":\"Wildy Wednesday\",\"location\":\"World 458\","
			+ "\"event_start\":\"2026-06-04T19:00:00Z\"}]}";
		final EventsResponse r = gson.fromJson(json, EventsResponse.class);
		assertNotNull(r.getEvents());
		assertEquals(1, r.getEvents().size());
		assertEquals("Wildy Wednesday", r.getEvents().get(0).getTitle());
		assertFalse(r.getEvents().isEmpty());
	}

	@Test
	public void parsesBroadcastsWrapper()
	{
		final String json = "{\"broadcasts\":[{\"id\":\"b1\",\"message\":\"Boss mass tonight at 8 PM!\","
			+ "\"is_active\":true,\"created_at\":\"2026-06-01T10:00:00Z\"}]}";
		final BroadcastsResponse r = gson.fromJson(json, BroadcastsResponse.class);
		assertNotNull(r.getBroadcasts());
		assertEquals(1, r.getBroadcasts().size());
		assertEquals("Boss mass tonight at 8 PM!", r.getBroadcasts().get(0).getMessage());
		assertTrue(r.getBroadcasts().get(0).isActive());
	}
}
