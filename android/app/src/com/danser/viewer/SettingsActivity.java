package com.danser.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Lets the user change the settings in {@link DanserSettings}. Changes are saved immediately. */
public class SettingsActivity extends Activity {
    private static final int ACCENT = Color.rgb(255, 102, 170);

    private LinearLayout list;
    private TextView btStatus;
    private Button btCalibrate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (btStatus != null) updateBluetooth(); // headphones may have connected, or a calibration was saved
    }

    private void updateBluetooth() {
        String device = BluetoothSync.connectedDevice(this);

        if (device == null) {
            btStatus.setText("No Bluetooth headphones connected, so no extra offset is applied.");
            btCalibrate.setEnabled(false);
        } else if (BluetoothSync.hasOffset(this, device)) {
            btStatus.setText(device + ": " + BluetoothSync.getOffset(this, device) + " ms (applied while connected)");
            btCalibrate.setEnabled(true);
        } else {
            btStatus.setText(device + ": not calibrated yet");
            btCalibrate.setEnabled(true);
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(24, 24, 28));
        // keep the first slider from grabbing focus and scrolling the page down on open
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.setFocusableInTouchMode(true);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Insets.padForSystemBars(list, dp(16), dp(16), dp(16), dp(32));
        scroll.addView(list);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(ACCENT);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);

        String section = null;
        for (DanserSettings.Setting s : DanserSettings.ALL) {
            if (!s.section.equals(section)) {
                section = s.section;
                addHeader(section);
            }

            if (s instanceof DanserSettings.Slider) {
                addSlider((DanserSettings.Slider) s);
            } else if (s instanceof DanserSettings.Toggle) {
                addToggle((DanserSettings.Toggle) s);
            } else if (s instanceof DanserSettings.Choice) {
                addChoice((DanserSettings.Choice) s);
            }
        }

        addHeader("Bluetooth sync");

        btStatus = new TextView(this);
        btStatus.setTextColor(Color.LTGRAY);
        btStatus.setPadding(0, dp(4), 0, dp(4));
        list.addView(btStatus);

        btCalibrate = new Button(this);
        btCalibrate.setText("Calibrate Bluetooth headphones");
        btCalibrate.setAllCaps(false);
        btCalibrate.setOnClickListener(v -> startActivity(new Intent(this, CalibrationActivity.class)));
        list.addView(btCalibrate);

        updateBluetooth();

        Button reset = new Button(this);
        reset.setText("Reset to defaults");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Reset all settings to their defaults?")
                .setPositiveButton("Reset", (d, w) -> {
                    DanserSettings.resetAll(this);
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        list.addView(reset, lp);

        setContentView(scroll);
    }

    private void addHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextColor(ACCENT);
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, dp(20), 0, dp(4));
        list.addView(header);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        return t;
    }

    private void addSlider(DanserSettings.Slider s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));

        LinearLayout top = new LinearLayout(this);
        top.addView(label(s.label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = new TextView(this);
        value.setTextColor(Color.LTGRAY);
        top.addView(value);
        row.addView(top);

        SeekBar bar = new SeekBar(this);
        bar.setMax(s.max - s.min);
        int current = DanserSettings.get(this, s);
        bar.setProgress(current - s.min);
        value.setText(s.format(current));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                int v = progress + s.min;
                value.setText(s.format(v));
                if (fromUser) DanserSettings.set(SettingsActivity.this, s, v);
            }

            public void onStartTrackingTouch(SeekBar b) {}

            public void onStopTrackingTouch(SeekBar b) {}
        });

        row.addView(bar);
        list.addView(row);
    }

    private void addToggle(DanserSettings.Toggle s) {
        Switch sw = new Switch(this);
        sw.setText(s.label);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(15);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setChecked(DanserSettings.get(this, s) != 0);
        sw.setOnCheckedChangeListener((b, checked) -> DanserSettings.set(this, s, checked ? 1 : 0));
        list.addView(sw);
    }

    private void addChoice(DanserSettings.Choice s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(label(s.label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, s.options));
        spinner.setSelection(Math.max(0, Math.min(s.options.size() - 1, DanserSettings.get(this, s))));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                DanserSettings.set(SettingsActivity.this, s, pos);
            }

            public void onNothingSelected(AdapterView<?> p) {}
        });
        row.addView(spinner);

        list.addView(row);
    }
}
