package com.example.liquidgallery;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static class Photo { long id; String name, album; Uri uri; }
    static class Item { Photo photo; String title; int count; }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Photo> all = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Photo> shown = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final LruCache<Long, Bitmap> cache =
            new LruCache<Long, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
                @Override protected int sizeOf(Long k, Bitmap b) { return b.getByteCount() / 1024; }
            };
    private final ViewOutlineProvider ROUND = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
        }
    };

    private SharedPreferences prefs;
    private Set<String> favs;
    private int tab = 0, cols = 3, index = 0, loadGen = 0, topInset, botInset, sw;
    private int side, cellW, tabW, tabPad;
    private String album = null;
    private float zoom = 1f;
    private boolean viewerOpen, barsVisible = true, favsDirty, hasAccess;
    private int openIndex = -1;
    private float openTx, openTy, openScale = 1f;

    private FrameLayout root, viewer, stage;
    private GridView grid;
    private BaseAdapter adapter;
    private TextView empty, topTitle, vTitle, heartBtn;
    private GlassPanel topBar, tabBar, vTop, vBottom;
    private View indicator, stageBg;
    private ImageView photoView;
    private final TextView[] tabs = new TextView[3];

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setNavigationBarContrastEnforced(false);
        prefs = getSharedPreferences("gallery", MODE_PRIVATE);
        favs = new HashSet<>(prefs.getStringSet("favs", new HashSet<String>()));
        sw = getResources().getDisplayMetrics().widthPixels;
        buildUi();
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hasAccess = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        if (hasAccess) load(); else updateEmpty();
    }

    @Override
    public void onBackPressed() {
        if (viewerOpen) closeViewer();
        else if (tab == 1 && album != null) goBackAlbum();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 99 && res == RESULT_OK) {
            viewerOpen = false;
            viewer.setVisibility(View.GONE);
            photoView.setAlpha(1f);
            load();
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(android.R.color.transparent);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Item it = items.get(pos);
            if (it.title != null) {
                album = it.title;
                rebuild();
                grid.setSelection(0);
                popGrid();
            } else {
                openViewer(pos, v);
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int a, int b, int c) { refreshGlass(); }
        });
        root.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));

        empty = text(17, 0xAAFFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(40), 0, dp(40), 0);
        root.addView(empty, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));

        // top title pill
        topBar = new GlassPanel(this, grid, dp(26));
        topTitle = text(17, 0xFFFFFFFF);
        topTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topTitle.setGravity(Gravity.CENTER);
        topBar.addView(topTitle, new FrameLayout.LayoutParams(MATCH, MATCH));
        topBar.setOnClickListener(v -> {
            bounce(topBar);
            if (tab == 1 && album != null) goBackAlbum(); else grid.smoothScrollToPosition(0);
        });
        root.addView(topBar, new FrameLayout.LayoutParams(sw - dp(96), dp(52), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // bottom tab bar with sliding glass indicator
        int barW = sw - dp(56);
        tabPad = dp(6);
        tabW = (barW - 2 * tabPad) / 3;
        tabBar = new GlassPanel(this, grid, dp(34));
        indicator = new View(this);
        GradientDrawable ind = new GradientDrawable();
        ind.setColor(0x38FFFFFF);
        ind.setCornerRadius(dp(28));
        ind.setStroke(dp(1), 0x66FFFFFF);
        indicator.setBackground(ind);
        tabBar.addView(indicator, new FrameLayout.LayoutParams(tabW, dp(56), Gravity.CENTER_VERTICAL));
        indicator.setTranslationX(tabPad);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(tabPad, 0, tabPad, 0);
        String[] names = {"Photos", "Albums", "Favorites"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            TextView tv = text(14, 0x99FFFFFF);
            tv.setText(names[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(v -> selectTab(t));
            row.addView(tv, new LinearLayout.LayoutParams(0, MATCH, 1f));
            tabs[i] = tv;
        }
        tabBar.addView(row, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(tabBar, new FrameLayout.LayoutParams(barW, dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        styleTabs();

        buildViewer();

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Insets s = ins.getInsets(WindowInsets.Type.systemBars());
            topInset = s.top;
            botInset = s.bottom;
            layoutChrome();
            return ins;
        });
        root.requestApplyInsets();
    }

    private void buildViewer() {
        viewer = new FrameLayout(this);
        viewer.setVisibility(View.GONE);
        stage = new FrameLayout(this);
        stageBg = new View(this);
        stageBg.setBackgroundColor(0xFF000000);
        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(stageBg, MATCH, MATCH);
        stage.addView(photoView, MATCH, MATCH);
        viewer.addView(stage, MATCH, MATCH);

        vTop = new GlassPanel(this, stage, dp(26));
        vTitle = text(16, 0xFFFFFFFF);
        vTitle.setTypeface(Typeface.DEFAULT_BOLD);
        vTitle.setSingleLine(true);
        vTitle.setGravity(Gravity.CENTER_VERTICAL);
        vTitle.setPadding(dp(20), 0, dp(20), 0);
        vTop.addView(vTitle, new FrameLayout.LayoutParams(MATCH, MATCH));
        vTop.setOnClickListener(v -> closeViewer());
        viewer.addView(vTop, new FrameLayout.LayoutParams(sw - dp(56), dp(52), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        vBottom = new GlassPanel(this, stage, dp(34));
        LinearLayout actions = new LinearLayout(this);
        heartBtn = text(28, 0xFFFFFFFF);
        TextView share = text(15, 0xFFFFFFFF);
        TextView del = text(15, 0xFFFF6B6B);
        heartBtn.setText("♡");
        share.setText("Share");
        del.setText("Delete");
        heartBtn.setOnClickListener(v -> toggleFav());
        share.setOnClickListener(v -> { bounce(share); sharePhoto(); });
        del.setOnClickListener(v -> { bounce(del); deletePhoto(); });
        for (TextView tv : new TextView[]{heartBtn, share, del}) {
            tv.setGravity(Gravity.CENTER);
            actions.addView(tv, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        vBottom.addView(actions, new FrameLayout.LayoutParams(MATCH, MATCH));
        viewer.addView(vBottom, new FrameLayout.LayoutParams(sw - dp(96), dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        final ScaleGestureDetector sgd = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        setZoom(zoom * d.getScaleFactor());
                        return true;
                    }
                });
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleBars(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                animateZoom(zoom > 1.05f ? 1f : 2.5f);
                return true;
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (zoom > 1.05f && !sgd.isInProgress()) {
                    float mx = (zoom - 1f) * root.getWidth() / 2f;
                    float my = (zoom - 1f) * root.getHeight() / 2f;
                    photoView.setTranslationX(Math.max(-mx, Math.min(mx, photoView.getTranslationX() - dx)));
                    photoView.setTranslationY(Math.max(-my, Math.min(my, photoView.getTranslationY() - dy)));
                    refreshViewerGlass();
                }
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (zoom > 1.05f) return false;
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 900) navigate(vx < 0 ? 1 : -1);
                else if (vy > 1400) closeViewer();
                return true;
            }
        });
        stage.setOnTouchListener((v, e) -> {
            sgd.onTouchEvent(e);
            gd.onTouchEvent(e);
            return true;
        });
        root.addView(viewer, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void layoutChrome() {
        ((FrameLayout.LayoutParams) topBar.getLayoutParams()).topMargin = topInset + dp(10);
        ((FrameLayout.LayoutParams) vTop.getLayoutParams()).topMargin = topInset + dp(10);
        ((FrameLayout.LayoutParams) tabBar.getLayoutParams()).bottomMargin = botInset + dp(14);
        ((FrameLayout.LayoutParams) vBottom.getLayoutParams()).bottomMargin = botInset + dp(14);
        topBar.requestLayout();
        vTop.requestLayout();
        tabBar.requestLayout();
        vBottom.requestLayout();
        applyGridPadding();
    }

    private void applyGridPadding() {
        grid.setPadding(side, topInset + dp(76), side, botInset + dp(104));
    }

    // ---------------------------------------------------------------- data

    private void load() {
        pool.execute(() -> {
            List<Photo> out = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    Photo p = new Photo();
                    p.id = c.getLong(0);
                    p.name = c.getString(1);
                    p.album = c.getString(2) == null ? "Other" : c.getString(2);
                    p.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id);
                    out.add(p);
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                all.clear();
                all.addAll(out);
                rebuild();
            });
        });
    }

    private boolean isFav(Photo p) {
        return favs.contains(String.valueOf(p.id));
    }

    private void rebuild() {
        items.clear();
        shown.clear();
        boolean albums = tab == 1 && album == null;
        if (albums) {
            Map<String, Item> m = new LinkedHashMap<>();
            for (Photo p : all) {
                Item it = m.get(p.album);
                if (it == null) {
                    it = new Item();
                    it.photo = p;
                    it.title = p.album;
                    m.put(p.album, it);
                }
                it.count++;
            }
            items.addAll(m.values());
        } else {
            for (Photo p : all) {
                if (tab == 2 && !isFav(p)) continue;
                if (tab == 1 && !p.album.equals(album)) continue;
                Item it = new Item();
                it.photo = p;
                items.add(it);
                shown.add(p);
            }
        }
        cols = albums ? 2 : 3;
        int gap = dp(albums ? 14 : 2);
        side = albums ? dp(14) : 0;
        grid.setNumColumns(cols);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        cellW = (sw - 2 * side - (cols - 1) * gap) / cols;
        applyGridPadding();
        adapter.notifyDataSetChanged();
        if (albums) topTitle.setText("Albums");
        else if (tab == 1) topTitle.setText("‹  " + album);
        else topTitle.setText((tab == 2 ? "Favorites" : "Photos") + "  ·  " + shown.size());
        updateEmpty();
        refreshGlass();
    }

    private void updateEmpty() {
        if (!hasAccess) empty.setText("Allow photo access to see your photos");
        else if (tab == 2) empty.setText("No favorites yet\nOpen a photo and tap ♥");
        else empty.setText("No photos found");
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- tabs

    private void selectTab(int t) {
        if (t == tab && album == null) {
            grid.smoothScrollToPosition(0);
            return;
        }
        tab = t;
        album = null;
        styleTabs();
        indicator.setScaleX(1.18f);
        indicator.setScaleY(0.92f);
        indicator.animate().translationX(tabPad + tab * tabW).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(new OvershootInterpolator(1.6f))
                .setUpdateListener(a -> refreshGlass()).start();
        rebuild();
        grid.setSelection(0);
        popGrid();
        root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            tabs[i].setTextColor(i == tab ? 0xFFFFFFFF : 0x99FFFFFF);
            tabs[i].setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void goBackAlbum() {
        album = null;
        rebuild();
        popGrid();
    }

    private void popGrid() {
        grid.setAlpha(0f);
        grid.setScaleX(0.96f);
        grid.setScaleY(0.96f);
        grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .setUpdateListener(a -> refreshGlass()).start();
    }

    // ---------------------------------------------------------------- grid cells

    private class Cell extends FrameLayout {
        final ImageView img;
        final TextView label, heart;

        Cell(Context ctx) {
            super(ctx);
            img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFF1C1C1E);
            addView(img, new LayoutParams(MATCH, MATCH));
            label = text(15, 0xFFFFFFFF);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.BOTTOM);
            label.setPadding(dp(12), dp(40), dp(12), dp(12));
            label.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xB0000000, 0x00000000}));
            addView(label, new LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
            heart = text(16, 0xFFFF5C7A);
            heart.setText("♥");
            heart.setShadowLayer(6f, 0, 0, 0xFF000000);
            LayoutParams lp = new LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(6), dp(8), 0);
            addView(heart, lp);
        }
    }

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Cell c = cv instanceof Cell ? (Cell) cv : new Cell(MainActivity.this);
            Item it = items.get(pos);
            boolean albumMode = it.title != null;
            c.setLayoutParams(new AbsListView.LayoutParams(cellW, cellW));
            c.setOutlineProvider(albumMode ? ROUND : ViewOutlineProvider.BOUNDS);
            c.setClipToOutline(true);
            c.label.setVisibility(albumMode ? View.VISIBLE : View.GONE);
            if (albumMode) {
                c.label.setText(it.title + "\n" + it.count + (it.count == 1 ? " photo" : " photos"));
            }
            c.heart.setVisibility(!albumMode && isFav(it.photo) ? View.VISIBLE : View.GONE);
            loadThumb(it.photo, c.img);
            return c;
        }
    }

    private void loadThumb(Photo p, ImageView iv) {
        iv.animate().cancel();
        iv.setTag(p.id);
        Bitmap cached = cache.get(p.id);
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.setAlpha(1f);
            return;
        }
        iv.setImageDrawable(null);
        iv.setAlpha(0f);
        pool.execute(() -> {
            try {
                Bitmap bm = getContentResolver().loadThumbnail(p.uri, new Size(360, 360), null);
                cache.put(p.id, bm);
                runOnUiThread(() -> {
                    Object t = iv.getTag();
                    if (t != null && (Long) t == p.id) {
                        iv.setImageBitmap(bm);
                        iv.animate().alpha(1f).setDuration(160).setUpdateListener(a -> refreshGlass()).start();
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    // ---------------------------------------------------------------- viewer

    private void openViewer(int pos, View cell) {
        index = pos;
        viewerOpen = true;
        favsDirty = false;
        barsVisible = true;
        viewer.setVisibility(View.VISIBLE);
        showPhoto();

        int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        openIndex = pos;
        openScale = cell.getWidth() / (float) root.getWidth();
        openTx = loc[0] + cell.getWidth() / 2f - root.getWidth() / 2f;
        openTy = loc[1] + cell.getHeight() / 2f - root.getHeight() / 2f;

        photoView.setAlpha(1f);
        photoView.setScaleX(openScale);
        photoView.setScaleY(openScale);
        photoView.setTranslationX(openTx);
        photoView.setTranslationY(openTy);
        stageBg.setAlpha(0f);
        stageBg.animate().alpha(1f).setDuration(280).start();
        photoView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(420).setInterpolator(new OvershootInterpolator(0.7f))
                .setUpdateListener(a -> refreshViewerGlass()).start();
        vTop.setAlpha(0f);
        vTop.setTranslationY(-dp(40));
        vBottom.setAlpha(0f);
        vBottom.setTranslationY(dp(40));
        vTop.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
        vBottom.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
    }

    private void closeViewer() {
        if (!viewerOpen) return;
        viewerOpen = false;
        setZoom(1f);
        stageBg.animate().alpha(0f).setDuration(260).start();
        ViewPropertyAnimator a = photoView.animate().setDuration(340)
                .setInterpolator(new AccelerateDecelerateInterpolator());
        if (index == openIndex) {
            a.scaleX(openScale).scaleY(openScale).translationX(openTx).translationY(openTy);
        } else {
            a.scaleX(0.8f).scaleY(0.8f).alpha(0f);
        }
        a.setUpdateListener(x -> refreshViewerGlass()).withEndAction(() -> {
            viewer.setVisibility(View.GONE);
            photoView.setImageDrawable(null);
            photoView.setAlpha(1f);
            if (favsDirty) rebuild();
        }).start();
        vTop.animate().alpha(0f).translationY(-dp(40)).setStartDelay(0).setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        vBottom.animate().alpha(0f).translationY(dp(40)).setStartDelay(0).setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void showPhoto() {
        Photo p = shown.get(index);
        photoView.setImageBitmap(cache.get(p.id));
        setZoom(1f);
        loadFull(p);
        vTitle.setText("‹   " + p.name);
        styleHeart(p);
    }

    private void styleHeart(Photo p) {
        heartBtn.setText(isFav(p) ? "♥" : "♡");
        heartBtn.setTextColor(isFav(p) ? 0xFFFF5C7A : 0xFFFFFFFF);
    }

    /** Full-quality decode. Animated GIF / WebP start playing automatically. */
    private void loadFull(Photo p) {
        final int gen = ++loadGen;
        pool.execute(() -> {
            try {
                Drawable d = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getContentResolver(), p.uri),
                        (dec, info, src) -> {
                            if (info.getSize().getWidth() > 4096) dec.setTargetSampleSize(2);
                        });
                runOnUiThread(() -> {
                    if (gen != loadGen) return;
                    photoView.setImageDrawable(d);
                    if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
                });
            } catch (Exception ignored) { }
        });
    }

    private void navigate(int dir) {
        final int n = index + dir;
        final float w = root.getWidth();
        if (n < 0 || n >= shown.size()) {
            photoView.setTranslationX(-dir * dp(36));
            photoView.animate().translationX(0f).setDuration(380)
                    .setInterpolator(new OvershootInterpolator(3f))
                    .setUpdateListener(a -> refreshViewerGlass()).start();
            return;
        }
        photoView.animate().translationX(-dir * w * 0.35f).alpha(0f).setDuration(140)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setUpdateListener(a -> refreshViewerGlass())
                .withEndAction(() -> {
                    index = n;
                    showPhoto();
                    photoView.setTranslationX(dir * w * 0.35f);
                    photoView.animate().translationX(0f).alpha(1f).setDuration(380)
                            .setInterpolator(new OvershootInterpolator(1.1f))
                            .setUpdateListener(a -> refreshViewerGlass()).start();
                }).start();
    }

    private void setZoom(float z) {
        zoom = Math.max(1f, Math.min(5f, z));
        photoView.setScaleX(zoom);
        photoView.setScaleY(zoom);
        if (zoom <= 1.0f) {
            photoView.setTranslationX(0f);
            photoView.setTranslationY(0f);
        }
        refreshViewerGlass();
    }

    private void animateZoom(float target) {
        ValueAnimator va = ValueAnimator.ofFloat(zoom, target);
        va.setDuration(280);
        va.setInterpolator(new AccelerateDecelerateInterpolator());
        va.addUpdateListener(a -> setZoom((Float) a.getAnimatedValue()));
        va.start();
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        float a = barsVisible ? 1f : 0f;
        vTop.animate().alpha(a).translationY(barsVisible ? 0 : -dp(30)).setStartDelay(0).setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        vBottom.animate().alpha(a).translationY(barsVisible ? 0 : dp(30)).setStartDelay(0).setDuration(260)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void toggleFav() {
        Photo p = shown.get(index);
        String k = String.valueOf(p.id);
        if (!favs.remove(k)) favs.add(k);
        prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        favsDirty = true;
        styleHeart(p);
        bounce(heartBtn);
        heartBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        refreshViewerGlass();
    }

    private void sharePhoto() {
        Photo p = shown.get(index);
        Intent i = new Intent(Intent.ACTION_SEND);
        String type = getContentResolver().getType(p.uri);
        i.setType(type == null ? "image/*" : type);
        i.putExtra(Intent.EXTRA_STREAM, p.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void deletePhoto() {
        Photo p = shown.get(index);
        try {
            IntentSender sender = MediaStore.createDeleteRequest(getContentResolver(),
                    Collections.singletonList(p.uri)).getIntentSender();
            startIntentSenderForResult(sender, 99, null, 0, 0, 0);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- helpers

    private void refreshGlass() {
        if (topBar != null) topBar.refresh();
        if (tabBar != null) tabBar.refresh();
    }

    private void refreshViewerGlass() {
        if (vTop != null) vTop.refresh();
        if (vBottom != null) vBottom.refresh();
    }

    private void bounce(View v) {
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
