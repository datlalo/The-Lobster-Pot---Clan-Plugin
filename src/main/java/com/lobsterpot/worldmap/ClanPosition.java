package com.lobsterpot.worldmap;

class ClanPosition
{
	String rsn;
	int x;
	int y;
	int plane;
	int world;
	String activity;

	ClanPosition(String rsn, int x, int y, int plane, int world, String activity)
	{
		this.rsn = rsn;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.world = world;
		this.activity = activity;
	}
}
