package com.danser.viewer;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * Per-device audio offsets for Bluetooth headphones. An offset only applies while its
 * device is connected; with no Bluetooth output the extra offset is 0.
 */
final class BluetoothSync {
    private static final String KEY_PREFIX = "bt_offset:";

    private BluetoothSync() {}

    /** Name of the connected Bluetooth audio output, or null if media plays elsewhere. */
    static String connectedDevice(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                    CharSequence name = d.getProductName();
                    return name != null && name.length() > 0 ? name.toString() : "Bluetooth audio";
            }
        }

        return null;
    }

    static boolean hasOffset(Context ctx, String device) {
        return DanserSettings.prefs(ctx).contains(KEY_PREFIX + device);
    }

    static int getOffset(Context ctx, String device) {
        return DanserSettings.prefs(ctx).getInt(KEY_PREFIX + device, 0);
    }

    static void setOffset(Context ctx, String device, int offsetMs) {
        DanserSettings.prefs(ctx).edit().putInt(KEY_PREFIX + device, offsetMs).apply();
    }

    static void clearOffset(Context ctx, String device) {
        DanserSettings.prefs(ctx).edit().remove(KEY_PREFIX + device).apply();
    }

    /** Offset to add for whatever is connected right now: the device's calibrated value, or 0. */
    static int currentOffset(Context ctx) {
        String device = connectedDevice(ctx);
        return device != null ? getOffset(ctx, device) : 0;
    }
}
