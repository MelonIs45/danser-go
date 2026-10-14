package com.danser.viewer;

import android.os.Process;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.Collections;

/** Hosts danser through SDL. Runs in the ":danser" process, which is killed when danser exits. */
public class DanserActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "danser.args";

    @Override
    protected String[] getLibraries() {
        // BASS libraries are loaded through System.loadLibrary so their JNI_OnLoad can grab the Java VM.
        return new String[] {"SDL3", "bass", "bass_fx", "bassmix", "main"};
    }

    @Override
    protected String[] getArguments() {
        ArrayList<String> args = new ArrayList<>();
        args.add(getFilesDir().getAbsolutePath());

        String[] extra = getIntent().getStringArrayExtra(EXTRA_ARGS);
        if (extra != null) {
            Collections.addAll(args, extra);
        }

        return args.toArray(new String[0]);
    }

    /** Back leaves danser. SDL's default back handling doesn't reliably close it on newer Android versions. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                finish();
            }

            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isFinishing()) {
            Process.killProcess(Process.myPid());
        }
    }
}
