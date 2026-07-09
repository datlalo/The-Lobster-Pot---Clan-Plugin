package com.lobsterpot.worldmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Static table of world map "map list" layers and their approximate center coordinates, mirroring
 * the selectable entries in the world map dropdown (RuneLite map ids 0-51, excluding the two unused
 * ids). RuneLite exposes no API to map a coordinate to a map layer, so this lets us resolve the
 * layer that contains a clanmate without blindly scanning every entry: the layer whose center is
 * nearest the target is, in practice, always the correct one because underground/instance areas sit
 * in far-apart, disjoint coordinate blocks.
 *
 * <p>The result is only ever a best guess to try first. Callers must still verify the coordinate is
 * actually on the returned layer (via {@code surfaceContainsPosition}) before panning, so a wrong
 * guess is self-correcting and simply falls through to a full scan.
 */
final class WorldMapLayers
{
	private static final class Layer
	{
		final String name;
		final int x;
		final int y;

		Layer(String name, int x, int y)
		{
			this.name = name;
			this.x = x;
			this.y = y;
		}
	}

	// Names must match the world map dropdown entry text exactly (findEntryByName selects by text).
	// A mismatched name simply won't be selectable and falls back to the scan, so drift is harmless.
	private static final Layer[] LAYERS = {
		new Layer("Gielinor Surface", 2496, 3328),
		new Layer("Ancient Cavern", 1760, 5344),
		new Layer("Ardougne Underground", 2575, 9694),
		new Layer("Asgarnia Ice Cave", 2989, 9566),
		new Layer("Braindeath Island", 2144, 5101),
		new Layer("Dorgesh-Kaan", 2720, 5344),
		new Layer("Dwarven Mines", 3040, 9824),
		new Layer("God Wars Dungeon", 2880, 5312),
		new Layer("Ghorrock Prison", 2935, 6391),
		new Layer("Keldagrim", 2879, 10176),
		new Layer("Misthalin Underground", 3168, 9632),
		new Layer("Mole Hole", 1760, 5183),
		new Layer("Morytania Underground", 3479, 9837),
		new Layer("Mos Le'Harmless Cave", 3775, 9407),
		new Layer("Ourania Altar", 3040, 5600),
		new Layer("Fremennik Slayer Cave", 2784, 10016),
		new Layer("Stronghold of Security", 1888, 5216),
		new Layer("Stronghold Underground", 2432, 9812),
		new Layer("Taverley Underground", 2912, 9824),
		new Layer("Tolna's Rift", 3104, 5280),
		new Layer("Troll Stronghold", 2822, 10087),
		new Layer("Mor Ul Rek", 2489, 5118),
		new Layer("Lair of Tarn Razorlor", 3168, 4564),
		new Layer("Waterbirth Dungeon", 2495, 10144),
		new Layer("Wilderness Dungeons", 3040, 10303),
		new Layer("Yanille Underground", 2580, 9522),
		new Layer("Zanaris", 2447, 4448),
		new Layer("Prifddinas", 3263, 6079),
		new Layer("Fossil Island Underground", 3744, 10272),
		new Layer("Feldip Hills Underground", 1989, 9023),
		new Layer("Kourend Underground", 1664, 10048),
		new Layer("Kebos Underground", 1266, 10206),
		new Layer("Prifddinas Underground", 3263, 12479),
		new Layer("Prifddinas Grand Library", 2623, 6143),
		new Layer("LMS Desert Island", 3456, 5824),
		new Layer("Tutorial Island", 1695, 6111),
		new Layer("LMS Wild Varrock", 3552, 6120),
		new Layer("Ruins of Camdozaal", 2952, 5766),
		new Layer("The Abyss", 3040, 4832),
		new Layer("Lassar Undercity", 2656, 6368),
		new Layer("Kharidian Desert Underground", 3488, 9504),
		new Layer("Varlamore Underground", 1696, 9504),
		new Layer("Cam Torum", 1440, 9568),
		new Layer("Neypotzli", 1440, 9632),
		new Layer("Ardent Ocean Underground", 2691, 9564),
		new Layer("Unquiet Ocean Underground", 3168, 8864),
		new Layer("Shrouded Ocean Underground", 2016, 9184),
		new Layer("Sunset Ocean Underground", 1181, 9198),
		new Layer("Western Ocean Underground", 2264, 9880),
		new Layer("Northern Ocean Underground", 2559, 10272),
	};

	private WorldMapLayers()
	{
	}

	/**
	 * Returns up to {@code limit} layer names ordered by how close their center is to the given
	 * world coordinate (nearest first). These are best guesses only; the caller must verify the
	 * coordinate is actually on a layer before using it.
	 */
	static List<String> nearestLayerNames(int x, int y, int limit)
	{
		final List<Layer> sorted = new ArrayList<>(Arrays.asList(LAYERS));
		sorted.sort(Comparator.comparingLong(layer -> distanceSquared(x, y, layer)));

		final List<String> names = new ArrayList<>();
		for (int i = 0; i < sorted.size() && i < limit; i++)
		{
			names.add(sorted.get(i).name);
		}
		return names;
	}

	private static long distanceSquared(int x, int y, Layer layer)
	{
		final long dx = x - layer.x;
		final long dy = y - layer.y;
		return dx * dx + dy * dy;
	}
}
