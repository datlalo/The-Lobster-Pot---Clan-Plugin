package com.lobsterpot.api;

import javax.annotation.Nullable;

/**
 * Result callback for asynchronous API calls. Callbacks are invoked off the client/Swing thread
 * (on an OkHttp dispatcher thread); implementations are responsible for marshalling back to the
 * correct thread (e.g. {@code SwingUtilities.invokeLater} or {@code ClientThread#invoke}).
 *
 * @param <T> the parsed success payload type
 */
public interface ApiCallback<T>
{
	void onSuccess(@Nullable T result);

	/**
	 * @param error    a human-readable message suitable for display
	 * @param httpCode the HTTP status code, or -1 for a network/transport failure
	 */
	void onFailure(String error, int httpCode);
}
