package com.lobsterpot.worldmap;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.util.ImageUtil;

class ClanMemberWorldMapPoint extends WorldMapPoint
{
	static final int NO_TITLE = Integer.MIN_VALUE;

	private static final int ICON_HEIGHT = 28;
	private static final int LABEL_GAP = 2;
	private static final BufferedImage FALLBACK_ICON = createFallbackIcon();

	private final String memberName;
	private int titleId = NO_TITLE;
	private int world;
	private String activity;

	ClanMemberWorldMapPoint(WorldPoint point, String memberName, int world, String activity)
	{
		super(point, buildMarker(FALLBACK_ICON, memberName));
		this.memberName = memberName;
		this.world = world;
		this.activity = activity;
		// setName is required by WorldMapOverlay's assertion when jumpOnClick is enabled.
		setName(memberName);
		updateTooltip();
		setSnapToEdge(false);
		setJumpOnClick(false);
	}

	int getTitleId()
	{
		return titleId;
	}

	int getWorld()
	{
		return world;
	}

	String getMemberName()
	{
		return memberName;
	}

	String getActivity()
	{
		return activity;
	}

	void setWorld(int world)
	{
		this.world = world;
		updateTooltip();
	}

	void setActivity(String activity)
	{
		this.activity = activity;
		updateTooltip();
	}

	void applyRank(BufferedImage rankIcon, int titleId)
	{
		this.titleId = titleId;
		final BufferedImage base = rankIcon != null ? scaleIcon(rankIcon) : FALLBACK_ICON;
		setImage(buildMarker(base, memberName));
	}

	private void updateTooltip()
	{
		final String worldText = world > 0 ? "World " + world : "World unknown";
		if (activity != null && !activity.trim().isEmpty() && !"-".equals(activity.trim()))
		{
			setTooltip(memberName + "<br>" + worldText + "<br>" + activity.trim());
			return;
		}
		setTooltip(memberName + "<br>" + worldText);
	}

	private static BufferedImage scaleIcon(BufferedImage icon)
	{
		if (icon.getHeight() <= 0)
		{
			return icon;
		}
		final double ratio = (double) ICON_HEIGHT / icon.getHeight();
		final int width = Math.max(1, (int) Math.round(icon.getWidth() * ratio));
		return ImageUtil.resizeImage(icon, width, ICON_HEIGHT);
	}

	private static BufferedImage buildMarker(BufferedImage base, String name)
	{
		final Font font = FontManager.getRunescapeBoldFont();

		final BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D pg = probe.createGraphics();
		pg.setFont(font);
		final FontMetrics metrics = pg.getFontMetrics();
		final int textWidth = metrics.stringWidth(name);
		final int textHeight = metrics.getHeight();
		final int ascent = metrics.getAscent();
		pg.dispose();

		final int width = Math.max(base.getWidth(), textWidth) + 2;
		final int height = base.getHeight() + LABEL_GAP + textHeight;

		final BufferedImage marker = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = marker.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.drawImage(base, (width - base.getWidth()) / 2, 0, null);

		g.setFont(font);
		final int textX = (width - textWidth) / 2;
		final int textY = base.getHeight() + LABEL_GAP + ascent;
		g.setColor(Color.BLACK);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					g.drawString(name, textX + dx, textY + dy);
				}
			}
		}
		g.setColor(Color.WHITE);
		g.drawString(name, textX, textY);
		g.dispose();
		return marker;
	}

	private static BufferedImage createFallbackIcon()
	{
		final int size = 18;
		final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xFF6B35));
		g.fillOval(0, 0, size - 1, size - 1);
		g.setColor(Color.WHITE);
		g.drawOval(0, 0, size - 1, size - 1);
		g.dispose();
		return img;
	}
}
