package com.lobsterpot.feed;

import com.google.gson.Gson;
import com.lobsterpot.LobsterPotConfig;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public class PluginFeedClient
{
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final LobsterPotConfig config;

	@Inject
	public PluginFeedClient(OkHttpClient httpClient, Gson gson, LobsterPotConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	public void fetch(FeedCallback callback)
	{
		String configured = config.pluginFeedUrl();
		if (configured == null || configured.trim().isEmpty())
		{
			configured = LobsterPotConfig.DEFAULT_PLUGIN_FEED_URL;
		}

		final HttpUrl url = HttpUrl.parse(configured.trim());
		if (url == null)
		{
			callback.onFailure("Plugin feed URL is invalid.");
			return;
		}

		final Request request = new Request.Builder()
			.url(url)
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
