package com.lobsterpot.worldmap;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Singleton
public class ClanPositionWorldMapOverlay extends Overlay
{
	private final ClanPositionService clanPositionService;

	@Inject
	ClanPositionWorldMapOverlay(ClanPositionService clanPositionService)
	{
		this.clanPositionService = clanPositionService;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_HIGH);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLD_MAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		clanPositionService.addHoveredMapMenuEntry();
		return null;
	}
}
