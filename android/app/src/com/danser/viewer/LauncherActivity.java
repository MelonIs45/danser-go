package com.danser.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Picks what danser should show: a replay, or a beatmap in autoplay / cursor dance mode. */
public class LauncherActivity extends Activity {
    private static final int REQUEST_IMPORT = 1;

    private static final int ACCENT = Color.rgb(255, 102, 170);

    /** Skin selected until the user picks another one. Bundled from android/skins. */
    private static final String DEFAULT_SKIN = "MonkoGlassEdit";
    private static final String PREF_SKIN = "skin";

    static class MapEntry {
        String artist = "", title = "", version = "", creator = "";
        File file;
        String md5;

        String label() {
            return artist + " - " + title + " [" + version + "]";
        }
    }

    static class ReplayEntry {
        File file;
        String mapMd5;
        String player = "";

        String label(Map<String, MapEntry> byMd5) {
            MapEntry map = byMd5.get(mapMd5);
            String mapName = map != null ? map.label() : "(beatmap not imported)";
            return player + " — " + mapName;
        }
    }

    private File songsDir, skinsDir, replaysDir;

    private final List<MapEntry> maps = new ArrayList<>();
    private final Map<String, MapEntry> mapsByMd5 = new HashMap<>();
    private final List<ReplayEntry> replays = new ArrayList<>();

    private final List<Object> shown = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private boolean showingReplays;
    private boolean busy;

    private TextView status;
    private ProgressBar progress;
    private EditText search;
    private Spinner skinSpinner;
    private Button mapsTab, replaysTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        songsDir = new File(getFilesDir(), "Songs");
        skinsDir = new File(getFilesDir(), "Skins");
        replaysDir = new File(getFilesDir(), "Replays");
        songsDir.mkdirs();
        skinsDir.mkdirs();
        replaysDir.mkdirs();

        buildUi();

        runInBackground("Preparing...", () -> {
            extractAssetsIfNeeded();
            scanLibrary();
        }, () -> handleIncoming(getIntent()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        showCrashIfAny();
    }

    /** danser writes crash.txt when it dies; show it once so it can be shared. */
    private void showCrashIfAny() {
        File crash = new File(getFilesDir(), "crash.txt");
        if (!crash.exists() || crash.length() == 0) return;

        long seen = getPreferences(MODE_PRIVATE).getLong("crash_seen", 0);
        if (crash.lastModified() <= seen) return;

        getPreferences(MODE_PRIVATE).edit().putLong("crash_seen", crash.lastModified()).apply();

        String report;
        try {
            report = "danser crashed on " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")\n\n"
                    + readText(crash) + "\n\n--- end of danser.log ---\n" + tail(new File(getFilesDir(), "danser.log"), 60);
        } catch (IOException e) {
            return;
        }

        TextView text = new TextView(this);
        text.setText(report);
        text.setTextSize(11);
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(8), dp(16), dp(8));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);

