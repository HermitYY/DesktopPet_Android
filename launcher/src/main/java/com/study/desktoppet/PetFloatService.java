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
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;

/**
 * 全屏透明 Unity 桌宠悬浮窗，但<strong>窗口框只覆盖宠物及稳定边距</strong>。
 *
 * <p>为什么不能继续 MATCH_PARENT + FLAG_NOT_TOUCHABLE：
 * Unity 透视相机必须铺满屏幕，窗口却不能铺满屏幕。Android 12+ 的 untrusted
 * touch 按<strong>窗口外框</strong>判断遮挡，全屏 overlay 即使全透明、即使设置了
 * touchable region，也会让桌面/下层 App 点不动。
 *
 * <p>做法：
 * <ul>
 *   <li>WindowManager 窗口只覆盖 Unity 送来的碰撞体屏幕矩形及稳定边距。</li>
 *   <li>Unity 视图始终按整块屏幕像素测量，再用负偏移对齐，透视相机和 Screen 尺寸不变。</li>
 *   <li>点击按下不改变窗口；拖动或多指操作时切到全屏，期间不再追着模型改窗口。</li>
 *   <li>全屏布局完成后才转发移动事件，抬手后恢复最新宠物区域。</li>
 *   <li>触摸显式转换为屏幕坐标，窗口移动不会改变 Unity 收到的手指位置。</li>
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
    private PetOptionsMenu optionsMenu;
    private PetAccessoryState accessoryState = PetAccessoryState.DISABLED;
    // Session setting: every fresh service starts with sound on; model changes keep this value.
    private volatile boolean petMuted;

    private int displayWidth;
    private int displayHeight;

    private final Rect pendingRect = new Rect();
    private int pendingUnityW;
    private int pendingUnityH;
    private boolean hasPendingRect;
    private boolean applyScheduled;

    private final Rect petRect = new Rect();
    // 缓存 Unity 原始坐标，重放时只能缩放一次。
    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private int lastW;
    private int lastH;
    private int lastUnityW;
    private int lastUnityH;

    private boolean gestureActive;
    private boolean fullScreenGesture;
    private boolean unityReady;
    private boolean windowOnScreen;

    private void traceInput(String stage) {
        Log.i(TAG, stage + " active=" + gestureActive + " full=" + fullScreenGesture
                + " ready=" + (floatView != null && floatView.fullScreenLayoutReady)
                + " queued=" + (floatView == null ? 0 : floatView.deferredTouchEvents.size())
                + " frame=" + (windowParams == null ? "none" : windowParams.x + ","
                + windowParams.y + "," + windowParams.width + "," + windowParams.height));
    }

    private static final int WINDOW_PAD = 60;
    private static final int STABLE_SLACK = 24;

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
        disableWindowMoveAnimation();

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

    private void disableWindowMoveAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                // API 34 的公开接口；使用反射兼容当前 compileSdkVersion 33。
                WindowManager.LayoutParams.class
                        .getMethod("setCanPlayMoveAnimation", boolean.class)
                        .invoke(windowParams, false);
                Log.i(TAG, "Window move animation disabled via public API");
                return;
            } catch (Exception e) {
                Log.w(TAG, "Public window move animation API failed", e);
            }
        }
        try {
            java.lang.reflect.Field f =
                    WindowManager.LayoutParams.class.getField("privateFlags");
            f.setInt(windowParams, f.getInt(windowParams) | 0x00000040);
        } catch (Exception e) {
            Log.w(TAG, "Could not disable legacy window move animation", e);
        }
    }

    /**
     * Unity 坐标已经换成 Android 左上角原点，单位是像素。
     * Android 端再加稳定边距，减少动作引起的频繁窗口更新。
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

    public static boolean isPetMuted() {
        PetFloatService service = sInstance;
        return service != null && service.petMuted;
    }

    /** Unity publishes both the available accessories and actual checked states on its game thread. */
    public static void updatePetAccessories(String json) {
        PetFloatService service = sInstance;
        if (service == null) return;
        MAIN.post(() -> {
            if (sInstance != service) return;
            try {
                service.accessoryState = PetAccessoryState.fromJson(json);
                if (service.optionsMenu != null) service.optionsMenu.refreshAccessories();
            } catch (Exception exception) {
                Log.w(TAG, "Could not read accessory settings", exception);
            }
        });
    }

    /** Called after Unity confirms a three-second stationary press actually hit the pet. */
    public static void showPetOptionsMenu(int x, int y, int unityW, int unityH) {
        PetFloatService service = sInstance;
        if (service == null) return;
        MAIN.post(() -> {
            if (sInstance != service || !service.unityReady || !service.windowOnScreen) return;
            service.refreshDisplaySize();
            int screenX = unityW > 0 ? Math.round(x * (service.displayWidth / (float) unityW)) : x;
            int screenY = unityH > 0 ? Math.round(y * (service.displayHeight / (float) unityH)) : y;
            if (service.optionsMenu == null) {
                service.optionsMenu = new PetOptionsMenu(service, service.windowManager,
                        () -> service.petMuted, service::setPetMuted,
                        () -> service.accessoryState, service::setPetAccessory);
            }
            try {
                service.optionsMenu.show(screenX, screenY, service.displayWidth, service.displayHeight);
            } catch (Exception exception) {
                Log.w(TAG, "Could not open pet options", exception);
            }
        });
    }

    private boolean setPetAccessory(String session, int slot, int item, boolean selected) {
        if (!unityReady || mUnityPlayer == null || !accessoryState.enabled
                || !accessoryState.session.equals(session) || accessoryState.findItem(slot, item) == null) return false;
        try {
            String command = new org.json.JSONObject().put("session", session).put("slot", slot)
                    .put("item", item).put("selected", selected).toString();
            Class.forName("com.unity3d.player.UnityPlayer")
                    .getMethod("UnitySendMessage", String.class, String.class, String.class)
                    .invoke(null, "__DesktopPetAccessories", "SetAccessorySelection", command);
            return true;
        } catch (Exception exception) {
            Log.w(TAG, "Could not change pet accessory", exception);
            return false;
        }
    }

    private boolean setPetMuted(boolean muted) {
        if (!unityReady || mUnityPlayer == null) return false;
        boolean previous = petMuted;
        petMuted = muted;
        try {
            Class.forName("com.unity3d.player.UnityPlayer")
                    .getMethod("UnitySendMessage", String.class, String.class, String.class)
                    .invoke(null, "__DesktopPetAudioSettings", "SetMuted", muted ? "true" : "false");
            return true;
        } catch (Exception exception) {
            petMuted = previous;
            Log.w(TAG, "Could not change pet audio", exception);
            return false;
        }
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

    private void applyTouchArea(
            int x, int y, int width, int height, int unityW, int unityH) {
        if (!unityReady || floatView == null || windowParams == null) {
            return;
        }

        lastX = x;
        lastY = y;
        lastW = width;
        lastH = height;
        lastUnityW = unityW;
        lastUnityH = unityH;

        // 全屏手势期间只缓存最新区域。Unity 可以立即移动，窗口无需逐帧追赶。
        if (fullScreenGesture) {
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

        if (width <= 0 || height <= 0) {
            if (!gestureActive) hidePetWindow();
            return;
        }

        Rect clipped = new Rect(x, y, x + width, y + height);
        if (!clipped.intersect(0, 0, displayWidth, displayHeight) || clipped.isEmpty()) {
            if (!gestureActive) hidePetWindow();
            return;
        }

        Rect padded = new Rect(clipped);
        padded.inset(-WINDOW_PAD, -WINDOW_PAD);
        if (!padded.intersect(0, 0, displayWidth, displayHeight) || padded.isEmpty()) {
            if (!gestureActive) hidePetWindow();
            return;
        }

        // 微小变化可复用当前窗口，明显缩小时仍需更新。
        int dw = Math.abs(padded.width() - petRect.width());
        int dh = Math.abs(padded.height() - petRect.height());

        // 只有窗口参数仍与缓存的宠物区域一致时才允许跳过更新。
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
            return;
        }

        windowParams.x = x;
        windowParams.y = y;
        windowParams.width = width;
        windowParams.height = height;
        windowParams.flags = flags;

        traceInput("window-request");

        try {
            if (windowOnScreen) {
                windowManager.updateViewLayout(floatView, windowParams);
            }
        } catch (Exception e) {
            Log.w(TAG, "updateViewLayout failed", e);
        }
        layoutUnityView();
    }

    private final Runnable endGestureRunnable = () -> {
        traceInput("gesture-end");
        gestureActive = false;
        fullScreenGesture = false;
        if (floatView != null) floatView.fullScreenLayoutReady = false;
        if (lastX != Integer.MIN_VALUE) {
            applyTouchArea(lastX, lastY, lastW, lastH, lastUnityW, lastUnityH);
        }
    };

    // 这里只请求布局。updateViewLayout 返回后，屏幕位置/窗口尺寸可能仍是旧值。
    // 偏移与裁剪统一在 OverlayRoot.onLayout 中按本次提交的窗口框计算。
    private void layoutUnityView() {
        if (unityView == null || floatView == null || windowParams == null) {
            return;
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) unityView.getLayoutParams();
        if (lp == null || lp.width != displayWidth || lp.height != displayHeight) {
            lp = new FrameLayout.LayoutParams(displayWidth, displayHeight);
            lp.gravity = Gravity.TOP | Gravity.START;
            unityView.setLayoutParams(lp);
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
        if (optionsMenu != null) optionsMenu.dismiss();
        refreshDisplaySize();
        if (fullScreenGesture) {
            floatView.fullScreenLayoutReady = false;
            commitWindowFrame(0, 0, displayWidth, displayHeight, true);
        } else if (lastX != Integer.MIN_VALUE) {
            applyTouchArea(lastX, lastY, lastW, lastH, lastUnityW, lastUnityH);
        }
        layoutUnityView();
    }

    private final class OverlayRoot extends FrameLayout {
        private final ArrayDeque<MotionEvent> deferredTouchEvents = new ArrayDeque<>();
        private final int dragSlop;
        private float downRawX;
        private float downRawY;
        private boolean fullScreenLayoutReady;
        private boolean flushPosted;

        OverlayRoot() {
            super(PetFloatService.this);
            setMotionEventSplittingEnabled(false);
            // 提前于 Unity 的拖动阈值准备窗口，微小点击抖动仍不扩窗。
            dragSlop = Math.max(1, Math.min(
                    ViewConfiguration.get(PetFloatService.this).getScaledTouchSlop(),
                    WINDOW_PAD / 4));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                MAIN.removeCallbacks(endGestureRunnable);
                gestureActive = true;
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
            }
            if (action != MotionEvent.ACTION_MOVE) traceInput("receive " + ev);
            if (unityView == null) {
                gestureActive = false;
                return false;
            }

            // Unity 的视口始终覆盖整屏。直接送入屏幕坐标，避免父窗口重布局
            // 期间的旧 child.left/top 把静止手指变成一次虚假的拖动。
            MotionEvent screenEvent = MotionEvent.obtain(ev);
            screenEvent.offsetLocation(ev.getRawX() - ev.getX(), ev.getRawY() - ev.getY());

            float dx = ev.getRawX() - downRawX;
            float dy = ev.getRawY() - downRawY;
            boolean moved = dx * dx + dy * dy >= dragSlop * dragSlop;
            if (gestureActive && !fullScreenGesture
                    && (action == MotionEvent.ACTION_POINTER_DOWN
                    || ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                    && moved))) {
                fullScreenGesture = true;
                fullScreenLayoutReady = false;
                refreshDisplaySize();
                commitWindowFrame(0, 0, displayWidth, displayHeight, true);
                // 模型切换的过渡区域可能已是全屏，commitWindowFrame 会跳过相同参数。
                // 仍需请求一次布局以更新 ready 并发送暂存事件，避免 UP/CANCEL 永久滞留。
                layoutUnityView();
                traceInput("prepare-full-screen");
            }

            if (fullScreenGesture && (!fullScreenLayoutReady || flushPosted)) {
                // 窗口还没铺满时不能先让 Unity 移动，否则第一帧快速拖动仍会被裁剪。
                // 连续 MOVE 只保留最新位置；多指按下/抬起等事件保持顺序。
                MotionEvent previous = deferredTouchEvents.peekLast();
                if (action == MotionEvent.ACTION_MOVE && previous != null
                        && previous.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    deferredTouchEvents.removeLast().recycle();
                }
                deferredTouchEvents.addLast(screenEvent);
                return true;
            }
            return dispatchScreenTouchEvent(screenEvent);
        }

        private boolean dispatchScreenTouchEvent(MotionEvent screenEvent) {
            int action = screenEvent.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                MAIN.removeCallbacks(endGestureRunnable);
                gestureActive = true;
            }
            boolean handled;
            long started = System.nanoTime();
            try {
                handled = unityView.dispatchTouchEvent(screenEvent);
                if (action != MotionEvent.ACTION_MOVE || !handled) {
                    traceInput("forward action=" + action + " handled=" + handled);
                }
            } finally {
                long elapsedMs = (System.nanoTime() - started) / 1000000;
                if (elapsedMs >= 100) traceInput("slow-forward ms=" + elapsedMs);
                screenEvent.recycle();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    MAIN.post(endGestureRunnable);
                }
            }
            return handled;
        }

        private void flushDeferredTouchEvents() {
            flushPosted = false;
            if (!fullScreenLayoutReady || unityView == null) return;
            traceInput("flush-touch");
            while (!deferredTouchEvents.isEmpty()) {
                dispatchScreenTouchEvent(deferredTouchEvents.removeFirst());
            }
        }

        private void recycleDeferredTouchEvents() {
            while (!deferredTouchEvents.isEmpty()) deferredTouchEvents.removeFirst().recycle();
            flushPosted = false;
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

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (unityView == null) {
                return;
            }
            long started = System.nanoTime();
            boolean wasReady = fullScreenLayoutReady;
            // getLocationOnScreen 在窗口移动的这次 onLayout 中也可能还是旧值。
            // 窗口使用 TOP|START、fitInsets=0、NO_LIMITS，按提交坐标与父窗同步补偿。
            int x = -windowParams.x;
            int y = -windowParams.y;
            unityView.layout(x, y, x + displayWidth, y + displayHeight);

            boolean coversScreen = fullScreenGesture
                    && r - l >= displayWidth && b - t >= displayHeight;
            if (coversScreen || petRect.isEmpty()) {
                unityView.setClipBounds(null);
            } else {
                // clipBounds 使用 Unity 视图的局部坐标，和偏移在同一次布局中更新。
                unityView.setClipBounds(new Rect(-x, -y, -x + (r - l), -y + (b - t)));
            }
            fullScreenLayoutReady = coversScreen;
            if (!wasReady && coversScreen) traceInput("viewport-ready");
            long elapsedMs = (System.nanoTime() - started) / 1000000;
            if (elapsedMs >= 100) traceInput("slow-layout ms=" + elapsedMs);
            if (fullScreenLayoutReady && !deferredTouchEvents.isEmpty() && !flushPosted) {
                // 等本次布局/绘制遍历结束后再交给 Unity，避免旧 Surface 裁剪第一帧。
                flushPosted = true;
                MAIN.post(this::flushDeferredTouchEvents);
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
        if (optionsMenu != null) optionsMenu.dismiss();
        sInstance = null;
        MAIN.removeCallbacksAndMessages(null);
        if (floatView != null) floatView.recycleDeferredTouchEvents();
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
