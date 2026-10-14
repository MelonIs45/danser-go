package com.danser.viewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * The handful of danser settings exposed on the phone. Values are stored in SharedPreferences
 * and turned into a danser -sPatch JSON object on every launch.
 */
final class DanserSettings {
    static final String PREFS = "danser_settings";

    /** A setting backed by an int preference. */
    abstract static class Setting {
        final String key, label, section;

        Setting(String section, String key, String label) {
            this.section = section;
            this.key = key;
            this.label = label;
        }

        abstract int defaultValue();

        abstract void apply(JSONObject patch, int value) throws JSONException;
    }

    /** A numeric value shown as a slider; json value = stored / divisor. */
    static final class Slider extends Setting {
        final int min, max, def;
        final double divisor;
        final String unit, path;

        Slider(String section, String key, String label, int min, int max, int def, double divisor, String unit, String path) {
            super(section, key, label);
            this.min = min;
            this.max = max;
            this.def = def;
            this.divisor = divisor;
            this.unit = unit;
            this.path = path;
        }

        int defaultValue() { return def; }

        void apply(JSONObject patch, int value) throws JSONException {
            put(patch, path, divisor == 1 ? (Object) value : (Object) (value / divisor));
        }

        String format(int value) {
            if ("x".equals(unit)) return String.format(java.util.Locale.ROOT, "%.2fx", value / divisor);
            return value + unit;
        }
    }

    /** An on/off switch. Stored as 0/1. */
    static final class Toggle extends Setting {
        final boolean def;
        final String path;

        Toggle(String section, String key, String label, boolean def, String path) {
            super(section, key, label);
            this.def = def;
            this.path = path;
        }

        int defaultValue() { return def ? 1 : 0; }

        void apply(JSONObject patch, int value) throws JSONException {
            if (path != null) put(patch, path, value != 0);
        }
    }

    /** A pick from a list. Stored as the option index. Applied by the launcher, not through a path. */
    static final class Choice extends Setting {
        final List<String> options;
        final int def;

        Choice(String section, String key, String label, List<String> options, int def) {
            super(section, key, label);
            this.options = options;
            this.def = def;
        }

        int defaultValue() { return def; }

        void apply(JSONObject patch, int value) {}
    }

    static final Slider MASTER_VOLUME = new Slider("Audio", "volume_master", "Master volume", 0, 100, 50, 100, "%", "Audio.GeneralVolume");
    static final Slider MUSIC_VOLUME = new Slider("Audio", "volume_music", "Music volume", 0, 100, 50, 100, "%", "Audio.MusicVolume");
    static final Slider HITSOUND_VOLUME = new Slider("Audio", "volume_hitsounds", "Hitsound volume", 0, 100, 50, 100, "%", "Audio.SampleVolume");
    static final Slider OFFSET = new Slider("Audio", "offset", "Audio offset", -300, 300, 0, 1, "ms", "Audio.Offset");

    static final Slider BACKGROUND_DIM = new Slider("Display", "bg_dim", "Background dim", 0, 100, 95, 100, "%", "Playfield.Background.Dim.Normal");
    static final Toggle STORYBOARDS = new Toggle("Display", "storyboards", "Storyboards", true, "Playfield.Background.LoadStoryboards");
    static final Toggle SKIN_COLOURS = new Toggle("Display", "skin_colours", "Use skin colours", true, "Skin.UseColorsFromSkin");
    static final Toggle SKIN_CURSOR = new Toggle("Display", "skin_cursor", "Use skin cursor", true, "Skin.Cursor.UseSkinCursor");
    static final Slider CURSOR_SIZE = new Slider("Display", "cursor_size", "Cursor size", 50, 200, 100, 100, "%", "Skin.Cursor.Scale");

    static final Toggle PP_COUNTER = new Toggle("HUD", "hud_pp", "PP counter", true, "Gameplay.PPCounter.Show");
    static final Toggle HIT_ERROR = new Toggle("HUD", "hud_hit_error", "Hit error meter", true, "Gameplay.HitErrorMeter.Show");
    static final Toggle FPS = new Toggle("HUD", "hud_fps", "FPS counter", true, "Graphics.ShowFPS");

    static final List<String> MOVERS = Arrays.asList("linear", "spline", "bezier", "circular", "axis", "aggressive", "flower", "momentum", "exgon", "pippi");
    static final Choice AUTOPLAY_MOVER = new Choice("Playback", "autoplay_mover", "Autoplay cursor movement", MOVERS, 0);
    static final Toggle QUICKSTART = new Toggle("Playback", "quickstart", "Start immediately (skip lead-in & intro)", true, null);

    static final List<Setting> ALL = Arrays.asList(
            MASTER_VOLUME, MUSIC_VOLUME, HITSOUND_VOLUME, OFFSET,
            BACKGROUND_DIM, STORYBOARDS, SKIN_COLOURS, SKIN_CURSOR, CURSOR_SIZE,
            PP_COUNTER, HIT_ERROR, FPS,
            AUTOPLAY_MOVER, QUICKSTART);

    private DanserSettings() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int get(Context ctx, Setting s) {
        return prefs(ctx).getInt(s.key, s.defaultValue());
    }

    static void set(Context ctx, Setting s, int value) {
        prefs(ctx).edit().putInt(s.key, value).apply();
    }

    static void resetAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /** Builds the -sPatch JSON for a launch. */
    static String buildPatch(Context ctx, boolean autoplay) {
        try {
            JSONObject patch = new JSONObject();

            // Always off on the phone
            put(patch, "Playfield.SeizureWarning.Enabled", false);
            put(patch, "Playfield.Logo.Enabled", false);

            for (Setting s : ALL) {
                s.apply(patch, get(ctx, s));
            }

            // Calibrated Bluetooth latency on top of the manual offset; 0 when no Bluetooth output is connected
            put(patch, "Audio.Offset", get(ctx, OFFSET) + BluetoothSync.currentOffset(ctx));

            if (autoplay) {
                JSONObject mover = new JSONObject();
                mover.put("Mover", MOVERS.get(Math.max(0, Math.min(MOVERS.size() - 1, get(ctx, AUTOPLAY_MOVER)))));
                mover.put("SliderDance", false);
                mover.put("RandomSliderDance", false);

                put(patch, "CursorDance.Movers", new JSONArray().put(mover));
            }

            return patch.toString();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /** Sets a dotted path like "Audio.GeneralVolume", creating intermediate objects. */
    private static void put(JSONObject root, String path, Object value) throws JSONException {
        String[] parts = path.split("\\.");
        JSONObject node = root;

        for (int i = 0; i < parts.length - 1; i++) {
            JSONObject next = node.optJSONObject(parts[i]);
            if (next == null) {
                next = new JSONObject();
                node.put(parts[i], next);
            }
            node = next;
        }

        node.put(parts[parts.length - 1], value);
    }
}
