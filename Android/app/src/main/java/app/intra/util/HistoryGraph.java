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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader.TileMode;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;
import java.util.Collection;


/**
 * A graph showing the DNS query activity over the past minute.  The sequence of events is
 * rendered as a QPS graph using a gradually diffusing gaussian convolution, reflecting the idea
 * that the more recent an event is, the more we care about the fine temporal detail.
 */
public class HistoryGraph extends View implements DnsActivityReader {

  private static final int WINDOW_MS = 60 * 1000;  // Show the last minute of activity
  private static final int RESOLUTION_MS = 100;  // Compute the QPS curve with 100ms granularity.
  private static final int PULSE_INTERVAL_MS = 10 * 1000;  // Mark a pulse every 10 seconds

  private static final int DATA_STROKE_WIDTH = 10;  // Display pixels
  private static final float BOTTOM_MARGIN_FRACTION = 0.1f;  // Space to reserve below y=0.
  private static final float RIGHT_MARGIN_FRACTION = 0.1f;  // Space to reserve right of t=0.

  private DnsQueryTracker tracker;
  private Paint dataPaint;   // Paint for the QPS curve itself.
  private Paint pulsePaint;  // Paint for the radiating pulses that also serve as x-axis marks.

  // Preallocate the curve to reduce garbage collection pressure.
  private int range = WINDOW_MS / RESOLUTION_MS;
  private float[] curve = new float[range];
  private float[] lines = new float[(curve.length - 1) * 4];

  // Indicate whether the current curve is all zero, which allows an optimization.
  private boolean curveIsEmpty = true;

  // The peak value of the graph.  This value rises as needed, then falls to zero when the graph
  // is empty.
  private float max = 0;

  // Value of SystemClock.elapsedRealtime() for the current frame.
  private long now;

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
  private static float gaussian(float mu, float inverseSigma, int x) {
    float z = (x - mu) * inverseSigma;
    return ((float) Math.exp(-z * z)) * inverseSigma;
  }

  @Override
  public void read(Collection<Long> activity) {
    // Reset the curve, and populate it if there are any events in the window.
    curveIsEmpty = true;
    float scale = 1.0f / RESOLUTION_MS;
    for (long t : activity) {
      long age = now - t;
      if (age < 0) {
        // Possible clock skew mishap.
        continue;
      }
      float e = age * scale;

      // Diffusion equation: sigma grows as sqrt(time).
      // Add a constant to avoid dividing by zero.
      float sigma = (float) Math.sqrt(e + DATA_STROKE_WIDTH);

      // Optimization: truncate the Gaussian at +/- 2.7 sigma.  Beyond 2.7 sigma, a gaussian is less
      // than 1/1000 of its peak value, which is not visible on our graph.
      float support = 2.7f * sigma;
      int right = (int) (e + support);
      if (right > range) {
        // This event is offscreen.
        continue;
      }
      if (curveIsEmpty) {
        curveIsEmpty = false;
        Arrays.fill(curve, 0.0f);
      }
      int left = Math.max(0, (int) (e - support));
      float inverseSigma = 1.0f / sigma;  // Optimization: only compute division once per event.
      for (int i = left; i < right; ++i) {
        curve[i] += gaussian(e, inverseSigma, i);
      }
    }

  }

  // Updates the curve contents or returns false if there are no contents.
  private boolean updateCurve() {
    tracker.readInto(this);
    return !curveIsEmpty;
  }

  private void updateMax() {
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
        color, color & 0x00FFFFFF, TileMode.CLAMP));
  }

  @Override
  protected void onMeasure(int w, int h) {
    int wMode = MeasureSpec.getMode(w);
    int width;
    if (wMode == MeasureSpec.AT_MOST || wMode == MeasureSpec.EXACTLY) {
      // Always fill the whole width.
      width = MeasureSpec.getSize(w);
    } else {
      width = getSuggestedMinimumWidth();
    }

    int hMode = MeasureSpec.getMode(h);
    int height;
    if (hMode == MeasureSpec.EXACTLY) {
      // Nothing we can do about it.
      height = MeasureSpec.getSize(h);
    } else {
      // Make the aspect ratio 1:1, but limit the height to fit on screen with some room to spare.
      int screenHeight = getResources().getDisplayMetrics().heightPixels;
      height = Math.min(width, (int)(0.8 * screenHeight));
      if (hMode == MeasureSpec.AT_MOST) {
        height = Math.min(height, MeasureSpec.getSize(h));
      }
    }

    setMeasuredDimension(width, height);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    // Normally the coordinate system puts 0,0 at the top left.  This puts it at the bottom right,
    // with positive axes pointed up and left.
    canvas.rotate(180, getWidth() / 2, getHeight() / 2);

    // Scale factors based on current window size.
    float xoffset = (float) (getWidth()) * RIGHT_MARGIN_FRACTION;
    float usableWidth = getWidth() - xoffset;
    float yoffset = getHeight() * BOTTOM_MARGIN_FRACTION;
    float usableHeight = getHeight() - (DATA_STROKE_WIDTH + yoffset);

    now = SystemClock.elapsedRealtime();
    float rightEndY;
    if (updateCurve()) {
      updateMax();

      float xscale = usableWidth / (curve.length - 1);
      float yscale = max == 0 ? 0 : usableHeight / max;

      // Convert the curve into lines in the appropriate scale, and draw it.
      // |lines| has 4 values (x0, y0, x1, y1) for every interval in |curve|.
      // We use drawLines instead of drawPath for performance, even though it creates some visual
      // artifacts.  See https://developer.android.com/topic/performance/vitals/render.
      for (int i = 1; i < curve.length; ++i) {
        int j = (i - 1) * 4;
        lines[j] = (i - 1) * xscale + xoffset;
        lines[j + 1] = curve[i - 1] * yscale + yoffset;
        lines[j + 2] = i * xscale + xoffset;
        lines[j + 3] = curve[i] * yscale + yoffset;
      }
      canvas.drawLines(lines, dataPaint);
      rightEndY = lines[1];
    } else {
      max = 0;
      // Draw a horizontal line at y = 0.
      canvas.drawLine(xoffset, yoffset, xoffset + usableWidth, yoffset, dataPaint);
      rightEndY = yoffset;
    }

    // Draw a circle at the right end of the line.
    float tagRadius = 2 * DATA_STROKE_WIDTH;
    float tagX = xoffset - tagRadius;
    canvas.drawCircle(tagX, rightEndY, tagRadius, dataPaint);

    // Draw pulses at regular intervals, growing and fading with age.
    float maxRadius = getWidth() - tagX;
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
