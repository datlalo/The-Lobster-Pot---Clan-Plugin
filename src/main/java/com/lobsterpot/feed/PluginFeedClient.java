package com.lobsterpot.feed;

import com.google.gson.Gson;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public class PluginFeedClient
{
	private static final String PLUGIN_FEED_URL = "https://raw.githubusercontent.com/datlalo/lobsterpot-plugin-feed/refs/heads/main/plugin-feed.json";

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public PluginFeedClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	public void fetch(FeedCallback callback)
	{
		final Request request = new Request.Builder()
			.url(PLUGIN_FEED_URL)
			.header("Accept", "application/json")
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not load plugin feed.");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						callback.onFailure("Plugin feed request failed (HTTP " + response.code() + ").");
						return;
					}

					final PluginFeed feed = gson.fromJson(body.charStream(), PluginFeed.class);
					if (feed == null)
					{
						callback.onFailure("Plugin feed was empty.");
						return;
					}
					callback.onSuccess(feed);
				}
			}
		});
	}

	public interface FeedCallback
	{
		void onSuccess(PluginFeed feed);

		void onFailure(String error);
	}
}
