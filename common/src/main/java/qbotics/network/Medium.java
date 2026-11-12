package qbotics.network;

import org.jspecify.annotations.Nullable;

/**
 * What a logistics device carries: items, fluids, energy or experience (design doc §11.0).
 * A Universal Serial Bus carries items, fluid and energy, so its medium is UNIVERSAL; each
 * call works on one of the three (see CablePort.as).
 */
public enum Medium {
    ITEM("item", ""), FLUID("fluid", "fluid_"), ENERGY("energy", "energy_"), EXPERIENCE("experience", "experience_"),
    UNIVERSAL("universal", "universal_");

    /** The word saved in the world. */
    public final String word;
    /** Goes before a device's kind in the name programs use: "fluid_input", "energy_bus"; nothing for items. */
    public final String prefix;

    Medium(String word, String prefix) {
        this.word = word;
        this.prefix = prefix;
    }

    /** The media a Universal Serial Bus carries, in the order a move tries them. */
    public static final java.util.List<Medium> CARRIED = java.util.List.of(ITEM, FLUID, ENERGY);

    /** The medium a program names after "@" in a USB's address ("item", "fluid", "energy"), or null. */
    public static @Nullable Medium carried(String word) {
        for (Medium medium : CARRIED) {
            if (medium.word.equals(word)) {
                return medium;
            }
        }
        return null;
    }

    public static Medium parse(@Nullable String word) {
        for (Medium medium : values()) {
            if (medium.word.equals(word)) {
                return medium;
            }
        }
        return ITEM;
    }
}