        new AlertDialog.Builder(this)
                .setTitle("danser crashed")
                .setView(scroll)
                .setPositiveButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, report);
                    startActivity(Intent.createChooser(send, "Share crash report"));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static String tail(File f, int lines) throws IOException {
        if (!f.exists()) return "";

        String[] all = readText(f).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
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
        Insets.padForSystemBars(root, dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(24, 24, 28));

        TextView title = new TextView(this);
        title.setText("danser");
        title.setTextColor(ACCENT);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("Import files (.osz / .osr / .osk)", v -> pickFiles()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        actions.addView(button("Settings", v -> startActivity(new Intent(this, SettingsActivity.class))),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(actions);

        LinearLayout skinRow = new LinearLayout(this);
        skinRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView skinLabel = new TextView(this);
        skinLabel.setText("Skin: ");
        skinLabel.setTextColor(Color.LTGRAY);
        skinRow.addView(skinLabel);
        skinSpinner = new Spinner(this);
        skinRow.addView(skinSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(skinRow);

        LinearLayout tabs = new LinearLayout(this);
        mapsTab = button("Beatmaps", v -> showTab(false));
        replaysTab = button("Replays", v -> showTab(true));
        tabs.addView(mapsTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(replaysTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(tabs);

        search = new EditText(this);
        search.setHint("Search");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.GRAY);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refreshList(); }
        });
        root.addView(search);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(status);
        root.addView(statusRow);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> onItemClicked(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        showTab(false);
    }

    private void showTab(boolean replaysTabSelected) {
        showingReplays = replaysTabSelected;
        mapsTab.setTextColor(replaysTabSelected ? Color.GRAY : ACCENT);
        replaysTab.setTextColor(replaysTabSelected ? ACCENT : Color.GRAY);
        refreshList();
    }

    private void refreshList() {
        String query = search.getText().toString().toLowerCase(Locale.ROOT).trim();

        shown.clear();
        List<String> labels = new ArrayList<>();

        if (showingReplays) {
            for (ReplayEntry r : replays) {
                String label = r.label(mapsByMd5);
                if (query.isEmpty() || label.toLowerCase(Locale.ROOT).contains(query)) {
                    shown.add(r);
                    labels.add(label);
                }
            }
        } else {
            for (MapEntry m : maps) {
                String label = m.label();
                if (query.isEmpty() || (label + " " + m.creator).toLowerCase(Locale.ROOT).contains(query)) {
                    shown.add(m);
                    labels.add(label);
                }
            }
        }

        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();

        if (!busy) {
            setStatus(maps.size() + " beatmaps, " + replays.size() + " replays"
                    + (maps.isEmpty() ? ". Import some .osz files to get started." : ""));
        }
    }

    private void refreshSkins() {
        List<String> skins = new ArrayList<>();
        skins.add("default");

        File[] dirs = skinsDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) skins.add(d.getName());
        }

        Collections.sort(skins.subList(1, skins.size()), String.CASE_INSENSITIVE_ORDER);

        Object current = skinSpinner.getSelectedItem();
        String wanted = current != null ? current.toString() : getPreferences(MODE_PRIVATE).getString(PREF_SKIN, DEFAULT_SKIN);

        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, skins);
        skinSpinner.setAdapter(a);

        if (skins.contains(wanted)) {
            skinSpinner.setSelection(skins.indexOf(wanted));
        }
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void runInBackground(String message, Runnable work, Runnable after) {
        busy = true;
        progress.setVisibility(View.VISIBLE);
        setStatus(message);

        new Thread(() -> {
            String error = null;
            try {
                work.run();
            } catch (RuntimeException e) {
                Log.e("danser", "Launcher task failed", e);
                error = e.getMessage();
            }

            String finalError = error;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                refreshSkins();
                refreshList();

                if (finalError != null) {
                    Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                }

                if (after != null) after.run();
            });
        }).start();
    }

    private void postStatus(String text) {
        runOnUiThread(() -> setStatus(text));
    }

    // ---------------------------------------------------------------- Launching

    private void onItemClicked(Object item) {
        if (item instanceof MapEntry) {
            MapEntry map = (MapEntry) item;
            new AlertDialog.Builder(this)
                    .setTitle(map.label())
                    .setItems(new String[] {"Autoplay", "danser (cursor dance)"}, (d, which) -> {
                        if (which == 0) {
                            launch(true, "-md5=" + map.md5, "-mods=AT");
                        } else {
                            launch(false, "-md5=" + map.md5);
                        }
                    })
                    .show();
        } else if (item instanceof ReplayEntry) {
            playReplay((ReplayEntry) item);
        }
    }

    private void playReplay(ReplayEntry replay) {
        if (!mapsByMd5.containsKey(replay.mapMd5)) {
            new AlertDialog.Builder(this)
                    .setTitle("Beatmap missing")
                    .setMessage("Import the beatmap (.osz) this replay was played on, then try again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        launch(false, "-replay=" + replay.file.getAbsolutePath());
    }

    private void launch(boolean autoplay, String... danserArgs) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-noupdatecheck");
        args.add("-nodbcheck");
        if (DanserSettings.get(this, DanserSettings.QUICKSTART) != 0) {
            args.add("-quickstart"); // no lead-in, start right at the first object
        }

        args.add("-sPatch=" + DanserSettings.buildPatch(this, autoplay));

        Object skin = skinSpinner.getSelectedItem();
        if (skin != null) {
            getPreferences(MODE_PRIVATE).edit().putString(PREF_SKIN, skin.toString()).apply();
            args.add("-skin=" + skin);
        }

        Collections.addAll(args, danserArgs);

        Intent intent = new Intent(this, DanserActivity.class);
        intent.putExtra(DanserActivity.EXTRA_ARGS, args.toArray(new String[0]));
        startActivity(intent);
    }

    // ---------------------------------------------------------------- Importing

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        importUris(uris);
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;

        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        }

        intent.setAction(null); // don't re-import on configuration changes

        if (!uris.isEmpty()) importUris(uris);
    }

    private void importUris(List<Uri> uris) {
        final ReplayEntry[] lastReplay = new ReplayEntry[1];

        runInBackground("Importing...", () -> {
            for (Uri uri : uris) {
                String name = displayName(uri);
                String lower = name.toLowerCase(Locale.ROOT);
                postStatus("Importing " + name + "...");

                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) continue;

                    if (lower.endsWith(".osz")) {
                        unzip(in, new File(songsDir, safeName(name.substring(0, name.length() - 4))));
                    } else if (lower.endsWith(".osk")) {
                        unzip(in, new File(skinsDir, safeName(name.substring(0, name.length() - 4))));
                    } else if (lower.endsWith(".osr")) {
                        File out = new File(replaysDir, safeName(name));
                        copy(in, out);
                        lastReplay[0] = readReplay(out);
                    } else {
                        throw new RuntimeException("Unsupported file: " + name);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to import " + name + ": " + e.getMessage());
                }
            }

            scanLibrary();
        }, () -> {
            if (uris.size() == 1 && lastReplay[0] != null) {
                showTab(true);
                playReplay(lastReplay[0]);
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }

        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }

    private static String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static void copy(InputStream in, File out) throws IOException {
        out.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void unzip(InputStream in, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;

        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(dest, entry.getName());

                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Invalid archive entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    copy(zip, out);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Library scanning

    private void scanLibrary() {
        List<MapEntry> foundMaps = new ArrayList<>();

        File[] sets = songsDir.listFiles(File::isDirectory);
        if (sets != null) {
            for (File set : sets) {
                File[] osuFiles = set.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".osu"));
                if (osuFiles == null) continue;

                for (File f : osuFiles) {
                    MapEntry m = readMap(f);
                    if (m != null) foundMaps.add(m);
                }
            }
        }

        foundMaps.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));

        List<ReplayEntry> foundReplays = new ArrayList<>();
        File[] replayFiles = replaysDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".osr"));
        if (replayFiles != null) {
            for (File f : replayFiles) {
                ReplayEntry r = readReplay(f);
                if (r != null) foundReplays.add(r);
            }
        }

        foundReplays.sort((a, b) -> Long.compare(b.file.lastModified(), a.file.lastModified()));

        runOnUiThread(() -> {
            maps.clear();
            maps.addAll(foundMaps);
            mapsByMd5.clear();
            for (MapEntry m : foundMaps) mapsByMd5.put(m.md5, m);
            replays.clear();
            replays.addAll(foundReplays);
        });
    }

    /** Reads the metadata of an osu!standard .osu file, or returns null for other modes. */
    private static MapEntry readMap(File f) {
        MapEntry m = new MapEntry();
        m.file = f;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("Mode:") && !line.substring(5).trim().equals("0")) return null;
                else if (line.startsWith("Title:")) m.title = line.substring(6).trim();
                else if (line.startsWith("Artist:")) m.artist = line.substring(7).trim();
                else if (line.startsWith("Creator:")) m.creator = line.substring(8).trim();
                else if (line.startsWith("Version:")) m.version = line.substring(8).trim();
                else if (line.equals("[Difficulty]")) break;
            }

            m.md5 = md5(f);
        } catch (IOException e) {
            return null;
        }

        return m;
    }

    private static String md5(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);

            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Reads the beatmap hash and player name from an .osr header. */
    private static ReplayEntry readReplay(File f) {
        try (InputStream in = new FileInputStream(f)) {
            in.read();          // game mode
            readBytes(in, 4);   // game version

            ReplayEntry r = new ReplayEntry();
            r.file = f;
            r.mapMd5 = readOsuString(in);
            r.player = readOsuString(in);

            return r.mapMd5 != null ? r : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readBytes(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(b, off, n - off);
            if (read < 0) throw new IOException("Unexpected end of replay");
            off += read;
        }
        return b;
    }

    private static String readOsuString(InputStream in) throws IOException {
        int marker = in.read();
        if (marker == 0x00) return "";
        if (marker != 0x0b) throw new IOException("Invalid replay string");

        int length = 0, shift = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("Unexpected end of replay");
            length |= (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return new String(readBytes(in, length), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Assets

    /** Copies danser's assets folder out of the APK whenever the app is installed or updated. */
    private void extractAssetsIfNeeded() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String stamp = Long.toString(info.lastUpdateTime);

            File marker = new File(getFilesDir(), "assets.version");
            if (marker.exists() && stamp.equals(readText(marker))) return;

            postStatus("Unpacking danser assets...");
            copyAssetDir(getAssets(), "assets", new File(getFilesDir(), "assets"));

            String[] bundledSkins = getAssets().list("skins");
            if (bundledSkins != null) {
                for (String skin : bundledSkins) {
                    postStatus("Installing skin " + skin + "...");
                    copyAssetDir(getAssets(), "skins/" + skin, new File(skinsDir, skin));
                }
            }

            try (OutputStream os = new FileOutputStream(marker)) {
                os.write(stamp.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to unpack assets: " + e, e);
        }
    }

    private static void copyAssetDir(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);

        if (children == null || children.length == 0) {
            try (InputStream in = am.open(path)) {
                copy(in, dest);
            }
            return;
        }

        dest.mkdirs();
        for (String child : children) {
            copyAssetDir(am, path + "/" + child, new File(dest, child));
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readBytes(in, (int) f.length()), StandardCharsets.UTF_8).trim();
        }
    }
}
