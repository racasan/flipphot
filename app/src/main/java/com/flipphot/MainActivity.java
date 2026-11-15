package com.flipphot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;

/**
 * Flipphot: a minimal digital photo frame.
 *
 * Shows the photos stored on the device as a full-screen cross-fading
 * slideshow. No network, no audio, no libraries. Everything is built to stay
 * light on an old tablet: one background thread, at most two screen-sized
 * bitmaps in memory, and nothing running between slides except one timer.
 */
public class MainActivity extends Activity {

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final int REQ_PERMISSION = 1;
    private static final int LOW_BATTERY_PERCENT = 10;
    private static final long FADE_MS = 800;
    private static final long CONTROLS_VISIBLE_MS = 5000;

    private static final int[] INTERVAL_SECONDS = {5, 10, 15, 30, 60, 300, 900, 3600};
    private static final String[] INTERVAL_LABELS = {
            "5 seconds", "10 seconds", "15 seconds", "30 seconds",
            "1 minute", "5 minutes", "15 minutes", "1 hour"};

    private static final String PREF_INTERVAL = "interval";
    private static final String PREF_SHUFFLE = "shuffle";
    private static final String PREF_ALBUM_ID = "albumId";
    private static final String PREF_ALBUM_NAME = "albumName";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private HandlerThread workerThread;
    private Handler worker;
    private SharedPreferences prefs;

    private ImageView front, back;      // front = currently visible layer
    private Bitmap frontBitmap, backBitmap;
    private TextView messageView, batteryView;
    private View controls;
    private Button pauseButton;

    private long[] ids = new long[0];   // MediaStore ids of the photos
    private int[] rotations = new int[0];
    private int[] order = new int[0];   // play order (indexes into ids)
    private int position = -1;

    private boolean started, paused, needsPermission;
    private int loadGeneration, showGeneration;
    private volatile int screenW, screenH;

