package com.lobsterpot.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.lobsterpot.LobsterPotConfig;
import com.lobsterpot.SessionManager;
import com.lobsterpot.api.model.Broadcast;
import com.lobsterpot.api.model.BroadcastsResponse;
import com.lobsterpot.api.model.ClanEvent;
import com.lobsterpot.api.model.EventsResponse;
import com.lobsterpot.api.model.LoginResponse;
import com.lobsterpot.api.model.NewsPost;
import com.lobsterpot.api.model.NewsResponse;
import com.lobsterpot.api.model.Profile;
import com.lobsterpot.api.model.RankAvailable;
import com.lobsterpot.api.model.RankClaimResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Async client for the clan's public member API. Authenticated endpoints use the JWT held by
 * {@link SessionManager}. Ships no secrets: the only credential is the member's own login, which is
 * exchanged for a token and never persisted as a password.
 */
@Slf4j
@Singleton
public class LobsterPotApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final LobsterPotConfig config;
	private final SessionManager session;

	@Inject
	public LobsterPotApiClient(OkHttpClient httpClient, Gson gson, LobsterPotConfig config, SessionManager session)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
		this.session = session;
	}

	public boolean isConfigured()
	{
		return baseUrl() != null;
	}

	// ------------------------------------------------------------------ endpoints

	/** POST /login (unauthenticated) */
	public void login(String username, String password, ApiCallback<LoginResponse> callback)
	{
		final HttpUrl base = requireBase(callback);
		if (base == null)
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("username", username);
		body.addProperty("password", password);

		final Request request = new Request.Builder()
			.url(base.newBuilder().addPathSegment("login").build())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		send(request, false, LoginResponse.class, callback);
	}

	/** GET /me */
	public void getProfile(ApiCallback<Profile> callback)
	{
		authedGet(callback, Profile.class, "me");
	}

	/** GET /rank/available */
	public void getRankAvailable(ApiCallback<RankAvailable> callback)
	{
		authedGet(callback, RankAvailable.class, "rank", "available");
	}

	/** GET /events?upcoming=true */
	public void getUpcomingEvents(ApiCallback<List<ClanEvent>> callback)
	{
		final HttpUrl base = requireBase(callback);
		if (base == null)
		{
			return;
		}
		final HttpUrl url = base.newBuilder()
			.addPathSegment("events")
			.addQueryParameter("upcoming", "true")
			.build();
		send(authed(new Request.Builder().url(url).get(), callback), true, EventsResponse.class,
			new ApiCallback<EventsResponse>()
			{
				@Override
				public void onSuccess(EventsResponse result)
				{
					callback.onSuccess(result == null || result.getEvents() == null
						? Collections.emptyList() : result.getEvents());
				}

				@Override
				public void onFailure(String error, int httpCode)
				{
					callback.onFailure(error, httpCode);
				}
			});
	}

	/** GET /news */
	public void getNews(ApiCallback<List<NewsPost>> callback)
	{
		final HttpUrl base = requireBase(callback);
		if (base == null)
		{
			return;
		}
		final HttpUrl url = base.newBuilder().addPathSegment("news").build();
		send(authed(new Request.Builder().url(url).get(), callback), true, NewsResponse.class,
			new ApiCallback<NewsResponse>()
			{
				@Override
				public void onSuccess(NewsResponse result)
				{
					callback.onSuccess(result == null || result.getNews() == null
						? Collections.emptyList() : result.getNews());
				}

				@Override
				public void onFailure(String error, int httpCode)
				{
					callback.onFailure(error, httpCode);
				}
			});
	}

	/** GET /broadcasts (active broadcasts; the MOTD shown on login) */
	public void getActiveBroadcasts(ApiCallback<List<Broadcast>> callback)
	{
		final HttpUrl base = requireBase(callback);
		if (base == null)
		{
			return;
		}
		final HttpUrl url = base.newBuilder().addPathSegment("broadcasts").build();
		send(authed(new Request.Builder().url(url).get(), callback), true, BroadcastsResponse.class,
			new ApiCallback<BroadcastsResponse>()
			{
				@Override
				public void onSuccess(BroadcastsResponse result)
				{
					callback.onSuccess(result == null || result.getBroadcasts() == null
						? Collections.emptyList() : result.getBroadcasts());
				}

				@Override
				public void onFailure(String error, int httpCode)
				{
					callback.onFailure(error, httpCode);
				}
			});
	}

	/** POST /rank/claim {@code {claim_text}} */
	public void submitRankClaim(String claimText, ApiCallback<RankClaimResponse> callback)
	{
		final HttpUrl base = requireBase(callback);
		if (base == null)
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("claim_text", claimText);

		final Request.Builder rb = authed(new Request.Builder()
			.url(base.newBuilder().addPathSegment("rank").addPathSegment("claim").build())
			.post(RequestBody.create(JSON, gson.toJson(body))), callback);
		send(rb, true, RankClaimResponse.class, callback);
	}

	// ------------------------------------------------------------------ plumbing

	private <T> void authedGet(ApiCallback<T> callback, Type type, String... segments)
	{
		final HttpUrl base = requireBase(callback);
		if (base == null)
		{
			return;
		}
		HttpUrl.Builder b = base.newBuilder();
		for (String s : segments)
		{
			b.addPathSegment(s);
		}
		send(authed(new Request.Builder().url(b.build()).get(), callback), true, type, callback);
	}

	/**
	 * Adds the bearer header, or returns null (and fails the callback) when there is no valid
	 * session. A null return must short-circuit the caller.
	 */
	@Nullable
	private Request.Builder authed(Request.Builder rb, ApiCallback<?> callback)
	{
		final String token = session.getAccessToken();
		if (token == null || token.isEmpty() || session.isExpired())
		{
			session.clear();
			callback.onFailure("Your session has expired. Please log in again.", 401);
			return null;
		}
		return rb.header("Authorization", "Bearer " + token);
	}

	private <T> void send(@Nullable Request.Builder rb, boolean authed, Type type, ApiCallback<T> callback)
	{
		if (rb == null)
		{
			// authed() already failed the callback.
			return;
		}
		send(rb.build(), authed, type, callback);
	}

	private <T> void send(Request request, boolean authed, Type type, ApiCallback<T> callback)
	{
		enqueue(request, new BodyHandler()
		{
			@Override
			public void onBody(@Nullable String json, int code)
			{
				try
				{
					callback.onSuccess(parse(json, type));
				}
				catch (JsonSyntaxException e)
				{
					log.debug("parse failed for {}", request.url(), e);
					callback.onFailure("Unexpected response from the clan API.", code);
				}
			}

			@Override
			public void onError(String message, int code)
			{
				if (authed && code == 401)
				{
					session.clear();
					callback.onFailure("Your session has expired. Please log in again.", 401);
					return;
				}
				callback.onFailure(message, code);
			}
		});
	}

	private void enqueue(Request request, BodyHandler handler)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("request to {} failed", request.url(), e);
				handler.onError("Could not reach the clan API. Check your connection.", -1);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody responseBody = response.body())
				{
					final String body = responseBody != null ? responseBody.string() : null;
					if (response.isSuccessful())
					{
						handler.onBody(body, response.code());
						return;
					}
					if (response.code() == 429)
					{
						handler.onError("Rate limit exceeded — please wait a moment.", 429);
						return;
					}
					handler.onError(extractError(body, response.code()), response.code());
				}
				catch (IOException e)
				{
					log.debug("reading response from {} failed", request.url(), e);
					handler.onError("Unexpected response from the clan API.", response.code());
				}
			}
		});
	}

	/** Maps the {@code {"error": "..."}} body to a display message, falling back to the status code. */
	private String extractError(@Nullable String body, int code)
	{
		if (body != null && !body.trim().isEmpty())
		{
			try
			{
				final JsonElement el = new JsonParser().parse(body);
				if (el.isJsonObject() && el.getAsJsonObject().has("error"))
				{
					final String message = el.getAsJsonObject().get("error").getAsString();
					if (message != null && !message.isEmpty())
					{
						return message;
					}
				}
			}
			catch (RuntimeException ignored)
			{
				// fall through to generic message
			}
		}
		if (code == 401)
		{
			return "Not authorized — please log in.";
		}
		return "Request failed (HTTP " + code + ").";
	}

	@Nullable
	private <T> T parse(@Nullable String json, Type type)
	{
		if (isBlankJson(json))
		{
			return null;
		}
		return gson.fromJson(json.trim(), type);
	}

	private static boolean isBlankJson(@Nullable String json)
	{
		if (json == null)
		{
			return true;
		}
		final String trimmed = json.trim();
		return trimmed.isEmpty() || "null".equals(trimmed);
	}

	@Nullable
	private HttpUrl baseUrl()
	{
		final String configured = config.apiBaseUrl();
		if (configured == null || configured.trim().isEmpty())
		{
			return null;
		}
		String trimmed = configured.trim();
		while (trimmed.endsWith("/"))
		{
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return HttpUrl.parse(trimmed);
	}

	@Nullable
	private HttpUrl requireBase(ApiCallback<?> callback)
	{
		final HttpUrl base = baseUrl();
		if (base == null)
		{
			callback.onFailure("Clan API URL is not configured. Set it in the plugin settings.", -1);
		}
		return base;
	}

	private interface BodyHandler
	{
		void onBody(@Nullable String json, int code);

		void onError(String message, int code);
	}
}
