package com.lobsterpot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClanMembershipServiceTest
{
	@Test
	public void acceptsLobsterPotClanNameVariants()
	{
		assertTrue(ClanMembershipService.isRequiredClanName("LobsterPot"));
		assertTrue(ClanMembershipService.isRequiredClanName("lobsterpot"));
		assertTrue(ClanMembershipService.isRequiredClanName("Lobster Pot"));
		assertTrue(ClanMembershipService.isRequiredClanName("Lobster-Pot"));
	}

	@Test
	public void rejectsOtherClanNames()
	{
		assertFalse(ClanMembershipService.isRequiredClanName(null));
		assertFalse(ClanMembershipService.isRequiredClanName(""));
		assertFalse(ClanMembershipService.isRequiredClanName("The Lobster Pot"));
		assertFalse(ClanMembershipService.isRequiredClanName("Lobsters"));
	}
}
