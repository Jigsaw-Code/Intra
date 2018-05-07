/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.util;

import android.os.Build;
import android.os.Build.VERSION_CODES;
import app.intra.DnsVpnServiceState;
import app.intra.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader.TileMode;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.google.firebase.crash.FirebaseCrash;

import java.util.Queue;


/**
 * A graph showing the DNS query activity over the past minute.  The sequence of events is
 * rendered as a QPS graph using a gradually diffusing gaussian convolution, reflecting the idea
 * that the more recent an event is, the more we care about the fine temporal detail.
 */
public class HistoryGraph extends View {

  private static final String LOG_TAG = "HistoryGraph";

  private static final int WINDOW_MS = 60 * 1000;  // Show the last minute of activity
  private static final int RESOLUTION_MS = 100;  // Compute the QPS curve with 100ms granularity.
  private static final int PULSE_INTERVAL_MS = 10 * 1000;  // Mark a pulse every 10 seconds

  private static final int DATA_STROKE_WIDTH = 10;  // Display pixels
  private static final float BOTTOM_MARGIN_FRACTION = 0.1f;  // Space to reserve below y=0.
  private static final float RIGHT_MARGIN_FRACTION = 0.1f;  // Space to reserve right of t=0.

  private DnsQueryTracker tracker;
  private Paint dataPaint;   // Paint for the QPS curve itself.
  private Paint pulsePaint;  // Paint for the radiating pulses that also serve as x-axis marks.

  // The peak value of the graph.  This value rises as needed, then falls to zero when the graph
  // is empty.
  private float max = 0;

  public HistoryGraph(Context context, AttributeSet attrs) {
    super(context, attrs);
    tracker = DnsVpnServiceState.getInstance().getTracker(context);

    int color = getResources().getColor(R.color.accent_good);

    dataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    dataPaint.setStrokeWidth(DATA_STROKE_WIDTH);
    dataPaint.setStyle(Paint.Style.STROKE);
    dataPaint.setColor(color);

    pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    pulsePaint.setStrokeWidth(0);
    pulsePaint.setStyle(Paint.Style.STROKE);
    pulsePaint.setColor(color);
  }

  // Gaussian curve formula.  (Not normalized.)
  private static float gaussian(float mu, float sigma, int x) {
    float z = (x - mu) / sigma;
    return ((float) Math.exp(-z * z)) / sigma;
  }

  private static float[] smoothCurve(int range, float[] events) {
    float[] output = new float[range];
    for (float e : events) {
      // Diffusion equation: sigma grows as sqrt(time).
      // Add a constant to avoid dividing by zero.
      float sigma = (float) Math.sqrt(e + DATA_STROKE_WIDTH);

      // Optimization: truncate the Gaussian at +/- 2.7 sigma.  Beyond 2.7 sigma, a gaussian is less
      // than 1/1000 of its peak value, which is not visible on our graph.
      float support = 2.7f * sigma;
      int left = Math.max(0, (int) (e - support));
      int right = Math.min(range, (int) (e + support));
      for (int i = left; i < right; ++i) {
        output[i] += gaussian(e, sigma, i);
      }
    }
    return output;
  }

  // Returns a QPS curve to fill the window.  Not normalized. and raises this.max to the maximum
  // value if necessary.
  // If the curve is zero, max is reset to zero.
  private float[] getQpsCurve(long now) {
    Queue<Long> times = tracker.getRecentActivity();
    float scale = 1.0f / RESOLUTION_MS;
    float[] events = new float[times.size()];
    int i = 0;
    boolean empty = true;
    for (long t : times) {
      long age = now - t;
      if (age < WINDOW_MS) {
        empty = false;
      }
      events[i] = age * scale;
      ++i;
    }
    if (empty) {
      // Special case: there are no events in the window, so just return zero.
      return new float[2];
    }
    int range = WINDOW_MS / RESOLUTION_MS;
    return smoothCurve(range, events);
  }

  private void updateMax(float[] curve) {
    // Increase the maximum to fit the curve.
    float total = 0;
    for (float v : curve) {
      total += v;
      max = Math.max(max, v);
    }
    // Set the maximum to zero if the curve is all zeros.
    if (total == 0) {
      max = 0;
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    int color = dataPaint.getColor();
    dataPaint.setShader(new LinearGradient(0, 0, w, 0,
        color, Color.TRANSPARENT, TileMode.CLAMP));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    // Normally the coordinate system puts 0,0 at the top left.  This puts it at the bottom right,
    // with positive axes pointed up and left.
    canvas.rotate(180, canvas.getWidth() / 2, canvas.getHeight() / 2);

    long now = SystemClock.elapsedRealtime();
    float[] curve = getQpsCurve(now);
    if (curve.length <= 1) {
      FirebaseCrash.logcat(Log.ERROR, LOG_TAG, "Logic error in curve computation");
      return;
    }
    updateMax(curve);

    // Convert the curve into lines in the appropriate scale, and draw it.
    // |lines| has 4 values (x0, y0, x1, y1) for every interval in |curve|.
    float[] lines = new float[(curve.length - 1) * 4];
    float xoffset = (float) (canvas.getWidth()) * RIGHT_MARGIN_FRACTION;
    float usableWidth = canvas.getWidth() - xoffset;
    float xscale = usableWidth / (curve.length - 1);
    float yoffset = canvas.getHeight() * BOTTOM_MARGIN_FRACTION;
    float usableHeight = canvas.getHeight() - (DATA_STROKE_WIDTH + yoffset);
    float yscale = max == 0 ? 0 : usableHeight / max;
    for (int i = 1; i < curve.length; ++i) {
      int j = (i - 1) * 4;
      lines[j] = (i - 1) * xscale + xoffset;
      lines[j + 1] = curve[i - 1] * yscale + yoffset;
      lines[j + 2] = i * xscale + xoffset;
      lines[j + 3] = curve[i] * yscale + yoffset;
    }
    canvas.drawLines(lines, dataPaint);
    float tagRadius = 2 * DATA_STROKE_WIDTH;
    float tagX = xoffset - tagRadius;
    canvas.drawCircle(tagX, lines[1], tagRadius, dataPaint);

    // Draw pulses at regular intervals, growing and fading with age.
    float maxRadius = canvas.getWidth() - tagX;
    for (long age = now % PULSE_INTERVAL_MS; age < WINDOW_MS; age += PULSE_INTERVAL_MS) {
      float fraction = ((float) age) / WINDOW_MS;
      float radius = tagRadius + fraction * (maxRadius - tagRadius);
      int alpha = (int) (255 * (1 - fraction));
      pulsePaint.setAlpha(alpha);
      canvas.drawCircle(tagX, yoffset, radius, pulsePaint);
    }

    // Queue up the next animation frame.
    if (Build.VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
      // Redraw the UI at its preferred update frequency.
      postInvalidateOnAnimation();
    } else {
      // postInvalidateOnAnimation is only available in Jelly Bean and higher.  On older devices,
      // update every RESOLUTION_MS (currently 10 FPS, which is choppy but good enough).
      postInvalidateDelayed(RESOLUTION_MS);
    }
  }
}
