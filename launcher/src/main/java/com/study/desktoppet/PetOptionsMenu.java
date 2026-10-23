package com.study.desktoppet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Separate native settings window; navigation never resizes the Unity render window. */
final class PetOptionsMenu {
    interface AccessorySetter { boolean set(String session, int slot, int item, boolean selected); }
    private final Context context;
    private final WindowManager windowManager;
    private final BooleanSupplier isMuted;
    private final Predicate<Boolean> setMuted;
    private final Supplier<PetAccessoryState> accessories;
    private final AccessorySetter setAccessory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout menu;
    private ScrollView itemsScroll;
    private int anchorX, anchorY, screenWidth, screenHeight;
    private boolean accessoryPage, changePending;
    private String menuSession = "";
    private int selectedSlot = -1;
    private final Runnable pendingTimeout = this::onPendingTimeout;

    private void onPendingTimeout() {
        if (!changePending || menu == null) return;
        changePending = false;
        render();
        Toast.makeText(context, "饰品设置未生效，请重试", Toast.LENGTH_SHORT).show();
    }

    PetOptionsMenu(Context context, WindowManager windowManager,
                   BooleanSupplier isMuted, Predicate<Boolean> setMuted,
                   Supplier<PetAccessoryState> accessories, AccessorySetter setAccessory) {
        this.context = new ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar);
        this.windowManager = windowManager;
        this.isMuted = isMuted;
        this.setMuted = setMuted;
        this.accessories = accessories;
        this.setAccessory = setAccessory;
    }

    void show(int anchorX, int anchorY, int screenWidth, int screenHeight) {
        dismiss();
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(16), dp(12), dp(16), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(250, 250, 252));
        background.setCornerRadius(dp(12));
        menu.setBackground(background);
        menu.setElevation(dp(8));
        menu.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { dismiss(); return true; }
            return false;
        });
        render();
    }

    void refreshAccessories() {
        if (menu == null) return;
        cancelPending();
        PetAccessoryState state = accessories.get();
        if (accessoryPage && (!state.enabled || !state.session.equals(menuSession))) {
            accessoryPage = false;
            selectedSlot = -1;
        }
        render();
    }

    private void render() {
        if (menu == null) return;
        PetAccessoryState state = accessories.get();
        if (accessoryPage && (!state.enabled || !state.session.equals(menuSession))) accessoryPage = false;
        int scrollY = itemsScroll == null ? 0 : itemsScroll.getScrollY();
        itemsScroll = null;
        menu.removeAllViews();
        int[] bounds = safeBounds();
        int availableHeight = Math.max(1, bounds[3] - bounds[1]);
        int maxHeight = Math.min(dp(480), availableHeight);
        menu.addView(label(accessoryPage ? "饰品设置" : "桌宠设置", 16));
        if (accessoryPage) buildAccessories(state, maxHeight, scrollY);
        else buildSettings(state);

        int width = Math.min(dp(accessoryPage ? 320 : 220), Math.max(1, bounds[2] - bounds[0]));
        menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        int height = Math.min(menu.getMeasuredHeight(), availableHeight);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, Math.max(1, height), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clamp(anchorX - width / 2, bounds[0], bounds[2] - width);
        int y = anchorY - height - dp(16);
        if (y < bounds[1]) y = anchorY + dp(24);
        params.y = clamp(y, bounds[1], bounds[3] - height);
        params.windowAnimations = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsIgnoringVisibility(true);
        }
        if (menu.isAttachedToWindow()) windowManager.updateViewLayout(menu, params);
        else windowManager.addView(menu, params);
    }

    private void buildSettings(PetAccessoryState state) {
        CheckBox mute = checkBox("静音", isMuted.getAsBoolean());
        menu.addView(mute, rowParams());
        mute.setOnClickListener(v -> {
            if (!setMuted.test(mute.isChecked())) {
                mute.setChecked(isMuted.getAsBoolean());
                Toast.makeText(context, "暂时无法修改声音，请稍后重试", Toast.LENGTH_SHORT).show();
            }
        });
        if (state.enabled) {
            menu.addView(button("饰品控制", () -> {
                PetAccessoryState current = accessories.get();
                if (!current.enabled) { render(); return; }
                accessoryPage = true;
                menuSession = current.session;
                selectedSlot = -1;
                render();
            }), rowParams());
        }
        menu.addView(button("关闭", this::dismiss), rowParams());
    }

    private void buildAccessories(PetAccessoryState state, int maxHeight, int scrollY) {
        PetAccessoryState.Slot selected = null;
        for (PetAccessoryState.Slot slot : state.slots) if (slot.id == selectedSlot) selected = slot;
        if (selected == null && !state.slots.isEmpty()) {
            selected = state.slots.get(0);
            selectedSlot = selected.id;
        }
        HorizontalScrollView tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button activeTab = null;
        for (PetAccessoryState.Slot slot : state.slots) {
            Button tab = button(slot.name, () -> {
                selectedSlot = slot.id;
                itemsScroll = null;
                render();
            });
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setMinWidth(dp(64));
            boolean active = slot.id == selectedSlot;
            if (active) activeTab = tab;
            tab.setBackgroundTintList(ColorStateList.valueOf(active ? Color.rgb(66, 105, 190) : Color.rgb(230, 234, 242)));
            tab.setTextColor(active ? Color.WHITE : Color.rgb(35, 39, 47));
            tabs.addView(tab, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        tabsScroll.addView(tabs);
        Button tabToShow = activeTab;
        if (tabToShow != null) tabsScroll.post(() -> tabsScroll.scrollTo(Math.max(0, tabToShow.getLeft() - dp(8)), 0));
        menu.addView(tabsScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        itemsScroll = new ScrollView(context);
        LinearLayout items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        if (selected == null) items.addView(label("尚未配置饰品位置", 14));
        else if (selected.items.isEmpty()) items.addView(label("这个位置还没有配置饰品", 14));
        else for (PetAccessoryState.Item item : selected.items) {
            final int slotId = selected.id;
            final String itemSession = state.session;
            CheckBox box = checkBox(item.name, item.selected);
            box.setEnabled(!changePending);
            box.setOnClickListener(v -> {
                PetAccessoryState current = accessories.get();
                PetAccessoryState.Item actual = current.findItem(slotId, item.id);
                if (changePending || !accessoryPage || !current.enabled || !current.session.equals(itemSession)
                        || !menuSession.equals(itemSession) || actual == null) {
                    box.setChecked(actual != null && actual.selected);
                    return;
                }
                if (!setAccessory.set(itemSession, slotId, item.id, box.isChecked())) {
                    box.setChecked(actual.selected);
                    Toast.makeText(context, "暂时无法修改饰品，请稍后重试", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Unity acknowledges with its actual instances; do not assume enqueueing means success.
                changePending = true;
                box.setEnabled(false);
                main.postDelayed(pendingTimeout, 3000);
            });
            items.addView(box, rowParams());
        }
        itemsScroll.addView(items);
        menu.addView(itemsScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, maxHeight - dp(148))));
        ScrollView currentScroll = itemsScroll;
        currentScroll.post(() -> currentScroll.scrollTo(0, scrollY));
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.addView(button("返回设置", () -> { accessoryPage = false; render(); }),
                new LinearLayout.LayoutParams(0, dp(48), 1f));
        footer.addView(button("关闭", this::dismiss), new LinearLayout.LayoutParams(0, dp(48), 1f));
        menu.addView(footer);
    }

    private int[] safeBounds() {
        int margin = dp(8);
        int left = margin, top = dp(32), right = screenWidth - margin, bottom = screenHeight - dp(24);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Insets insets = windowManager.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = Math.max(left, insets.left + margin);
            top = Math.max(top, insets.top + margin);
            right = Math.min(right, screenWidth - insets.right - margin);
            bottom = Math.min(bottom, screenHeight - insets.bottom - margin);
        }
        return new int[]{left, top, right, bottom};
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(35, 39, 47));
        return view;
    }

    private CheckBox checkBox(String name, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(name);
        box.setTextSize(16);
        box.setTextColor(Color.rgb(35, 39, 47));
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        return box;
    }

    private Button button(String name, Runnable action) {
        Button button = new Button(context);
        button.setText(name);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void cancelPending() {
        main.removeCallbacks(pendingTimeout);
        changePending = false;
    }

    void dismiss() {
        cancelPending();
        accessoryPage = false;
        menuSession = "";
        selectedSlot = -1;
        itemsScroll = null;
        if (menu == null) return;
        View previous = menu;
        menu = null;
        if (previous.isAttachedToWindow()) windowManager.removeView(previous);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }
}
