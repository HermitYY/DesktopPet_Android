package com.study.desktoppet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A separate small overlay: opening settings never resizes the Unity render window. */
final class PetOptionsMenu {
    private final Context context;
    private final WindowManager windowManager;
    private final BooleanSupplier isMuted;
    private final Predicate<Boolean> setMuted;
    private View menu;

    PetOptionsMenu(Context context, WindowManager windowManager,
                   BooleanSupplier isMuted, Predicate<Boolean> setMuted) {
        this.context = new ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar);
        this.windowManager = windowManager;
        this.isMuted = isMuted;
        this.setMuted = setMuted;
    }

    void show(int anchorX, int anchorY, int screenWidth, int screenHeight) {
        dismiss();
        int margin = dp(8);
        int left = margin, top = dp(32), right = screenWidth - margin;
        int bottom = screenHeight - dp(24);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Insets insets = windowManager.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = Math.max(left, insets.left + margin);
            top = Math.max(top, insets.top + margin);
            right = Math.min(right, screenWidth - insets.right - margin);
            bottom = Math.min(bottom, screenHeight - insets.bottom - margin);
        }
        int width = Math.min(dp(220), Math.max(1, right - left));
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(250, 250, 252));
        background.setCornerRadius(dp(12));
        panel.setBackground(background);
        panel.setElevation(dp(8));

        TextView title = new TextView(context);
        title.setText("桌宠设置");
        title.setTextSize(16);
        title.setTextColor(Color.rgb(35, 39, 47));
        panel.addView(title);

        CheckBox mute = new CheckBox(context);
        mute.setText("静音");
        mute.setTextSize(16);
        mute.setTextColor(Color.rgb(35, 39, 47));
        mute.setChecked(isMuted.getAsBoolean());
        panel.addView(mute, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        // Click rather than checked-change keeps rollback from recursively changing the setting.
        mute.setOnClickListener(v -> {
            if (!setMuted.test(mute.isChecked())) {
                mute.setChecked(isMuted.getAsBoolean());
                Toast.makeText(context, "暂时无法修改声音，请稍后重试", Toast.LENGTH_SHORT).show();
            }
        });

        Button close = new Button(context);
        close.setText("关闭");
        close.setOnClickListener(v -> dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        panel.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(panel.getMeasuredHeight(), Math.max(1, bottom - top));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, Math.max(1, height), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clamp(anchorX - width / 2, left, right - width);
        int y = anchorY - height - dp(16);
        if (y < top) y = anchorY + dp(24);
        params.y = clamp(y, top, bottom - height);
        params.windowAnimations = 0;
        // The rendered surface and all UI coordinates use the physical display, including insets.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsIgnoringVisibility(true);
        }
        windowManager.addView(panel, params);
        menu = panel;
    }

    void dismiss() {
        if (menu == null) return;
        View previous = menu;
        menu = null;
        if (previous.isAttachedToWindow()) windowManager.removeView(previous);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }
}