    private final Runnable nextTick = () -> step(1);
    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (level < 0 || scale <= 0) return;
            int percent = level * 100 / scale;
            if (percent < LOW_BATTERY_PERCENT && !charging) {
                batteryView.setText("Battery low: " + percent + "%  -  plug in the charger");
                batteryView.setVisibility(View.VISIBLE);
            } else {
                batteryView.setVisibility(View.GONE);
            }
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);

        prefs = getPreferences(MODE_PRIVATE);
        front = findViewById(R.id.imageA);
        back = findViewById(R.id.imageB);
        messageView = findViewById(R.id.message);
        batteryView = findViewById(R.id.battery);
        controls = findViewById(R.id.controls);
        pauseButton = findViewById(R.id.pause);

        findViewById(R.id.root).setOnClickListener(v -> onScreenTapped());
        findViewById(R.id.prev).setOnClickListener(v -> { keepControls(); step(-1); });
        findViewById(R.id.next).setOnClickListener(v -> { keepControls(); step(1); });
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        pauseButton.setOnClickListener(v -> togglePause());

        workerThread = new HandlerThread("flipphot-worker", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        readScreenSize();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        hideSystemBars();

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter);
        }

        if (checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED) {
            loadPhotos(false);
        } else {
            needsPermission = true;
            showMessage("Flipphot needs permission to read the photos on this tablet.\n\nTap to allow.");
            requestPermissions(new String[]{storagePermission()}, REQ_PERMISSION);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        showGeneration++;               // drop any decode still in flight
        loadGeneration++;
        ui.removeCallbacks(nextTick);
        unregisterReceiver(batteryReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        workerThread.quit();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readScreenSize();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQ_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            needsPermission = false;
            if (started) loadPhotos(true);
        }
    }

    // ---------------------------------------------------------------- photos

    /** Reads the photo list from the device's media index (off the UI thread). */
    private void loadPhotos(boolean restart) {
        final int generation = ++loadGeneration;
        final String albumId = prefs.getString(PREF_ALBUM_ID, null);
        worker.post(() -> {
            long[] newIds = new long[0];
            int[] newRotations = new int[0];
            try (Cursor c = getContentResolver().query(
                    IMAGES,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.ORIENTATION},
                    albumId == null ? null : MediaStore.Images.Media.BUCKET_ID + "=?",
                    albumId == null ? null : new String[]{albumId},
                    MediaStore.Images.Media.DATE_TAKEN + " ASC, " + MediaStore.Images.Media._ID + " ASC")) {
                if (c != null) {
                    int n = c.getCount();
                    newIds = new long[n];
                    newRotations = new int[n];
                    int i = 0;
                    while (i < n && c.moveToNext()) {
                        newIds[i] = c.getLong(0);
                        newRotations[i] = c.getInt(1);
                        i++;
                    }
                }
            } catch (RuntimeException ignored) {
                // leave the list empty; the "no photos" message is shown
            }
            final long[] fIds = newIds;
            final int[] fRotations = newRotations;
            ui.post(() -> {
                if (generation == loadGeneration && started) onPhotosLoaded(fIds, fRotations, restart);
            });
        });
    }

    private void onPhotosLoaded(long[] newIds, int[] newRotations, boolean restart) {
        boolean sameSet = !restart && newIds.length == ids.length && frontBitmap != null;
        ids = newIds;
        rotations = newRotations;

        if (ids.length == 0) {
            clearImages();
            showMessage("No photos found.\n\nCopy some photos onto the tablet"
                    + " (for example into the Pictures folder) and they will appear here.");
            return;
        }
        messageView.setVisibility(View.GONE);

        if (sameSet) {
            schedule();                 // coming back to the app: carry on where we were
            return;
        }
        order = new int[ids.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (prefs.getBoolean(PREF_SHUFFLE, true)) shuffle(order);
        position = -1;
        step(1);
    }

    private void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    // ---------------------------------------------------------------- slideshow

    /** Moves forward (+1) or back (-1) one photo and shows it. */
    private void step(int delta) {
        ui.removeCallbacks(nextTick);
        if (!started || order.length == 0) return;

        position += delta;
        if (position >= order.length) {
            position = 0;
            if (prefs.getBoolean(PREF_SHUFFLE, true)) shuffle(order);
        } else if (position < 0) {
            position = order.length - 1;
        }

        final int index = order[position];
        final long id = ids[index];
        final int rotation = rotations[index];
        final int generation = ++showGeneration;
        worker.post(() -> {
            if (generation != showGeneration) return;   // already superseded
            final Bitmap bitmap = decode(id, rotation);
            ui.post(() -> {
                if (generation != showGeneration || !started) {
                    if (bitmap != null) bitmap.recycle();
                } else if (bitmap == null) {
                    // unreadable or deleted file: skip it shortly
                    ui.postDelayed(nextTick, 1000);
                } else {
                    display(bitmap);
                    schedule();
                }
            });
        });
    }

    private void schedule() {
        ui.removeCallbacks(nextTick);
        if (started && !paused && order.length > 0) {
            ui.postDelayed(nextTick, prefs.getInt(PREF_INTERVAL, 10) * 1000L);
        }
    }

    /** Cross-fades the new bitmap in over the current one. */
    private void display(Bitmap bitmap) {
        front.animate().cancel();
        back.animate().cancel();

        final ImageView in = back;
        final ImageView out = front;
        Bitmap stale = backBitmap;      // left over if a fade was interrupted
        in.setImageBitmap(bitmap);
        if (stale != null) stale.recycle();

        backBitmap = frontBitmap;
        frontBitmap = bitmap;
        front = in;
        back = out;

        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS);
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (back != out) return;
            out.setImageDrawable(null);
            if (backBitmap != null) {
                backBitmap.recycle();
                backBitmap = null;
            }
        });
    }

    private void clearImages() {
        showGeneration++;
        ui.removeCallbacks(nextTick);
        front.animate().cancel();
        back.animate().cancel();
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        if (frontBitmap != null) frontBitmap.recycle();
        if (backBitmap != null) backBitmap.recycle();
        frontBitmap = null;
        backBitmap = null;
        order = new int[0];
        position = -1;
    }

    /**
     * Decodes a photo scaled down to roughly screen size, so a 12-megapixel
     * picture costs a few MB of memory instead of ~48 MB. Runs on the worker.
     */
    private Bitmap decode(long id, int rotation) {
        Uri uri = ContentUris.withAppendedId(IMAGES, id);
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

            boolean sideways = rotation == 90 || rotation == 270;
            int w = sideways ? opts.outHeight : opts.outWidth;
            int h = sideways ? opts.outWidth : opts.outHeight;
            int sample = 1;
            while (w / (sample * 2) >= screenW || h / (sample * 2) >= screenH) sample *= 2;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap == null || rotation % 360 == 0) return bitmap;

            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- controls

    private void onScreenTapped() {
        if (needsPermission) {
            requestPermissions(new String[]{storagePermission()}, REQ_PERMISSION);
            return;
        }
        if (controls.getVisibility() == View.VISIBLE) {
            ui.removeCallbacks(hideControls);
            controls.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
            keepControls();
        }
    }

    private void keepControls() {
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, CONTROLS_VISIBLE_MS);
    }

    private void togglePause() {
        paused = !paused;
        pauseButton.setText(paused ? "Play" : "Pause");
        keepControls();
        schedule();
    }

    private void showSettings() {
        ui.removeCallbacks(hideControls);
        controls.setVisibility(View.GONE);

        int seconds = prefs.getInt(PREF_INTERVAL, 10);
        String interval = seconds + " seconds";
        for (int i = 0; i < INTERVAL_SECONDS.length; i++) {
            if (INTERVAL_SECONDS[i] == seconds) interval = INTERVAL_LABELS[i];
        }
        String[] items = {
                "Time per photo: " + interval,
                "Order: " + (prefs.getBoolean(PREF_SHUFFLE, true) ? "Shuffle" : "Oldest first"),
                "Album: " + prefs.getString(PREF_ALBUM_NAME, "All photos")};

        new AlertDialog.Builder(this)
                .setTitle("Flipphot settings")
                .setItems(items, (d, which) -> {
                    if (which == 0) chooseInterval();
                    else if (which == 1) chooseOrder();
                    else chooseAlbum();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void chooseInterval() {
        new AlertDialog.Builder(this)
                .setTitle("Time per photo")
                .setItems(INTERVAL_LABELS, (d, which) -> {
                    prefs.edit().putInt(PREF_INTERVAL, INTERVAL_SECONDS[which]).apply();
                    schedule();
                })
                .show();
    }

    private void chooseOrder() {
        new AlertDialog.Builder(this)
                .setTitle("Order")
                .setItems(new String[]{"Shuffle", "Oldest first"}, (d, which) -> {
                    prefs.edit().putBoolean(PREF_SHUFFLE, which == 0).apply();
                    loadPhotos(true);
                })
                .show();
    }

    /** Lists the photo folders on the device and lets the user pick one. */
    private void chooseAlbum() {
        worker.post(() -> {
            final LinkedHashMap<String, String> albums = new LinkedHashMap<>();
            try (Cursor c = getContentResolver().query(
                    IMAGES,
                    new String[]{MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME},
                    null, null,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        if (id == null || albums.containsKey(id)) continue;
                        String name = c.getString(1);
                        albums.put(id, name == null ? "Unnamed folder" : name);
                    }
                }
            } catch (RuntimeException ignored) {
                // show just "All photos"
            }
            ui.post(() -> {
                if (!started || isFinishing()) return;
                final ArrayList<String> albumIds = new ArrayList<>(albums.keySet());
                final String[] names = new String[albumIds.size() + 1];
                names[0] = "All photos";
                for (int i = 0; i < albumIds.size(); i++) names[i + 1] = albums.get(albumIds.get(i));
                new AlertDialog.Builder(this)
                        .setTitle("Album")
                        .setItems(names, (d, which) -> {
                            SharedPreferences.Editor e = prefs.edit();
                            if (which == 0) {
                                e.remove(PREF_ALBUM_ID).remove(PREF_ALBUM_NAME);
                            } else {
                                e.putString(PREF_ALBUM_ID, albumIds.get(which - 1));
                                e.putString(PREF_ALBUM_NAME, names[which]);
                            }
                            e.apply();
                            loadPhotos(true);
                        })
                        .show();
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private void showMessage(String text) {
        messageView.setText(text);
        messageView.setVisibility(View.VISIBLE);
    }

    private static String storagePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void readScreenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
