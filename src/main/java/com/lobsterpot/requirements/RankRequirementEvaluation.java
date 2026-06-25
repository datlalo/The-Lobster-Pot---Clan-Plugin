package com.lobsterpot.requirements;

public class RankRequirementEvaluation
{
	public enum Status
	{
		MET,
		MISSING,
		UNKNOWN
	}

	private final String requirementText;
	private final Status status;
	private final String message;

	private RankRequirementEvaluation(String requirementText, Status status, String message)
	{
		this.requirementText = requirementText;
		this.status = status;
		this.message = message;
	}

	public static RankRequirementEvaluation met(String requirementText, String message)
	{
		return new RankRequirementEvaluation(requirementText, Status.MET, message);
	}

	public static RankRequirementEvaluation missing(String requirementText, String message)
	{
		return new RankRequirementEvaluation(requirementText, Status.MISSING, message);
	}

	public static RankRequirementEvaluation unknown(String requirementText, String message)
	{
		return new RankRequirementEvaluation(requirementText, Status.UNKNOWN, message);
	}

	public String getRequirementText()
	{
		return requirementText;
	}

	public Status getStatus()
	{
		return status;
	}

	public String getMessage()
	{
		return message;
	}
}
