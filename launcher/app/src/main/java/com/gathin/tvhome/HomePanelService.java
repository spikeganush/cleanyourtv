package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleHide = this::hide;
    private WindowManager windowManager;
    private View panel;
    private TextView title;
    private RecyclerView list;
    private ShelfAdapter adapter;
    private boolean loading;
    private Runnable pendingBrightness;

    @Override
    protected void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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
        super.onDestroy();
    }
}
