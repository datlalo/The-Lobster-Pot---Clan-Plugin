package com.lobsterpot.bounty;

/**
 * Response body returned by the backend's {@code POST /bounty} endpoint. {@code status} is one of
 * {@code submitted} / {@code already_submitted} (and error variants); {@code message} is optional
 * human-readable detail.
 */
public class BountySubmissionResult
{
	private String status;
	private String message;

	public String getStatus()
	{
		return status;
	}

	public String getMessage()
	{
		return message;
	}
}
