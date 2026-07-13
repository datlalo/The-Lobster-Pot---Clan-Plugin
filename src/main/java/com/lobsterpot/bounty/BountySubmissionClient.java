package com.lobsterpot.bounty;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.lobsterpot.Backend;
import java.io.IOException;
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
 * Posts bounty completion submissions to the LobsterPot backend. The member's RSN is self-asserted
 * (same trust model as world-map positions); the backend gates on clan membership and queues the
 * submission for the Discord bot to pull and record pending admin approval.
 */
@Singleton
@Slf4j
public class BountySubmissionClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public BountySubmissionClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	public void submit(String rsn, String bountyId, String note, SubmitCallback callback)
	{
		final HttpUrl url = HttpUrl.parse(Backend.URL + "/bounty");
		if (url == null)
		{
			callback.onFailure("Could not reach the bounty backend.");
			return;
		}

		final RequestBody body = RequestBody.create(JSON, gson.toJson(new BountySubmission(rsn, bountyId, note)));
		final Request request = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.post(body)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not submit bounty.");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody responseBody = response.body())
				{
					BountySubmissionResult result = null;
					if (responseBody != null)
					{
						try
						{
							result = gson.fromJson(responseBody.charStream(), BountySubmissionResult.class);
						}
						catch (JsonSyntaxException ex)
						{
							log.debug("[LobsterPot] bad bounty response body", ex);
						}
					}

					if (response.isSuccessful() && result != null)
					{
						callback.onSuccess(result);
						return;
					}

					callback.onFailure(errorMessage(response.code(), result));
				}
			}
		});
	}

	private static String errorMessage(int code, BountySubmissionResult result)
	{
		if (result != null && result.getMessage() != null && !result.getMessage().trim().isEmpty())
		{
			return result.getMessage().trim();
		}
		switch (code)
		{
			case 409:
				return "You've already submitted this bounty.";
			case 403:
				return "Only LobsterPot members can submit bounties.";
			case 429:
				return "You're submitting too quickly - try again shortly.";
			case 400:
				return "That bounty submission was invalid.";
			default:
				return "Could not submit bounty (HTTP " + code + ").";
		}
	}

	public interface SubmitCallback
	{
		void onSuccess(BountySubmissionResult result);

		void onFailure(String error);
	}
}
