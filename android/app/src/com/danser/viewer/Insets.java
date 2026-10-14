package com.danser.viewer;

import android.view.View;

/** Android 15+ draws apps edge-to-edge, so screens have to pad themselves away from the status/navigation bars. */
final class Insets {
    private Insets() {}

    /** Pads v by the given amounts plus the system bars, keeping it updated on rotation. */
    @SuppressWarnings("deprecation")
    static void padForSystemBars(View v, int left, int top, int right, int bottom) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        v.setPadding(left, top, right, bottom);
        v.requestApplyInsets();
    }
}
