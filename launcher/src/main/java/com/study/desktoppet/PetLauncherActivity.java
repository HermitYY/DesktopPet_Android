package com.study.desktoppet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public class PetLauncherActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_NOTIFICATION = 1002;

    /** 是否已经点过「我知道了」，避免 onResume 反复弹声明 */
    private boolean copyrightAccepted = false;
    /** 是否正在等用户从悬浮窗设置页回来 */
    private boolean waitingForOverlay = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showCopyrightDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户在设置里开了悬浮窗后，即使上滑回桌面再点图标进来，
        // 或者从设置页返回，都会走这里，自动继续后续流程
        if (copyrightAccepted && waitingForOverlay
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false;
            checkAndRequestPermissions();
        }
    }

    private void showCopyrightDialog() {
        String title = decode(new int[]{0x58f0, 0x660e});

        String message = decode(new int[]{
                0x672c, 0x5e94, 0x7528, 0x4e3a, 0x4e2a, 0x4eba, 0x5b66, 0x4e60, 0x4ea4, 0x6d41, 0x7528, 0x9014, 0x7684, 0x514d, 0x8d39, 0x5206, 0x4eab, 0x8f6f, 0x4ef6, 0xff0c,
                0x7981, 0x6b62, 0x4e8c, 0x6b21, 0x8d29, 0x5356, 0x3001, 0x6536, 0x8d39, 0x6216, 0x7528, 0x4e8e, 0x4efb, 0x4f55, 0x5546, 0x4e1a, 0x7528, 0x9014, 0x3002,
                0x76f8, 0x5173, 0x7248, 0x6743, 0x53ca, 0x5546, 0x6807, 0x5f52, 0x7f51, 0x6613, 0x6e38, 0x620f, 0x53ca, 0x5176, 0x6743, 0x5229, 0x4eba, 0x6240, 0x6709, 0x3002
        });

        String ok     = decode(new int[]{0x6211, 0x77e5, 0x9053, 0x4e86});
        String cancel = decode(new int[]{0x9000, 0x51fa});

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyrightAccepted = true;
                        checkAndRequestPermissions();
                    }
                })
                .setNegativeButton(cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private String decode(int[] data) {
        StringBuilder sb = new StringBuilder(data.length);
        for (int v : data) {
            sb.append((char) v);
        }
        return sb.toString();
    }

    private void checkAndRequestPermissions() {
        // 1. 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            waitingForOverlay = true;
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "请开启「在其他应用上层显示」权限", Toast.LENGTH_LONG).show();
            return;
        }

        // 2. Android 13+ 通知权限（前台服务需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
                return;
            }
        }

        // 权限都齐了 → 启动桌宠
        startPetAndFinish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            waitingForOverlay = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                checkAndRequestPermissions();
            } else {
                Toast.makeText(this, "没有悬浮窗权限，无法启动桌宠", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            // 通知权限无论给不给都继续启动（前台服务通知只是提示）
            startPetAndFinish();
        }
    }

    private void startPetAndFinish() {
        Intent intent = new Intent(this, PetFloatService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        finish();
    }
}