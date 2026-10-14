package com.danser.viewer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.un4seen.bass.BASS;
import com.un4seen.bass.BASS_FX;
import com.un4seen.bass.BASSmix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures Bluetooth audio latency. A metronome plays through the same BASS chain danser uses
 * (same buffers, mixer and tempo stream), so its clock behaves exactly like danser's. The user taps
 * along to the clicks they hear; the median delay becomes the offset, which can then be fine-tuned
 * by watching the approach circle close on each click.
 */
public class CalibrationActivity extends Activity {
    private static final int ACCENT = Color.rgb(255, 102, 170);

    private static final int SAMPLE_RATE = 44100;
    private static final double FIRST_BEAT_MS = 1000;
    private static final double BEAT_MS = 1000; // 60 BPM: one beat per second covers latencies up to ~900 ms
    private static final int BEATS = 90;
    private static final int TAPS_NEEDED = 12;

    private String device;
    private int offsetMs;

    private int mixer, track;

    // Clock interpolation, same idea as danser's player
    private double lastRawMs = -1, interpolatedMs;
    private long lastClockNanos;

    private final List<Double> tapErrors = new ArrayList<>();

    private TextView status, offsetText;
    private CalibrationView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        device = BluetoothSync.connectedDevice(this);
        if (device == null) {
            Toast.makeText(this, "Connect your Bluetooth headphones first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        offsetMs = BluetoothSync.getOffset(this, device);

        buildUi();

        if (!startAudio()) {
            Toast.makeText(this, "Couldn't start audio (BASS error " + BASS.BASS_ErrorGetCode() + ")", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        BASS.BASS_Free();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 24, 28));
        Insets.padForSystemBars(root, dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Bluetooth sync");
        title.setTextColor(ACCENT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText(device + "\n\nTap anywhere on the circle in time with the clicks you hear. "
                + "After " + TAPS_NEEDED + " taps the offset is set automatically. "
                + "Then use −/+ until the approach circle closes exactly when you hear each click.");
        info.setTextColor(Color.LTGRAY);
        info.setPadding(0, dp(8), 0, dp(8));
        root.addView(info);

        view = new CalibrationView(this);
        root.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        LinearLayout adjust = new LinearLayout(this);
        adjust.setGravity(Gravity.CENTER_VERTICAL);
        adjust.addView(button("−10", v -> nudge(-10)));
        adjust.addView(button("−1", v -> nudge(-1)));

        offsetText = new TextView(this);
        offsetText.setTextColor(Color.WHITE);
        offsetText.setTextSize(20);
        offsetText.setGravity(Gravity.CENTER);
        adjust.addView(offsetText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        adjust.addView(button("+1", v -> nudge(1)));
        adjust.addView(button("+10", v -> nudge(10)));
        root.addView(adjust);

        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Retap", v -> {
            tapErrors.clear();
            updateText();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(button("Reset to 0", v -> {
            BluetoothSync.clearOffset(this, device);
            offsetMs = 0;
            tapErrors.clear();
            updateText();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(button("Save", v -> {
            BluetoothSync.setOffset(this, device, offsetMs);
            Toast.makeText(this, "Saved " + offsetMs + " ms for " + device, Toast.LENGTH_SHORT).show();
            finish();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(actions);

        setContentView(root);
        updateText();
    }

    private void nudge(int ms) {
        offsetMs += ms;
        updateText();
    }

    private void updateText() {
        offsetText.setText(offsetMs + " ms");

        if (tapErrors.size() < TAPS_NEEDED) {
            status.setText("Taps: " + tapErrors.size() + " / " + TAPS_NEEDED);
        } else {
            status.setText("Offset measured from your taps. Fine-tune with −/+ then Save.");
        }
    }

    // ---------------------------------------------------------------- Audio

    /** Same BASS configuration and stream chain as danser's framework/bass (Android defaults). */
    private boolean startAudio() {
        File click;
        try {
            click = writeMetronome();
        } catch (IOException e) {
            return false;
        }

        BASS.BASS_SetConfig(BASS.BASS_CONFIG_DEV_NONSTOP, 1);
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_BUFFER, 100);
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_UPDATEPERIOD, 5);
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_DEV_BUFFER, 10);
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_DEV_PERIOD, 10);

        if (!BASS.BASS_Init(-1, SAMPLE_RATE, 0)) return false;

        mixer = BASSmix.BASS_Mixer_StreamCreate(SAMPLE_RATE, 2, BASSmix.BASS_MIXER_NONSTOP);
        BASS.BASS_ChannelSetAttribute(mixer, BASS.BASS_ATTRIB_BUFFER, 0);
        BASS.BASS_ChannelPlay(mixer, false);

        int stream = BASS.BASS_StreamCreateFile(click.getAbsolutePath(), 0, 0, BASS.BASS_STREAM_DECODE | BASS.BASS_STREAM_PRESCAN);
        if (stream == 0) return false;

        track = BASS_FX.BASS_FX_TempoCreate(stream, BASS_FX.BASS_FX_FREESOURCE | BASS.BASS_STREAM_DECODE);
        if (track == 0) return false;

        return BASSmix.BASS_Mixer_StreamAddChannel(mixer, track, BASSmix.BASS_MIXER_CHAN_NORAMPIN | BASSmix.BASS_MIXER_CHAN_BUFFER);
    }

    /** Raw music clock in ms, interpolated between BASS position updates like danser does. */
    private double clockMs() {
        double raw = BASS.BASS_ChannelBytes2Seconds(track, BASSmix.BASS_Mixer_ChannelGetPosition(track, BASS.BASS_POS_BYTE)) * 1000;
        long now = System.nanoTime();

        if (raw != lastRawMs) {
            lastRawMs = raw;
            interpolatedMs = raw;
        } else if (raw > 1) {
            interpolatedMs += (now - lastClockNanos) / 1e6;
        }

        lastClockNanos = now;
        return interpolatedMs;
    }

    /** A click track: one click per beat, every 4th accented. Mono 16-bit WAV in the cache. */
    private File writeMetronome() throws IOException {
        File f = new File(getCacheDir(), "metronome.wav");
        if (f.exists()) return f;

        int totalSamples = (int) ((FIRST_BEAT_MS + BEATS * BEAT_MS) / 1000 * SAMPLE_RATE);
        short[] pcm = new short[totalSamples];
        int clickLen = SAMPLE_RATE * 25 / 1000;

        for (int b = 0; b < BEATS; b++) {
            int start = (int) ((FIRST_BEAT_MS + b * BEAT_MS) / 1000 * SAMPLE_RATE);
            double freq = b % 4 == 0 ? 2000 : 1400;

            for (int i = 0; i < clickLen && start + i < totalSamples; i++) {
                double env = Math.exp(-i / (SAMPLE_RATE * 0.004));
                pcm[start + i] = (short) (Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE) * env * 28000);
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(44 + totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes()).putInt(36 + totalSamples * 2).put("WAVE".getBytes());
        buf.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        buf.put("data".getBytes()).putInt(totalSamples * 2);
        for (short s : pcm) buf.putShort(s);

        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(buf.array());
        }

        return f;
    }

    // ---------------------------------------------------------------- Taps

    private void onTap(long eventUptimeMs) {
        // Clock value at the moment of the touch, not when we got to process it
        double tapClock = clockMs() - (SystemClock.uptimeMillis() - eventUptimeMs);

        // Taps follow what you hear, which lags the clock: measure against the latest beat before the tap,
        // allowing taps up to 100 ms early
        double beatIndex = Math.floor((tapClock + 100 - FIRST_BEAT_MS) / BEAT_MS);
        if (beatIndex < 0) return;

        double error = tapClock - (FIRST_BEAT_MS + beatIndex * BEAT_MS);
        tapErrors.add(error);

        if (tapErrors.size() >= TAPS_NEEDED) {
            List<Double> recent = new ArrayList<>(tapErrors.subList(tapErrors.size() - TAPS_NEEDED, tapErrors.size()));
            Collections.sort(recent);
            double median = recent.get(recent.size() / 2);

            // danser shows the beat at clock + offset; it should appear when the click is heard
            offsetMs = (int) -Math.round(median);
        }

        updateText();
    }

    // ---------------------------------------------------------------- Drawing

    private class CalibrationView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        CalibrationView(Context ctx) {
            super(ctx);
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                onTap(e.getEventTime());
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.16f;

            double raw = clockMs();

            // Loop the click track shortly before it runs out
            if (raw > FIRST_BEAT_MS + (BEATS - 2) * BEAT_MS) {
                BASSmix.BASS_Mixer_ChannelSetPosition(track, 0, BASS.BASS_POS_BYTE);
                lastRawMs = -1;
                interpolatedMs = 0;
            }

            // Same mapping as danser: what's shown = raw clock + offset
            double time = raw + offsetMs;
            double sinceStart = time - FIRST_BEAT_MS;

            double next = FIRST_BEAT_MS + Math.max(0, Math.ceil(sinceStart / BEAT_MS)) * BEAT_MS;
            double prev = next - BEAT_MS;

            // Flash right after a beat
            double since = time - prev;
            int flash = since >= 0 && since < 150 && prev >= FIRST_BEAT_MS ? (int) (255 * (1 - since / 150)) : 0;

            fill.setColor(Color.argb(60 + flash * 195 / 255, 255, 102, 170));
            canvas.drawCircle(cx, cy, radius, fill);

            ring.setColor(Color.WHITE);
            ring.setStrokeWidth(dp(4));
            canvas.drawCircle(cx, cy, radius, ring);

            // Approach circle closes onto the hit circle at the beat
            double until = next - time;
            double preempt = 800;
            if (until >= 0 && until <= preempt) {
                float scale = 1 + 2.5f * (float) (until / preempt);
                ring.setColor(Color.argb(220, 255, 102, 170));
                ring.setStrokeWidth(dp(3));
                canvas.drawCircle(cx, cy, radius * scale, ring);
            }

            postInvalidateOnAnimation();
        }
    }
}
