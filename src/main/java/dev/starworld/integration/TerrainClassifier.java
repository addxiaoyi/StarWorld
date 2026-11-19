package dev.starworld.integration;

import java.util.Locale;
/** Reads exactly one sampled biome when registering a building. No chunk scans or forced loads. */
public final class TerrainClassifier {
    private TerrainClassifier() {}
    public static String classify(String biome) {
        String key=biome.toLowerCase(Locale.ROOT);
        if(key.contains("river") || key.contains("beach") || key.contains("ocean"))return "river";
        if(key.contains("desert") || key.contains("badlands"))return "desert";
        if(key.contains("snow") || key.contains("frozen") || key.contains("ice") || key.contains("grove"))return "snow";
        if(key.contains("peak") || key.contains("mountain") || key.contains("hill") || key.contains("slope") || key.contains("windswept"))return "mountains";
        if(key.contains("forest") || key.contains("taiga") || key.contains("jungle") || key.contains("mangrove"))return "forest";
        return "plains";
    }
}
