/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aarongutierrez.watchface;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.support.wearable.provider.WearableCalendarContract;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long CALENDAR_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(5);

    private static final String TAG = "MyWatchFace";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MSG_UPDATE_TIME = 0;
        static final int MSG_LOAD_MEETINGS = 0;

        private AsyncTask<Void, Void, List<CalenderEvent>> mLoadMeetingsTask;

        final Handler mLoadMeetingsHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_LOAD_MEETINGS:
                        cancelLoadMeetingTask();
                        mLoadMeetingsTask = new LoadMeetingsTask();
                        mLoadMeetingsTask.execute();
                        mLoadMeetingsHandler.sendEmptyMessageDelayed(MSG_LOAD_MEETINGS,
                                CALENDAR_UPDATE_RATE_MS);
                        break;
                }
            }
        };

        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                if (Intent.ACTION_PROVIDER_CHANGED.equals(intent.getAction())
                    && WearableCalendarContract.CONTENT_URI.equals(intent.getData())) {
                    cancelLoadMeetingTask();
                    mLoadMeetingsHandler.sendEmptyMessage(MSG_LOAD_MEETINGS);
                }
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mCalPaint;

        boolean mAmbient;

        Time mTime;

        private long today() {
            return 1436252915L;
            /* return ((mTime.toMillis(false) - (mTime.gmtoff * DateUtils.SECOND_IN_MILLIS)
                    - DateUtils.DAY_IN_MILLIS) / DateUtils.DAY_IN_MILLIS) * DateUtils.DAY_IN_MILLIS; */
        }

        float mXOffset;
        float mYOffset;

        float mCalWidth;

        List<CalenderEvent> mEventList;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(TAG, "Loading...");

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mCalWidth = resources.getDimension(R.dimen.cal_width);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text),
                    resources.getDimension(R.dimen.time_size));

            mCalPaint = new Paint();

            mTime = new Time();

            mLoadMeetingsHandler.sendEmptyMessage(MSG_LOAD_MEETINGS);
        }
        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mLoadMeetingsHandler.removeMessages(MSG_LOAD_MEETINGS);
            cancelLoadMeetingTask();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, float fontSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTextSize(fontSize);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            Resources resources = MyWatchFace.this.getResources();

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }

                if (mAmbient) {
                    mBackgroundPaint.setColor(resources.getColor(R.color.ambient_background));
                } else {
                    mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));
                }
                mCalPaint.setColor(resources.getColor(R.color.digital_text));
            }

            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private float timeToY(long time, float height) {
            Time tmp = new Time();
            tmp.setToNow();
            tmp.hour = 0;
            tmp.minute = 0;
            tmp.second = 0;

            return (((float)(time - tmp.toMillis(false)))
                    / DateUtils.HOUR_IN_MILLIS)* (height / 24.0f);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw time text
            mTime.setToNow();
            String time_string = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(time_string, mXOffset, mYOffset, mTextPaint);

            // Draw calendar events
            float cal_start_x = bounds.width() - mCalWidth;
            if (mEventList != null) {
                // Draw now line
                float now_y = timeToY(mTime.toMillis(false), bounds.height());
                canvas.drawLine(cal_start_x-10, now_y,
                        bounds.width(), now_y, mTextPaint);

                for (CalenderEvent c : mEventList) {
                    if (!mAmbient) {
                        mCalPaint.setColor(c.event_color);
                    }
                    canvas.drawRect(cal_start_x, timeToY(c.startTime, bounds.height()),
                            bounds.width(), timeToY(c.endTime, bounds.height()), mCalPaint);
                }
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void onMeetingsLoaded(List<CalenderEvent> result) {
            if (result != null) {
                mEventList = result;
                invalidate();
            }
        }

        private void cancelLoadMeetingTask() {
            if (mLoadMeetingsTask != null) {
                mLoadMeetingsTask.cancel(true);
            }
        }

        private class CalenderEvent {
            long startTime;
            long endTime;
            int event_color;

            public CalenderEvent(long startTime, long endTime, String color) {
                this.startTime = startTime;
                this.endTime = endTime;
                event_color = Integer.parseInt(color);
            }
        }

        private class LoadMeetingsTask extends AsyncTask<Void, Void, List<CalenderEvent>> {
            private PowerManager.WakeLock mWakeLock;

            @Override
            protected List<CalenderEvent> doInBackground(Void... params) {
                PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
                mWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "MyWatchFaceWakeLock");
                mWakeLock.acquire();

                Uri.Builder builder = WearableCalendarContract.Instances.CONTENT_URI.buildUpon();

                Log.d(TAG, "Time start: " + System.currentTimeMillis());
                ContentUris.appendId(builder, System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS);
                ContentUris.appendId(builder, System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS);

                final Cursor cursor = getContentResolver().query(builder.build(),
                        null, null, null, null);

                Log.d(TAG, "Count of rows: " + cursor.getCount());

                LinkedList<CalenderEvent> events = new LinkedList<>();

                int begin_index = cursor.getColumnIndex("begin");
                int end_index = cursor.getColumnIndex("end");
                int title_index = cursor.getColumnIndex("title");
                int display_color_index = cursor.getColumnIndex("displayColor");

                while (cursor.moveToNext()) {
                    Log.d(TAG, cursor.getString(title_index));

                    events.push(new CalenderEvent(
                            cursor.getLong(begin_index),
                            cursor.getLong(end_index),
                            cursor.getString(display_color_index)
                    ));
                }

                cursor.close();

                return events;
            }

            @Override
            protected void onPostExecute(List<CalenderEvent> result) {
                releaseWakeLock();
                onMeetingsLoaded(result);
            }

            @Override
            protected void onCancelled() {
                releaseWakeLock();
            }

            private void releaseWakeLock() {
                if (mWakeLock != null) {
                    mWakeLock.release();
                    mWakeLock = null;
                }
            }
        }
    }
}
