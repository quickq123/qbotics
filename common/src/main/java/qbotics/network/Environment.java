package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

/**
 * What the world is like where a computer is (docs/reference.md §7k2, the env module): the time
 * and day, the moon, the weather and what falls here, the biome, the light, and where "here"
 * is. A placed computer is where its block is, a Qbot where it stands now, and a laptop where the
 * player carrying it is. One read, no scanning; programs watch it by asking again.
 */
public final class Environment {
    private Environment() {
    }

    /** Where the day turns: the game's own marks, in ticks of the day (0 is sunrise's end, 6 in the morning). */
    private static String phase(long tick) {
        if (tick >= 23000) {
            return "sunrise";
        }
        if (tick < 12000) {
            return "day";
        }
        return tick < 13000 ? "sunset" : "night";
    }

    /** The readings at a place. {@code at} is the block the computer occupies (a laptop's: its player's feet). */
    public static JsonObject read(ServerLevel level, BlockPos at) {
        BlockPos here = at.above(); // the air beside the computer, not inside its block
        JsonObject env = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(at.getX());
        pos.add(at.getY());
        pos.add(at.getZ());
        env.add("pos", pos);
        env.addProperty("dimension", level.dimension().identifier().toString());
        boolean clock = level.dimensionType().defaultClock().isPresent() && !level.dimensionType().hasFixedTime();
        env.addProperty("has_time", clock);
        if (clock) {
            long ticks = level.getDefaultClockTime();
            long tick = Math.floorMod(ticks, 24000L);
            env.addProperty("time", tick);
            // The game's day starts at six in the morning.
            long minutes = (tick * 60 * 24 / 24000 + 6 * 60) % (24 * 60);
            env.addProperty("clock", String.format("%02d:%02d", minutes / 60, minutes % 60));
            env.addProperty("phase", phase(tick));
            env.addProperty("day", ticks / 24000);
            net.minecraft.world.level.MoonPhase moon = level.environmentAttributes()
                    .getValue(net.minecraft.world.attribute.EnvironmentAttributes.MOON_PHASE, here);
            env.addProperty("moon", moon.index());
            env.addProperty("moon_name", moon.getSerializedName());
        } else {
            for (String key : new String[] {"time", "clock", "phase", "day", "moon", "moon_name"}) {
                env.add(key, JsonNull.INSTANCE);
            }
        }
        env.addProperty("dark", level.isDarkOutside());
        env.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
        // What falls here: rain, snow, or nothing (clear weather, a desert, a roof overhead).
        String falling = "none";
        boolean sky = level.canSeeSky(here);
        if (level.isRaining() && sky) {
            Biome.Precipitation kind = level.getBiome(here).value().getPrecipitationAt(here, level.getSeaLevel());
            falling = kind == Biome.Precipitation.RAIN ? "rain" : kind == Biome.Precipitation.SNOW ? "snow" : "none";
        }
        env.addProperty("here", falling);
        env.addProperty("sky", sky);
        env.addProperty("biome", level.getBiome(here).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"));
        JsonObject light = new JsonObject();
        light.addProperty("block", level.getBrightness(LightLayer.BLOCK, here));
        light.addProperty("sky", level.getBrightness(LightLayer.SKY, here));
        light.addProperty("now", level.getMaxLocalRawBrightness(here));
        env.add("light", light);
        return env;
    }
}
