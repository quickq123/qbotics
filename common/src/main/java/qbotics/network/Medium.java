package qbotics.network;

import org.jspecify.annotations.Nullable;

/** What a logistics device carries: items, fluids, energy or experience (design doc §11.0). */
public enum Medium {
    ITEM("item", ""), FLUID("fluid", "fluid_"), ENERGY("energy", "energy_"), EXPERIENCE("experience", "experience_");

    /** The word saved in the world. */
    public final String word;
    /** Goes before a device's kind in the name programs use: "fluid_input", "energy_bus"; nothing for items. */
    public final String prefix;

    Medium(String word, String prefix) {
        this.word = word;
        this.prefix = prefix;
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
