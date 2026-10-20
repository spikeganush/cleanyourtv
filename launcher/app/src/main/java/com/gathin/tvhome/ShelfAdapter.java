package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * One horizontal row of the shelf above the dock: 16:9 picture cards, or compact control cards (icon,
 * name, state) for Home Assistant. The focused card is ringed and lifted.
 */
final class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

    interface Listener {
        void onShelfFocused(ShelfAdapter row, Shelf.Item item, int position);
        void onShelfClicked(Shelf.Item item);
        /** Up or Down on a card; return true when handled. */
        boolean onShelfVerticalKey(ShelfAdapter row, int keyCode);
        /** Left/Right in brightness mode: change a light by delta percent. */
        void onShelfAdjust(ShelfAdapter row, Shelf.Item item, int delta);
        /** Brightness mode ended. */
        void onShelfAdjustEnd(ShelfAdapter row, Shelf.Item item);
    }

    private final Listener listener;
    private final List<Shelf.Item> items = new ArrayList<>();
    private final boolean animate;
    int lastFocused;
    // Brightness mode: the light card held with OK, whose Left/Right now dim instead of move.
    private String adjustingKey;
    private boolean swallowCenterUp;

    ShelfAdapter(Listener listener, boolean animate) {
        this.listener = listener;
        this.animate = animate;
    }

    void setItems(List<Shelf.Item> list) {
        boolean sameSize = list.size() == items.size();
        items.clear();
        items.addAll(list);
        if (lastFocused >= items.size()) lastFocused = Math.max(0, items.size() - 1);
        // Same number of cards: rebind in place, so a focused card keeps its focus (a toggled light).
        if (sameSize) notifyItemRangeChanged(0, items.size());
        else notifyDataSetChanged();
    }

    boolean isAdjusting() {
        return adjustingKey != null;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    Shelf.Item itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    private static final int PICTURE = 0, CONTROL = 1;

    @Override
    public int getItemViewType(int position) {
        return items.get(position).icon != 0 ? CONTROL : PICTURE;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(
                viewType == CONTROL ? R.layout.item_shelf_tile : R.layout.item_shelf, parent, false);
        float radius = 10 * parent.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Shelf.Item item = items.get(position);
        if (h.icon != null) {
            h.icon.setImageResource(item.icon);
            h.icon.setImageTintList(ColorStateList.valueOf(item.active ? 0xFFFFD60A : 0x99FFFFFF));
            h.name.setText(item.title);
            boolean adjusting = item.key.equals(adjustingKey);
            h.value.setText(adjusting ? "\u25C0  " + item.brightness + "%  \u25B6" : item.value);
            h.itemView.setContentDescription(item.title + ", " + item.value);
        } else if (item.image != null) {
            h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.image.setImageBitmap(item.image);
        } else {
            h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            h.image.setImageDrawable(item.drawable);
        }
        if (h.icon == null) h.itemView.setContentDescription(item.title);
        boolean showProgress = item.progress > 0f && h.progress != null;
        if (h.progress != null) {
            h.track.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            h.progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        }
        if (showProgress) {
            ViewGroup.LayoutParams lp = h.progress.getLayoutParams();
            lp.width = Math.round(h.itemView.getLayoutParams().width * Math.min(1f, item.progress));
            h.progress.setLayoutParams(lp);
        }
        h.itemView.setOnClickListener(v -> listener.onShelfClicked(item));
        if (item.brightness >= 0) {
            h.itemView.setOnLongClickListener(v -> {
                adjustingKey = item.key;
                swallowCenterUp = true;   // the release of the held OK must not toggle the light
                notifyItemChanged(h.getBindingAdapterPosition());
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                return true;
            });
        } else {
            h.itemView.setOnLongClickListener(null);
        }
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.08f : 1f;
            if (animate) v.animate().scaleX(s).scaleY(s).setDuration(140).start();
            else { v.setScaleX(s); v.setScaleY(s); }
            int pos = h.getBindingAdapterPosition();
            if (focused && pos != RecyclerView.NO_POSITION) {
                lastFocused = pos;
                listener.onShelfFocused(this, items.get(pos), pos);
            }
        });
        h.itemView.setOnKeyListener((v, keyCode, event) -> {
            boolean select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
            if (event.getAction() == KeyEvent.ACTION_UP && select && swallowCenterUp) {
                swallowCenterUp = false;
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && items.get(pos).key.equals(adjustingKey)) {
                Shelf.Item it = items.get(pos);
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getRepeatCount() == 0 || event.getRepeatCount() % 3 == 0) {
                        listener.onShelfAdjust(this, it, keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -10 : 10);
                        notifyItemChanged(pos);
                    }
                    return true;
                }
                if (select || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getRepeatCount() > 0) return true;   // still holding OK from entering
                    adjustingKey = null;
                    if (select) swallowCenterUp = true;
                    notifyItemChanged(pos);
                    listener.onShelfAdjustEnd(this, it);
                    if (select || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return true;
                }
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return listener.onShelfVerticalKey(this, keyCode);
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return pos == 0;                       // row start: stay put
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return pos == items.size() - 1;        // row end: stay put
                default:
                    return false;
            }
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        // Picture cards: image, track, progress. Control cards: icon, name, value. The others stay null.
        final ImageView image, icon;
        final View track, progress;
        final TextView name, value;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.shelfImage);
            track = v.findViewById(R.id.shelfTrack);
            progress = v.findViewById(R.id.shelfProgress);
            icon = v.findViewById(R.id.tileIcon);
            name = v.findViewById(R.id.tileName);
            value = v.findViewById(R.id.tileValue);
        }
    }
}
