package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


/**
 * The remote's Input button (KEYCODE_TV) opens a panel of the Home Assistant room cards (ha.json, see
 * HomeAssistant) over whatever is on screen. It is an accessibility overlay, so the app underneath is not
 * paused. Input again or Back closes it, and it hides itself after a while without a key. Without ha.json
 * the key passes through untouched. Enabled through LocalShell by MainActivity.
 */
public class HomePanelService extends AccessibilityService implements ShelfAdapter.Listener {

    private static final long IDLE_HIDE_MS = 20_000;
    /** The running service, for the launcher's Projector card (same process). */
    static HomePanelService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleHide = this::hide;
    private WindowManager windowManager;
    private View panel;
    private TextView title;
    private RecyclerView list;
    private ShelfAdapter adapter;
    private boolean loading;
    private Runnable pendingBrightness;
    // "Darker than level 1": a black layer over everything, added first so the panel always sits above
    // it. Not focusable or touchable; 100 (no layer) after every start, so a reboot never leaves it dark.
    private View dimLayer;
    private int pictureLevel = 100;

    @Override
    protected void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
        dimLayer = new View(this);
        dimLayer.setBackgroundColor(0xFF000000);
        dimLayer.setAlpha(0f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowManager.addView(dimLayer, lp);
    }

    int pictureLevel() {
        return pictureLevel;
    }

    /** 100: the picture as is; lower: that much of it shows through the black layer; 0: black. */
    void setPictureLevel(int level) {
        pictureLevel = Math.max(0, Math.min(100, level));
        if (dimLayer != null) dimLayer.setAlpha((100 - pictureLevel) / 100f);
    }

    /** The Projector card: OK opens JMGO's own brightness (levels 1 to 10), holding OK dims below that. */
    static Shelf.Item projectorCard(Context context) {
        Intent jmgo = new Intent("android.settings.JMGO_LIGHT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Shelf.Item item = new Shelf.Item(PROJECTOR_KEY, context.getString(R.string.projector_card), -1f, jmgo);
        item.icon = R.drawable.ic_ha_projector;
        HomePanelService s = instance;
        item.brightness = s != null ? s.pictureLevel : -1;
        item.active = item.brightness >= 0;
        item.value = s == null ? context.getString(R.string.projector_no_service) : pictureText(item.brightness);
        return item;
    }

    static final String PROJECTOR_KEY = "local:projector";

    static String pictureText(int level) {
        return level >= 100 ? "Picture 100%" : "Dimmed · " + level + "%";
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_TV) return false;
        if (HomeAssistant.config(this) == null) return false;   // no room configured: leave the key alone
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (panel != null) hide();
            else show();
        }
        return true;
    }

    private void show() {
        float density = getResources().getDisplayMetrics().density;
        // A FrameLayout that sees the keys after its children: Back closes the panel unless a card used it.
        FrameLayout root = new FrameLayout(this) {
            private boolean backTaken;

            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                handler.removeCallbacks(idleHide);
                handler.postDelayed(idleHide, IDLE_HIDE_MS);
                boolean handled = super.dispatchKeyEvent(e);
                if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return handled;
                if (e.getAction() == KeyEvent.ACTION_DOWN) backTaken = handled;
                else if (!backTaken) hide();
                return true;
            }
        };
        View content = LayoutInflater.from(this).inflate(R.layout.home_panel, root, false);
        root.addView(content);
        title = content.findViewById(R.id.panelTitle);
        list = content.findViewById(R.id.panelList);
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setItemAnimator(null);
        adapter = new ShelfAdapter(this, true);
        list.setAdapter(adapter);
        title.setText(R.string.panel_loading);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.dimAmount = 0.25f;
        lp.y = Math.round(36 * density);
        windowManager.addView(root, lp);
        panel = root;
        content.setAlpha(0f);
        content.setTranslationY(24 * density);
        content.animate().alpha(1f).translationY(0f).setDuration(180).start();
        handler.postDelayed(idleHide, IDLE_HIDE_MS);
        load(true);
    }

    private void hide() {
        handler.removeCallbacks(idleHide);
        if (panel == null) return;
        View p = panel;
        panel = null;
        try {
            windowManager.removeView(p);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void load(boolean focusFirst) {
        if (loading) return;
        loading = true;
        Context app = getApplicationContext();
        new Thread(() -> {
            HomeAssistant.Config ha = HomeAssistant.config(app);
            HomeAssistant.Room room = ha == null ? null : HomeAssistant.load(ha);
            handler.post(() -> {
                loading = false;
                if (panel == null) return;
                if (room == null) {
                    title.setText(R.string.panel_unreachable);
                    return;
                }
                title.setText(room.title);
                room.cards.add(projectorCard(app));
                if (!adapter.isAdjusting()) adapter.setItems(room.cards);
                if (focusFirst) list.post(() -> {
                    RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(adapter.lastFocused);
                    if (vh != null) vh.itemView.requestFocus();
                });
            });
        }, "panel-load").start();
    }

    // ---- cards ------------------------------------------------------------------------------------

    @Override
    public void onShelfFocused(ShelfAdapter row, Shelf.Item item, int position) {
    }

    @Override
    public void onShelfClicked(Shelf.Item item) {
        if (item.entityId == null) {   // the Projector card: JMGO's own brightness screen
            hide();
            try {
                startActivity(new Intent(item.intent));
            } catch (android.content.ActivityNotFoundException ignored) {
            }
            return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            HomeAssistant.Config ha = HomeAssistant.config(app);
            boolean ok = ha != null && HomeAssistant.press(ha, item);
            handler.postDelayed(() -> load(false), ok ? 400 : 0);
        }, "panel-press").start();
    }

    @Override
    public void onShelfHold(ShelfAdapter row, Shelf.Item item) {
        Context app = getApplicationContext();
        new Thread(() -> {
            HomeAssistant.Config ha = HomeAssistant.config(app);
            boolean ok = ha != null && HomeAssistant.restart(ha, item);
            handler.postDelayed(() -> load(false), ok ? 400 : 0);
        }, "panel-hold").start();
    }

    @Override
    public boolean onShelfVerticalKey(ShelfAdapter row, int keyCode) {
        return true;   // one row: Up and Down stay put
    }

    @Override
    public void onShelfAdjust(ShelfAdapter row, Shelf.Item item, int delta) {
        if (PROJECTOR_KEY.equals(item.key)) {
            setPictureLevel(item.brightness + delta);
            item.brightness = pictureLevel;
            item.value = pictureText(pictureLevel);
            return;
        }
        item.brightness = Math.max(0, Math.min(100, item.brightness + delta));
        item.active = item.brightness > 0;
        item.value = item.brightness > 0 ? "On · " + item.brightness + "%" : "Off";
        if (pendingBrightness != null) handler.removeCallbacks(pendingBrightness);
        int pct = item.brightness;
        Context app = getApplicationContext();
        pendingBrightness = () -> new Thread(() -> {
            HomeAssistant.Config ha = HomeAssistant.config(app);
            if (ha != null) HomeAssistant.setBrightness(ha, item.entityId, pct);
        }, "panel-brightness").start();
        handler.postDelayed(pendingBrightness, 250);
    }

    @Override
    public void onShelfAdjustEnd(ShelfAdapter row, Shelf.Item item) {
        handler.postDelayed(() -> load(false), 800);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        hide();
    }

    @Override
    public void onDestroy() {
        hide();
        if (dimLayer != null) {
            try {
                windowManager.removeView(dimLayer);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
