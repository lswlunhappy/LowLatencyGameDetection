package com.lowlatencygamedetection.tool; // 根据你的实际包名修改

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.animation.AnimatorListenerAdapter; // FIX: 添加导入
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.content.Context;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private static final Handler AUDIO_HANDLER;
    private static final HandlerThread AUDIO_THREAD;
    private static final long DEBOUNCE_MS = 50;
    private static final long HEALTH_CHECK_INTERVAL = 500;

    static {
        AUDIO_THREAD = new HandlerThread("AudioThread",
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        AUDIO_THREAD.start();
        AUDIO_HANDLER = new Handler(AUDIO_THREAD.getLooper());
    }

    private long mLastTriggerTime = 0;
    private boolean mAudioFocused = true;
    private android.media.AudioManager mAudioManager;
    private android.media.AudioFocusRequest mFocusRequest;

    private final Runnable mHealthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRestoreAudioStream();
            AUDIO_HANDLER.postDelayed(this, HEALTH_CHECK_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initAudioFocus();
        startAudioEngine();
        setupButton();

        AUDIO_HANDLER.post(mHealthCheckRunnable);
        Log.i(TAG, "MainActivity created");
    }

    private void startAudioEngine() {
        AUDIO_HANDLER.post(() -> {
            boolean success = MixerEngine.nativeStart();
            Log.i(TAG, "Audio engine start: " + success);
            if (!success) {
                AUDIO_HANDLER.postDelayed(() -> startAudioEngine(), 5000);
            }
        });
    }

    private void setupButton() {
        View btn = findViewById(R.id.btn_quarter);
        if (btn != null) {
            btn.setOnTouchListener(this::handleTouch);
            Log.i(TAG, "Button setup successful");
        } else {
            Log.e(TAG, "Button R.id.btn_quarter not found!");
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            long now = SystemClock.elapsedRealtime();

            if (!mAudioFocused || !MixerEngine.nativeIsStreamHealthy()) {
                Log.w(TAG, "Audio not ready, focus=" + mAudioFocused +
                        ", healthy=" + MixerEngine.nativeIsStreamHealthy());
                checkAndRestoreAudioStream();
                return true;
            }

            if (now - mLastTriggerTime > DEBOUNCE_MS) {
                mLastTriggerTime = now;
                Log.d(TAG, "handleTouch: calling flashWhite");
                flashWhite();
                AUDIO_HANDLER.post(() -> {
                    MixerEngine.nativePlayClick();
                    Log.v(TAG, "Click played");
                });
            } else {
                Log.d(TAG, "Touch debounced");
            }
            return true;
        }
        return false;
    }

    // 兼容所有API级别的闪白实现
    private void flashWhite() {
        runOnUiThread(() -> {
            ViewGroup rootView = findViewById(android.R.id.content);
            if (rootView == null) {
                Log.e(TAG, "flashWhite: rootView is null!");
                return;
            }

            View overlay = new View(this);
            overlay.setBackgroundColor(Color.WHITE);
            rootView.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            ObjectAnimator animator = ObjectAnimator.ofFloat(overlay, "alpha", 0f, 0.8f, 0f);
            animator.setDuration(80);

            // API兼容的结束动作
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    rootView.removeView(overlay);
                    Log.d(TAG, "flashWhite: overlay removed");
                }
            });

            animator.start();
            Log.d(TAG, "flashWhite: overlay animation started");
        });
    }

    private void checkAndRestoreAudioStream() {
        if (!MixerEngine.nativeIsStreamHealthy()) {
            Log.w(TAG, "Audio stream unhealthy, restarting...");
            AUDIO_HANDLER.post(() -> {
                try {
                    MixerEngine.nativeStop();
                    Thread.sleep(100);
                    boolean success = MixerEngine.nativeStart();
                    mAudioFocused = success;
                    Log.i(TAG, "Stream restart result: " + success);
                } catch (Exception e) {
                    Log.e(TAG, "Stream restart failed", e);
                }
            });
        }
    }

    private void initAudioFocus() {
        mAudioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);

        mFocusRequest = new android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(focusChange -> {
                    AUDIO_HANDLER.post(() -> {
                        switch (focusChange) {
                            case android.media.AudioManager.AUDIOFOCUS_LOSS:
                            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                Log.w(TAG, "Audio focus lost: " + focusChange);
                                MixerEngine.nativeSetBgmOn(false);
                                mAudioFocused = false;
                                break;
                            case android.media.AudioManager.AUDIOFOCUS_GAIN:
                                Log.i(TAG, "Audio focus gained");
                                MixerEngine.nativeSetBgmOn(true);
                                mAudioFocused = true;
                                break;
                        }
                    });
                })
                .setWillPauseWhenDucked(true)
                .build();

        int result = mAudioManager.requestAudioFocus(mFocusRequest);
        mAudioFocused = (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Log.i(TAG, "Audio focus granted: " + mAudioFocused);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        AUDIO_HANDLER.removeCallbacks(mHealthCheckRunnable);

        if (mAudioManager != null && mFocusRequest != null) {
            mAudioManager.abandonAudioFocusRequest(mFocusRequest);
        }

        AUDIO_HANDLER.post(() -> {
            MixerEngine.nativeStop();
            Log.i(TAG, "Audio engine stopped");
        });

        new Thread(() -> {
            AUDIO_HANDLER.removeCallbacksAndMessages(null);
            AUDIO_THREAD.quitSafely();
            try {
                AUDIO_THREAD.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "AudioCleanup").start();
    }
}