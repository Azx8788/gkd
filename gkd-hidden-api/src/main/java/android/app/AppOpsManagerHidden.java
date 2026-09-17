package android.app;

import android.os.Build;

import androidx.annotation.RequiresApi;

import li.songe.remap.RemapType;

@RemapType(AppOpsManager.class)
public class AppOpsManagerHidden {
    public static int OP_POST_NOTIFICATION;

    public static int OP_SYSTEM_ALERT_WINDOW;

    // OP_RUN_IN_BACKGROUND(63) 自 API 24, OP_RUN_ANY_IN_BACKGROUND(71) 自 API 26,
    // 项目 minSdk=26, 两者均保证存在, 无需 @RequiresApi
    public static int OP_RUN_IN_BACKGROUND;

    public static int OP_RUN_ANY_IN_BACKGROUND;

    @RequiresApi(Build.VERSION_CODES.Q)
    public static int OP_ACCESS_ACCESSIBILITY;

    @RequiresApi(Build.VERSION_CODES.Q)
    public static String OPSTR_ACCESS_ACCESSIBILITY;

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static int OP_ACCESS_RESTRICTED_SETTINGS;

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static String OPSTR_ACCESS_RESTRICTED_SETTINGS;

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static int OP_FOREGROUND_SERVICE_SPECIAL_USE;

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static String OPSTR_FOREGROUND_SERVICE_SPECIAL_USE;
}
