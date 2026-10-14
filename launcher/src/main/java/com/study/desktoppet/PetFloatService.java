package com.study.desktoppet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 全屏透明 Unity 桌宠悬浮窗，但<strong>窗口框等于宠物包围盒</strong>。
 *
 * <p>为什么不能继续 MATCH_PARENT + FLAG_NOT_TOUCHABLE：
 * Unity 透视相机必须铺满屏幕，窗口却不能铺满屏幕。Android 12+ 的 untrusted
 * touch 按<strong>窗口外框</strong>判断遮挡，全屏 overlay 即使全透明、即使设置了
 * touchable region，也会让桌面/下层 App 点不动。
 *
 * <p>做法：
 * <ul>
 *   <li>WindowManager 窗口的 x/y/width/height = Unity 送来的碰撞体屏幕矩形，不加边框。</li>
 *   <li>Unity 视图始终按整块屏幕像素测量，再用负偏移对齐，透视相机和 Screen 尺寸不变。</li>
 *   <li>按下时把窗口临时铺满，避免拖动时手指离开包围盒丢 MOVE；抬起后再裁回去。</li>
 * </ul>
 *
 * <p>由 Unity {@code PetTouchAreaProvider} 调用
 * {@link #updatePetTouchArea(int, int, int, int, int, int)}。
 */
public class PetFloatService extends Service {

    private static final String TAG = "PetFloatService";
    private static final String CHANNEL_ID = "desktop_pet_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile PetFloatService sInstance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Object mUnityPlayer;
    private WindowManager windowManager;
    private OverlayRoot floatView;
    private View unityView;
    private WindowManager.LayoutParams windowParams;

    private int displayWidth;
    private int displayHeight;

    private final Rect pendingRect = new Rect();
    private int pendingUnityW;
    private int pendingUnityH;
    private boolean hasPendingRect;
    private boolean applyScheduled;

    private final Rect petRect = new Rect();
    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private int lastW;
    private int lastH;
    private int lastUnityW;
    private int lastUnityH;

    private boolean gestureActive;
    private boolean unityReady;
    private boolean windowOnScreen;

    private static final int WINDOW_PAD = 64;
    private static final int STABLE_SLACK = 32;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForegroundNotification();
        try {
            createUnityOverlay();
        } catch (Exception e) {
            Log.e(TAG, "createUnityOverlay failed", e);
        }
    }

    private void createUnityOverlay() throws Exception {
        Class<?> unityPlayerClass;
        try {
            unityPlayerClass = Class.forName(
                    "com.unity3d.player.UnityPlayerForActivityOrService");
        } catch (ClassNotFoundException e) {
            unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
        }

        Constructor<?> constructor;
        Object lifecycleEvents = null;
        try {
            Class<?> lifecycleClass = Class.forName(
                    "com.unity3d.player.IUnityPlayerLifecycleEvents");
            constructor = unityPlayerClass.getConstructor(
                    android.content.Context.class, lifecycleClass);
            lifecycleEvents = Proxy.newProxyInstance(
                    lifecycleClass.getClassLoader(),
                    new Class[]{lifecycleClass},
                    (proxy, method, args) -> null);
        } catch (Exception e) {
            try {
                constructor = unityPlayerClass.getConstructor(
                        android.content.Context.class);
            } catch (Exception e2) {
                constructor = unityPlayerClass.getConstructor(
                        android.app.Activity.class);
            }
        }

        if (lifecycleEvents != null) {
            mUnityPlayer = constructor.newInstance(getApplicationContext(), lifecycleEvents);
        } else {
            mUnityPlayer = constructor.newInstance(getApplicationContext());
        }

        try {
            Method method = unityPlayerClass.getMethod("getFrameLayout");
            unityView = (View) method.invoke(mUnityPlayer);
        } catch (NoSuchMethodException e) {
            Method method = unityPlayerClass.getMethod("getView");
            unityView = (View) method.invoke(mUnityPlayer);
        }

        setSurfaceTranslucent(unityView);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        refreshDisplaySize();

        floatView = new OverlayRoot();
        floatView.setBackgroundColor(Color.TRANSPARENT);
        unityView.setClipBounds(null);
        if (unityView.getParent() instanceof ViewGroup) {
            ((ViewGroup) unityView.getParent()).removeView(unityView);
        }
        floatView.addView(unityView, new FrameLayout.LayoutParams(displayWidth, displayHeight));

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        // 先以整屏尺寸把 Unity 拉起来，保证透视相机 / Screen.width 稳定。
        // 初始化期间加上 FLAG_NOT_TOUCHABLE，避免开机这一秒吞掉桌面点击。
        windowParams = new WindowManager.LayoutParams(
                displayWidth,
                displayHeight,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = 0;
        windowParams.y = 0;
        windowParams.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= 34) {
        }
        try {
            java.lang.reflect.Field f =
                    WindowManager.LayoutParams.class.getField("privateFlags");
            f.setInt(windowParams, f.getInt(windowParams) | 0x00000040); // NO_MOVE_ANIMATION
        } catch (Exception ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            windowParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowParams.setFitInsetsTypes(0);
            windowParams.setFitInsetsIgnoringVisibility(true);
        }

        windowManager.addView(floatView, windowParams);
        windowOnScreen = true;
        unityReady = true;

        unityView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            if (w != displayWidth || h != displayHeight) {
                layoutUnityView();
            }
        });

        try {
            unityPlayerClass.getMethod("windowFocusChanged", boolean.class)
                    .invoke(mUnityPlayer, true);
        } catch (Exception ignored) {
        }
        try {
            unityPlayerClass.getMethod("resume").invoke(mUnityPlayer);
        } catch (Exception ignored) {
        }
        try {
            unityPlayerClass.getMethod("onResume").invoke(mUnityPlayer);
        } catch (Exception ignored) {
        }

        floatView.post(this::flushPendingTouchArea);
    }

    /**
     * Unity 坐标已经换成 Android 左上角原点，单位是像素。
     * 碰撞体本身已留空，这里不再加边框。
     */
    public static void updatePetTouchArea(int x, int y, int width, int height) {
        updatePetTouchArea(x, y, width, height, 0, 0);
    }

    /**
     * @param unityW Unity {@code Screen.width}，和窗口像素不一致时做缩放
     * @param unityH Unity {@code Screen.height}
     */
    public static void updatePetTouchArea(
            int x, int y, int width, int height, int unityW, int unityH) {
        PetFloatService service = sInstance;
        if (service == null) {
            return;
        }
        service.queueTouchArea(x, y, width, height, unityW, unityH);
    }

    public static void clearPetTouchArea() {
        updatePetTouchArea(0, 0, 0, 0, 0, 0);
    }

    private void queueTouchArea(
            int x, int y, int width, int height, int unityW, int unityH) {
        synchronized (pendingRect) {
            pendingRect.set(x, y, x + width, y + height);
            pendingUnityW = unityW;
            pendingUnityH = unityH;
            hasPendingRect = true;
            if (applyScheduled) {
                return;
            }
            applyScheduled = true;
        }
        MAIN.post(applyRunnable);
    }

    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            int x;
            int y;
            int w;
            int h;
            int unityW;
            int unityH;
            synchronized (pendingRect) {
                applyScheduled = false;
                if (!hasPendingRect) {
                    return;
                }
                x = pendingRect.left;
                y = pendingRect.top;
                w = pendingRect.width();
                h = pendingRect.height();
                unityW = pendingUnityW;
                unityH = pendingUnityH;
                hasPendingRect = false;
            }
            applyTouchArea(x, y, w, h, unityW, unityH);
        }
    };

    private void flushPendingTouchArea() {
        if (hasPendingRect) {
            applyRunnable.run();
            return;
        }
        if (lastX != Integer.MIN_VALUE) {
            applyTouchArea(lastX, lastY, lastW, lastH, lastUnityW, lastUnityH);
        }
    }

    // 替换 PetFloatService.java 中的 applyTouchArea 方法
    private void applyTouchArea(
            int x, int y, int width, int height, int unityW, int unityH) {
        if (!unityReady || floatView == null || windowParams == null) {
            return;
        }

        refreshDisplaySize();

        if (unityW > 0 && unityH > 0
                && (unityW != displayWidth || unityH != displayHeight)) {
            float sx = displayWidth / (float) unityW;
            float sy = displayHeight / (float) unityH;
            x = Math.round(x * sx);
            y = Math.round(y * sy);
            width = Math.round(width * sx);
            height = Math.round(height * sy);
        }

        lastX = x;
        lastY = y;
        lastW = width;
        lastH = height;
        lastUnityW = unityW;
        lastUnityH = unityH;

        if (gestureActive) {
            return;
        }

        if (width <= 0 || height <= 0) {
            hidePetWindow();
            return;
        }

        Rect clipped = new Rect(x, y, x + width, y + height);
        if (!clipped.intersect(0, 0, displayWidth, displayHeight) || clipped.isEmpty()) {
            hidePetWindow();
            return;
        }

        Rect padded = new Rect(clipped);
        padded.inset(-WINDOW_PAD, -WINDOW_PAD);
        if (!padded.intersect(0, 0, displayWidth, displayHeight) || padded.isEmpty()) {
            hidePetWindow();
            return;
        }

        // 【修改点 1】：只有在微小抖动且尺寸变化不显著时才 Slack 拦截
        // 如果新外框显著小于当前 petRect（缩小操作），必须强制更新窗口，不能 Return！
        int dw = Math.abs(padded.width() - petRect.width());
        int dh = Math.abs(padded.height() - petRect.height());

        // 只有实际窗口仍与缓存的宠物区域一致时才允许跳过更新。
        // 手势期间窗口会扩大到全屏，抬手后必须重新提交宠物区域。
        boolean windowMatchesPetRect = windowParams.x == petRect.left
                && windowParams.y == petRect.top
                && windowParams.width == petRect.width()
                && windowParams.height == petRect.height();

        if (!petRect.isEmpty() && windowMatchesPetRect
                && dw < STABLE_SLACK && dh < STABLE_SLACK
                && clipped.left >= petRect.left
                && clipped.top >= petRect.top
                && clipped.right <= petRect.right
                && clipped.bottom <= petRect.bottom) {
            return;
        }

        petRect.set(padded);
        commitWindowFrame(padded.left, padded.top, padded.width(), padded.height(), true);
    }

    private int slack(boolean hasRoom) {
        return hasRoom ? STABLE_SLACK : 0;
    }

    private void hidePetWindow() {
        petRect.setEmpty();
        commitWindowFrame(-10000, -10000, 1, 1, false);
    }

    private void commitWindowFrame(int x, int y, int width, int height, boolean touchable) {
        if (windowManager == null || floatView == null || windowParams == null) {
            return;
        }

        width = Math.max(1, width);
        height = Math.max(1, height);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!touchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        if (windowParams.x == x
                && windowParams.y == y
                && windowParams.width == width
                && windowParams.height == height
                && windowParams.flags == flags) {
            layoutUnityView();
            return;
        }

        windowParams.x = x;
        windowParams.y = y;
        windowParams.width = width;
        windowParams.height = height;
        windowParams.flags = flags;

        try {
            if (windowOnScreen) {
                windowManager.updateViewLayout(floatView, windowParams);
            }
        } catch (Exception e) {
            Log.w(TAG, "updateViewLayout failed", e);
        }
        layoutUnityView();
    }

    private void setGestureActive(boolean active) {
        if (gestureActive == active) {
            return;
        }
        gestureActive = active;
        if (active) {
            commitWindowFrame(0, 0, displayWidth, displayHeight, true);
        } else if (lastX != Integer.MIN_VALUE) {
            applyTouchArea(lastX, lastY, lastW, lastH, lastUnityW, lastUnityH);
        } else {
            hidePetWindow();
        }
    }

    private final int[] screenLoc = new int[2];

    // 【修改点 2】：修正 ClipBounds 为 View 的 Local 坐标
    private void layoutUnityView() {
        if (unityView == null || floatView == null || windowParams == null) {
            return;
        }

        if (floatView.isAttachedToWindow()) {
            floatView.getLocationOnScreen(screenLoc);
        } else {
            screenLoc[0] = windowParams.x;
            screenLoc[1] = windowParams.y;
        }
        int left = -screenLoc[0];
        int top = -screenLoc[1];

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) unityView.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(displayWidth, displayHeight);
        }
        lp.width = displayWidth;
        lp.height = displayHeight;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.gravity = Gravity.TOP | Gravity.START;
        unityView.setLayoutParams(lp);

        if (gestureActive || petRect.isEmpty()) {
            unityView.setClipBounds(null);
        } else {
            int w = Math.max(1, floatView.getWidth());
            int h = Math.max(1, floatView.getHeight());
            // 【关键修复】：clipBounds 必须使用 unityView 的相对局部坐标，即 (screenLoc[0], screenLoc[1]) 对应的偏移量
            unityView.setClipBounds(new Rect(-left, -top, -left + w, -top + h));
        }
        floatView.requestLayout();
    }

    private void refreshDisplaySize() {
        int w;
        int h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            w = bounds.width();
            h = bounds.height();
        } else {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
            if (windowManager != null) {
                android.util.DisplayMetrics real = new android.util.DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(real);
                w = real.widthPixels;
                h = real.heightPixels;
            }
        }
        if (w > 0) {
            displayWidth = w;
        }
        if (h > 0) {
            displayHeight = h;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshDisplaySize();
        if (!gestureActive && lastX != Integer.MIN_VALUE) {
            applyTouchArea(lastX, lastY, lastW, lastH, lastUnityW, lastUnityH);
        } else {
            layoutUnityView();
        }
    }

    private final class OverlayRoot extends FrameLayout {
        OverlayRoot() {
            super(PetFloatService.this);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && !gestureActive) {
                setGestureActive(true);
            }
            boolean handled;
            try {
                handled = super.dispatchTouchEvent(ev);
            } finally {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    MAIN.post(() -> setGestureActive(false));
                }
            }
            return handled;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(Math.max(w, 1), Math.max(h, 1));
            if (unityView != null) {
                unityView.measure(
                        MeasureSpec.makeMeasureSpec(displayWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(displayHeight, MeasureSpec.EXACTLY));
            }
        }

        // 【修改点 3】：修正 OverlayRoot.onLayout 中的 clipBounds
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (unityView == null) {
                return;
            }
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            int x = -loc[0];
            int y = -loc[1];
            unityView.layout(x, y, x + displayWidth, y + displayHeight);

            if (gestureActive || petRect.isEmpty()) {
                unityView.setClipBounds(null);
            } else {
                // 使用相对局部坐标 (-x, -y)
                unityView.setClipBounds(new Rect(-x, -y, -x + (r - l), -y + (b - t)));
            }
        }
    }

    private void setSurfaceTranslucent(View view) {
        if (view == null) {
            return;
        }
        if (view instanceof SurfaceView) {
            SurfaceView surfaceView = (SurfaceView) view;
            surfaceView.setZOrderOnTop(false);
            surfaceView.setZOrderMediaOverlay(true);
//            surfaceView.setZOrderOnTop(true); // 独立图层，屏幕位置恒为 (0,0)
            surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setSurfaceTranslucent(group.getChildAt(i));
            }
        }
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "桌宠常驻服务",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("桌宠正在运行")
                .setContentText("悬浮窗服务保持后台运行中")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        MAIN.removeCallbacksAndMessages(null);
        try {
            if (mUnityPlayer != null) {
                Class<?> clazz = mUnityPlayer.getClass();
                try {
                    clazz.getMethod("pause").invoke(mUnityPlayer);
                } catch (Exception ignored) {
                }
                try {
                    clazz.getMethod("onPause").invoke(mUnityPlayer);
                } catch (Exception ignored) {
                }
                try {
                    clazz.getMethod("destroy").invoke(mUnityPlayer);
                } catch (Exception ignored) {
                }
            }
            if (windowManager != null && floatView != null && windowOnScreen) {
                windowManager.removeView(floatView);
                windowOnScreen = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy", e);
        }
        mUnityPlayer = null;
        unityView = null;
        floatView = null;
        windowManager = null;
        unityReady = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 彻底退出桌宠服务并清理所有悬浮窗口
     */
    public static void quitAndStopService() {
        PetFloatService service = sInstance;
        if (service == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                // 1. 移除系统悬浮窗
                if (service.windowManager != null && service.floatView != null && service.windowOnScreen) {
                    service.windowManager.removeView(service.floatView);
                    service.windowOnScreen = false;
                }
                // 2. 停止前台服务并销毁
                service.stopForeground(true);
                service.stopSelf();
            } catch (Exception e) {
                Log.e(TAG, "stopService failed", e);
            } finally {
                // 3. 杀死当前进程
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        });
    }
}