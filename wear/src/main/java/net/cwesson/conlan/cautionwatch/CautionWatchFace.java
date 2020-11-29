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

package net.cwesson.conlan.cautionwatch;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.CalendarContract;
import android.support.wearable.provider.WearableCalendarContract;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class CautionWatchFace extends CanvasWatchFaceService{
	/**
	 * Update rate in milliseconds for interactive mode. We update once a second to advance the
	 * second hand.
	 */
	private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

	/**
	 * Handler message id for updating the time periodically in interactive mode.
	 */
	private static final int MSG_UPDATE_TIME = 0;

	private static final int MSG_LOAD_MEETINGS = 0;

	private static final String TAG = "CalendarWatchFace";

	private static final String[] PROJECTION = {
			CalendarContract.Calendars._ID,
			CalendarContract.Instances.BEGIN,
			CalendarContract.Instances.END,
			CalendarContract.Instances.DISPLAY_COLOR,
			CalendarContract.Instances.EVENT_COLOR,
			CalendarContract.Instances.CALENDAR_COLOR,
			CalendarContract.Instances.ALL_DAY,
	};

	@Override
	public Engine onCreateEngine(){
		return new Engine();
	}

	private class Event implements Comparable<Event>{
		private long begin;
		private long end;
		private int color;

		public Event(long begin, long end, int color){
			this.begin = begin;
			this.end = end;
			this.color = color;
		}

		public long getBegin(){
			return this.begin;
		}

		public long getEnd(){
			return this.end;
		}

		public int getColor(){
			return this.color;
		}

		@Override
		public int compareTo(Event other){
			return (int) (this.begin - other.begin);
		}
	}

	private class Engine extends CanvasWatchFaceService.Engine{
		Paint mBackgroundPaint;
		Paint mHandHrPaint;
		Paint mHandMinPaint;
		Paint mHandSecPaint;
		Paint mTick1Paint;
		Paint mTick5Paint;
		Paint mTick15Paint;
		Paint mTick12Paint;
		Paint mDatePaint;
		Paint mEventStrokePaint;
		Paint mEventFillAmbientPaint;
		Paint mEventFillBrightPaint;
		boolean mAmbient;
		Time mTime;

		final Handler mUpdateTimeHandler = new EngineHandler(this);

		final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver(){
			@Override
			public void onReceive(Context context, Intent intent){
				mTime.clear(intent.getStringExtra("time-zone"));
				mTime.setToNow();
			}
		};
		boolean mRegisteredTimeZoneReceiver = false;

		/**
		 * Whether the display supports fewer bits for each color in ambient mode. When true, we
		 * disable anti-aliasing in ambient mode.
		 */
		boolean mLowBitAmbient;

		@Override
		public void onCreate(SurfaceHolder holder){
			super.onCreate(holder);

			setWatchFaceStyle(new WatchFaceStyle.Builder(CautionWatchFace.this)
					.setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
					.setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT)
					.setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
					.setShowSystemUiTime(false)
					.build());

			Resources resources = CautionWatchFace.this.getResources();

			mBackgroundPaint = new Paint();
			mBackgroundPaint.setColor(resources.getColor(R.color.analog_background));

			mHandHrPaint = new Paint();
			mHandHrPaint.setColor(resources.getColor(R.color.analog_hands));
			mHandHrPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hrhand_stroke));
			mHandHrPaint.setAntiAlias(true);
			mHandHrPaint.setStrokeCap(Paint.Cap.ROUND);

			mHandMinPaint = new Paint();
			mHandMinPaint.setColor(resources.getColor(R.color.analog_hands));
			mHandMinPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_minhand_stroke));
			mHandMinPaint.setAntiAlias(true);
			mHandMinPaint.setStrokeCap(Paint.Cap.ROUND);

			mHandSecPaint = new Paint();
			mHandSecPaint.setColor(resources.getColor(R.color.analog_hands));
			mHandSecPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_sechand_stroke));
			mHandSecPaint.setAntiAlias(true);
			mHandSecPaint.setStrokeCap(Paint.Cap.ROUND);

			mEventStrokePaint = new Paint();
			mEventStrokePaint.setColor(resources.getColor(R.color.event_stroke));
			mEventStrokePaint.setStrokeWidth(resources.getDimension(R.dimen.event_stroke));
			mEventStrokePaint.setStyle(Paint.Style.STROKE);
			mEventStrokePaint.setAntiAlias(true);
			mEventStrokePaint.setStrokeCap(Paint.Cap.ROUND);

			mTick1Paint = new Paint();
			mTick1Paint.setColor(resources.getColor(R.color.tick_stroke));
			mTick1Paint.setStrokeWidth(resources.getDimension(R.dimen.tick1_stroke));
			mTick1Paint.setAntiAlias(true);
			mTick1Paint.setStrokeCap(Paint.Cap.ROUND);

			mTick5Paint = new Paint();
			mTick5Paint.setColor(resources.getColor(R.color.tick_stroke));
			mTick5Paint.setStrokeWidth(resources.getDimension(R.dimen.tick5_stroke));
			mTick5Paint.setAntiAlias(true);
			mTick5Paint.setStrokeCap(Paint.Cap.ROUND);

			mTick15Paint = new Paint();
			mTick15Paint.setColor(resources.getColor(R.color.tick_stroke));
			mTick15Paint.setStrokeWidth(resources.getDimension(R.dimen.tick15_stroke));
			mTick15Paint.setAntiAlias(true);
			mTick15Paint.setStrokeCap(Paint.Cap.ROUND);

			mTick12Paint = new Paint();
			mTick12Paint.setColor(resources.getColor(R.color.tick_stroke));
			mTick12Paint.setStrokeWidth(resources.getDimension(R.dimen.tick12_stroke));
			mTick12Paint.setAntiAlias(true);
			mTick12Paint.setStrokeCap(Paint.Cap.ROUND);

			mEventFillAmbientPaint = new Paint();
			mEventFillAmbientPaint.setColor(resources.getColor(R.color.event_fill_ambient));
			mEventFillAmbientPaint.setStrokeWidth(resources.getDimension(R.dimen.event_stroke));
			mEventFillAmbientPaint.setStyle(Paint.Style.FILL);
			mEventFillAmbientPaint.setAntiAlias(true);
			mEventFillAmbientPaint.setStrokeCap(Paint.Cap.ROUND);

			mEventFillBrightPaint = new Paint();
			mEventFillBrightPaint.setColor(resources.getColor(R.color.event_fill_bright));
			mEventFillBrightPaint.setStrokeWidth(resources.getDimension(R.dimen.event_stroke));
			mEventFillBrightPaint.setStyle(Paint.Style.FILL);
			mEventFillBrightPaint.setAntiAlias(true);
			mEventFillBrightPaint.setStrokeCap(Paint.Cap.ROUND);

			mDatePaint = new Paint();
			mDatePaint.setColor(resources.getColor(R.color.text_light));
			mDatePaint.setAntiAlias(true);
			mDatePaint.setTextSize(28);
			mDatePaint.setTextAlign(Paint.Align.CENTER);

			mTime = new Time();
		}

		@Override
		public void onDestroy(){
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			super.onDestroy();
		}

		@Override
		public void onPropertiesChanged(Bundle properties){
			super.onPropertiesChanged(properties);
			mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
		}

		@Override
		public void onTimeTick(){
			super.onTimeTick();
			invalidate();
		}

		@Override
		public void onAmbientModeChanged(boolean inAmbientMode){
			super.onAmbientModeChanged(inAmbientMode);
			if(mAmbient != inAmbientMode){
				mAmbient = inAmbientMode;
				if(mLowBitAmbient){
					mHandHrPaint.setAntiAlias(!inAmbientMode);
					mHandMinPaint.setAntiAlias(!inAmbientMode);
				}
				invalidate();
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		@Override
		public void onDraw(Canvas canvas, Rect bounds){
			mTime.setToNow();

			// Draw the background.
			canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);

			// Find the center. Ignore the window insets so that, on round watches with a
			// "chin", the watch face is centered on the entire screen, not just the usable
			// portion.
			float centerX = bounds.width() / 2f;
			float centerY = bounds.height() / 2f;
			float radius = Math.max(centerX, centerY);

			float secRot = mTime.second / 30f * (float) Math.PI;
			float minRot = mTime.minute / 30f * (float) Math.PI;
			float hrRot = ((mTime.hour + (mTime.minute / 60f)) / 6f) * (float) Math.PI;

			float secLength = radius;
			float minLength = radius - 40;
			float hrLength = radius - 80;
			float tick15Length = radius - 8;
			float tick5Length = radius - 4;
			float tick1Length = radius - 2;

			// Draw ticks
			if(!mAmbient){
				for(double t = 0.0; t <= Math.PI; t += Math.PI / 30f){
					float x = (float) Math.sin(t) * radius;
					float y = (float) -Math.cos(t) * radius;
					canvas.drawLine(centerX - x, centerY - y, centerX + x, centerY + y, mTick1Paint);
				}
				canvas.drawCircle(centerX, centerY, tick1Length, mBackgroundPaint);

				for(double t = 0.0; t <= Math.PI; t += Math.PI/6f){
					float x = (float) Math.sin(t) * radius;
					float y = (float) -Math.cos(t) * radius;
					canvas.drawLine(centerX - x, centerY - y, centerX + x, centerY + y, mTick5Paint);
				}
				canvas.drawCircle(centerX, centerY, tick5Length, mBackgroundPaint);

				for(double t = 0.0; t <= Math.PI; t += Math.PI/2f){
					float x = (float) Math.sin(t) * radius;
					float y = (float) -Math.cos(t) * radius;
					canvas.drawLine(centerX - x, centerY - y, centerX + x, centerY + y, mTick15Paint);
				}
				canvas.drawCircle(centerX, centerY, tick15Length, mBackgroundPaint);
			}

			// Draw 12 tick
			Path path = new Path();
			path.setFillType(Path.FillType.EVEN_ODD);
			path.moveTo(centerX, 8);
			path.lineTo(centerX - 4, 0);
			path.arcTo(0f, 0f, centerX*2f, centerY*2f, 267f, 4f, false);
			path.close();
			canvas.drawPath(path, mBackgroundPaint);
			canvas.drawPath(path, mTick12Paint);

			// Draw events
			if(mMeetings != null){
				float lastEnd = 0;
				float eventLength = hrLength;
				final long now = mTime.toMillis(true);
				for(Event e : mMeetings){
					long start = e.getBegin();
					long end = e.getEnd();
					if(end < now || start > now + (DateUtils.DAY_IN_MILLIS / 2)){
						continue;
					}
					if(start < now){
						start = now;
					}
					if(end > now + (DateUtils.DAY_IN_MILLIS / 2)){
						end = now + (DateUtils.DAY_IN_MILLIS / 2);
					}
					if(start <= lastEnd){
						eventLength -= 10;
					}else{
						eventLength = hrLength;
					}
					float duration = (float) (end - start) / (float) DateUtils.HOUR_IN_MILLIS;
					Calendar calendar = GregorianCalendar.getInstance(); // creates a new calendar instance
					calendar.setTimeZone(TimeZone.getDefault());
					calendar.setTimeInMillis(start);   // assigns calendar to given date
					float startHr = calendar.get(Calendar.HOUR_OF_DAY) + (calendar.get(Calendar.MINUTE) / 60f);
					if(startHr > 12){
						startHr -= 12f;
					}
					drawEvent(centerX, centerY, startHr, duration, e.getColor(), eventLength, canvas);
					if(end > lastEnd){
						lastEnd = end;
					}
				}
			}

			// Draw minute hand
			float minX = (float) Math.sin(minRot) * minLength;
			float minY = (float) -Math.cos(minRot) * minLength;
			canvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandMinPaint);

			// Draw hour hand
			float hrX = (float) Math.sin(hrRot) * hrLength;
			float hrY = (float) -Math.cos(hrRot) * hrLength;
			canvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHandHrPaint);

			// Draw second hand
			if(!mAmbient){
				float secX = (float) Math.sin(secRot) * secLength;
				float secY = (float) -Math.cos(secRot) * secLength;
				canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mHandSecPaint);

				canvas.drawText(mTime.format("%a %m-%d"), centerX, centerY - (hrLength + 20), mDatePaint);
			}

			// Draw center
			canvas.drawCircle(centerX, centerY, 6, mBackgroundPaint);
			canvas.drawCircle(centerX, centerY, 6, mEventStrokePaint);
		}

		private void drawEvent(float centerX, float centerY, float start, float duration, int color, float length, Canvas canvas){
			float eventSRot = (start / 6f) * (float) Math.PI;
			float eventSX = (float) Math.sin(eventSRot) * length;
			float eventSY = (float) -Math.cos(eventSRot) * length;
			float eventSDeg = (start / 12f) * 360f;
			float eventDDeg = (duration / 12f) * 360f;
			Path path = new Path();
			path.setFillType(Path.FillType.EVEN_ODD);
			path.moveTo(centerX, centerY);
			path.lineTo(centerX + eventSX, centerY + eventSY);
			path.arcTo(centerX - length, centerY - length, centerX + length, centerY + length, eventSDeg - 90, eventDDeg, false);
			path.close();
			if(mAmbient){
				canvas.drawPath(path, mEventFillAmbientPaint);
			}else{
				mEventFillBrightPaint.setColor(color);
				canvas.drawPath(path, mEventFillBrightPaint);
			}
			canvas.drawPath(path, mEventStrokePaint);
		}

		@Override
		public void onVisibilityChanged(boolean visible){
			super.onVisibilityChanged(visible);

			if(visible){
				registerReceiver();

				mLoadMeetingsHandler.sendEmptyMessage(MSG_LOAD_MEETINGS);

				// Update time zone in case it changed while we weren't visible.
				mTime.clear(TimeZone.getDefault().getID());
				mTime.setToNow();
			}else{
				unregisterReceiver();

				mLoadMeetingsHandler.removeMessages(MSG_LOAD_MEETINGS);
				cancelLoadMeetingTask();
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		private void registerReceiver(){
			if(mRegisteredTimeZoneReceiver){
				return;
			}
			mRegisteredTimeZoneReceiver = true;
			IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
			CautionWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
		}

		private void unregisterReceiver(){
			if(!mRegisteredTimeZoneReceiver){
				return;
			}
			mRegisteredTimeZoneReceiver = false;
			CautionWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
		}

		/**
		 * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
		 * or stops it if it shouldn't be running but currently is.
		 */
		private void updateTimer(){
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			if(shouldTimerBeRunning()){
				mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
			}
		}

		/**
		 * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
		 * only run when we're visible and in interactive mode.
		 */
		private boolean shouldTimerBeRunning(){
			return isVisible() && !isInAmbientMode();
		}

		/**
		 * Handle updating the time periodically in interactive mode.
		 */
		private void handleUpdateTimeMessage(){
			invalidate();
			if(shouldTimerBeRunning()){
				long timeMs = System.currentTimeMillis();
				long delayMs = INTERACTIVE_UPDATE_RATE_MS
						- (timeMs % INTERACTIVE_UPDATE_RATE_MS);
				mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
			}
		}

		private void onMeetingsLoaded(List<Event> result){
			if(result != null){
				mMeetings = result;
				invalidate();
			}
		}

		private void cancelLoadMeetingTask(){
			if(mLoadMeetingsTask != null){
				mLoadMeetingsTask.cancel(true);
			}
		}

		/* Asynchronous task to load the meetings from the content provider and
		* report the number of meetings back using onMeetingsLoaded() */
		private class LoadMeetingsTask extends AsyncTask<Void, Void, List<Event>>{
			@Override
			protected List<Event> doInBackground(Void... voids){
				List<Event> events = new ArrayList<Event>();
				long begin = System.currentTimeMillis();
				Uri.Builder builder = WearableCalendarContract.Instances.CONTENT_URI.buildUpon();
				ContentUris.appendId(builder, begin);
				ContentUris.appendId(builder, begin + (DateUtils.DAY_IN_MILLIS));
				final Cursor cursor = getContentResolver().query(builder.build(), PROJECTION, null, null, null);

				while(cursor.moveToNext()){
					long start = cursor.getLong(cursor.getColumnIndex(CalendarContract.Instances.BEGIN));
					long end = cursor.getLong(cursor.getColumnIndex(CalendarContract.Instances.END));
					int color = cursor.getInt(cursor.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR));
					// Workaround for DISPLAY_COLOR not always working
					if(color == 0){
						color = cursor.getInt(cursor.getColumnIndex(CalendarContract.Instances.EVENT_COLOR));
						if(color == 0){
							color = cursor.getInt(cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_COLOR));
							if(color == 0){
								color = CautionWatchFace.this.getResources().getColor(R.color.event_fill_bright);
								Log.d("E", "No event color could be found, using default");
							}
						}
					}
					if(cursor.getInt(cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)) == 0){
						events.add(new Event(start, end, color));
					}
				}

				cursor.close();

				Collections.sort(events);
				return events;
			}

			@Override
			protected void onPostExecute(List<Event> result){
				/* get the number of meetings and set the next timer tick */
				onMeetingsLoaded(result);
			}
		}

		List<Event> mMeetings;
		private AsyncTask<Void, Void, List<Event>> mLoadMeetingsTask;

		/* Handler to load the meetings once a minute in interactive mode. */
		final Handler mLoadMeetingsHandler = new Handler(){
			@Override
			public void handleMessage(Message message){
				switch(message.what){
					case MSG_LOAD_MEETINGS:
						cancelLoadMeetingTask();
						mLoadMeetingsTask = new LoadMeetingsTask();
						mLoadMeetingsTask.execute();
						break;
				}
			}
		};
	}

	private static class EngineHandler extends Handler{
		private final WeakReference<CautionWatchFace.Engine> mWeakReference;

		public EngineHandler(CautionWatchFace.Engine reference){
			mWeakReference = new WeakReference<>(reference);
		}

		@Override
		public void handleMessage(Message msg){
			CautionWatchFace.Engine engine = mWeakReference.get();
			if(engine != null){
				switch(msg.what){
					case MSG_UPDATE_TIME:
						engine.handleUpdateTimeMessage();
						break;
				}
			}
		}
	}

}
