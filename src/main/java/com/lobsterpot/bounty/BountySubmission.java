package com.lobsterpot.bounty;

/**
 * Request payload POSTed to the backend when a member reports completing a bounty. Serialized to
 * JSON by Gson as {@code {"rsn":...,"bountyId":...,"note":...}}; the worker reads the same keys.
 */
class BountySubmission
{
	final String rsn;
	final String bountyId;
	final String note;

	BountySubmission(String rsn, String bountyId, String note)
	{
		this.rsn = rsn;
		this.bountyId = bountyId;
		this.note = note;
	}
}
