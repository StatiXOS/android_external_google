package com.google.android.systemui.elmyra.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TypedValue;
import com.android.systemui.R;
import com.google.android.systemui.elmyra.SnapshotConfiguration;
import com.google.android.systemui.elmyra.SnapshotController;
import com.google.android.systemui.elmyra.SnapshotLogger;
import com.google.android.systemui.elmyra.proto.nano.ElmyraChassis.Chassis;
import com.google.android.systemui.elmyra.proto.nano.SnapshotMessages.Snapshot;
import com.google.android.systemui.elmyra.proto.nano.SnapshotMessages.Snapshots;
import com.google.android.systemui.elmyra.sensors.GestureSensor.DetectionProperties;
import com.google.android.systemui.elmyra.sensors.GestureSensor.Listener;
import com.google.protobuf.nano.MessageNano;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

class AssistGestureController {
    private Chassis mChassis;
    private SnapshotLogger mCompleteGestures;
    private final long mFalsePrimeWindow;
    private final long mGestureCooldownTime;
    private Listener mGestureListener;
    private float mGestureProgress;
    private final GestureSensor mGestureSensor;
    private SnapshotLogger mIncompleteGestures;
    private boolean mIsFalsePrimed;
    private long mLastDetectionTime;
    private OPAQueryReceiver mOpaQueryReceiver;
    private final float mProgressAlpha;
    private final float mProgressReportThreshold;
    private final SnapshotController mSnapshotController;

    private class OPAQueryReceiver extends BroadcastReceiver {
        private OPAQueryReceiver() {
        }

        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.google.android.systemui.OPA_ELMYRA_QUERY_SUBMITTED")) {
                AssistGestureController.this.mCompleteGestures.didReceiveQuery();
            }
        }
    }

    AssistGestureController(Context context, GestureSensor gestureSensor) {
        this(context, gestureSensor, null);
    }

    AssistGestureController(Context context, GestureSensor gestureSensor, SnapshotConfiguration snapshotConfiguration) {
        int i = 0;
        this.mOpaQueryReceiver = new OPAQueryReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.google.android.systemui.OPA_ELMYRA_QUERY_SUBMITTED");
        context.registerReceiver(this.mOpaQueryReceiver, intentFilter);
        this.mGestureSensor = gestureSensor;
        Resources resources = context.getResources();
        TypedValue typedValue = new TypedValue();
        this.mCompleteGestures = new SnapshotLogger(snapshotConfiguration != null ? snapshotConfiguration.getCompleteGestures() : 0);
        if (snapshotConfiguration != null) {
            i = snapshotConfiguration.getIncompleteGestures();
        }
        this.mIncompleteGestures = new SnapshotLogger(i);
        resources.getValue(R.dimen.elmyra_progress_alpha, typedValue, true);
        this.mProgressAlpha = typedValue.getFloat();
        resources.getValue(R.dimen.elmyra_progress_report_threshold, typedValue, true);
        this.mProgressReportThreshold = typedValue.getFloat();
        this.mGestureCooldownTime = (long) resources.getInteger(R.integer.elmyra_gesture_cooldown_time);
        this.mFalsePrimeWindow = this.mGestureCooldownTime + ((long) resources.getInteger(R.integer.elmyra_false_prime_window));
        this.mSnapshotController = new SnapshotController(snapshotConfiguration);
    }

    private void sendGestureProgress(GestureSensor gestureSensor, float f, int i) {
        if (this.mGestureListener != null) {
            this.mGestureListener.onGestureProgress(gestureSensor, f, i);
        }
        this.mSnapshotController.onGestureProgress(gestureSensor, f, i);
    }


    public Chassis getChassisConfiguration() {
        return this.mChassis;
    }

    public void onGestureDetected(DetectionProperties detectionProperties) {
        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis - this.mLastDetectionTime >= this.mGestureCooldownTime && !this.mIsFalsePrimed) {
            if (this.mGestureListener != null) {
                this.mGestureListener.onGestureDetected(this.mGestureSensor, detectionProperties);
            }
            this.mSnapshotController.onGestureDetected(this.mGestureSensor, detectionProperties);
            this.mLastDetectionTime = uptimeMillis;
        }
    }

    public void onGestureProgress(float f) {
        int i = 1;
        if (f == 0.0f) {
            this.mGestureProgress = 0.0f;
            this.mIsFalsePrimed = false;
        } else {
            this.mGestureProgress = (this.mProgressAlpha * f) + ((1.0f - this.mProgressAlpha) * this.mGestureProgress);
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis - this.mLastDetectionTime >= this.mGestureCooldownTime && !this.mIsFalsePrimed) {
            if (uptimeMillis - this.mLastDetectionTime < this.mFalsePrimeWindow && f == 1.0f) {
                this.mIsFalsePrimed = true;
            } else if (this.mGestureProgress < this.mProgressReportThreshold) {
                sendGestureProgress(this.mGestureSensor, 0.0f, 0);
            } else {
                float f2 = (this.mGestureProgress - this.mProgressReportThreshold) / (1.0f - this.mProgressReportThreshold);
                if (f == 1.0f) {
                    i = 2;
                }
                sendGestureProgress(this.mGestureSensor, f2, i);
            }
        }
    }

    public void onSnapshotReceived(Snapshot snapshot) {
        if (snapshot.header.gestureType == 1) {
            this.mCompleteGestures.addSnapshot(snapshot, System.currentTimeMillis());
        } else {
            this.mIncompleteGestures.addSnapshot(snapshot, System.currentTimeMillis());
        }
    }

    public void setGestureListener(Listener listener) {
        this.mGestureListener = listener;
    }

    public void setSnapshotListener(SnapshotController.Listener listener) {
        this.mSnapshotController.setListener(listener);
    }

    public void storeChassisConfiguration(Chassis chassis) {
        this.mChassis = chassis;
    }
}
