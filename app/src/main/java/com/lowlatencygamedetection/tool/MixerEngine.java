package com.lowlatencygamedetection.tool;

public class MixerEngine {
    static {
        System.loadLibrary("oboe-mixer");
    }

    public static native boolean nativeStart();
    public static native void nativeStop();
    public static native void nativePlayClick();
    public static native void nativeSetBgmOn(boolean on);

    // 流健康检查（用于自动恢复）
    public static native boolean nativeIsStreamHealthy();
}