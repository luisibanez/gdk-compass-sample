/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.glass.sample.compass;

import com.google.android.glass.sample.compass.model.Place;
import com.google.android.glass.sample.compass.util.MathUtils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Location;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a stylized compass, with text labels at the cardinal and ordinal directions, and tick
 * marks at the half-winds. The red "needles" in the display mark the current heading.
 */
public class CompassView extends View {

    /** Various dimensions and other drawing-related constants. */
    private static final float NEEDLE_WIDTH = 10;
    private static final float NEEDLE_HEIGHT = 80;
    private static final int NEEDLE_COLOR = Color.GREEN;
    private static final float TICK_WIDTH = 2;
    private static final float TICK_HEIGHT = 10;
    private static final float DIRECTION_TEXT_HEIGHT = 84.0f;
    private static final float PLACE_TEXT_HEIGHT = 22.0f;
    private static final float PLACE_PIN_WIDTH = 14.0f;
    private static final float PLACE_TEXT_LEADING = 4.0f;
    private static final float PLACE_TEXT_MARGIN = 8.0f;

    /**
     * The maximum number of places names to allow to stack vertically underneath the compass
     * direction labels.
     */
    private static final int MAX_OVERLAPPING_PLACE_NAMES = 4;

    /**
     * If the difference between two consecutive headings is less than this value, the canvas will
     * be redrawn immediately rather than animated.
     */
    private static final float MIN_DISTANCE_TO_ANIMATE = 15.0f;

    /** The actual heading that represents the direction that the user is facing. */
    private float mHeading;

    /**
     * Represents the heading that is currently being displayed when the view is drawn. This is
     * used during animations, to keep track of the heading that should be drawn on the current
     * frame, which may be different than the desired end point.
     */
    private float mAnimatedHeading;

    private OrientationManager mOrientation;
    private List<Place> mNearbyPlaces;

    private final Paint mPaint;
    private final Paint mTickPaint;
    private final Path mPath;
    private final TextPaint mPlacePaint;
    private final Bitmap mPlaceBitmap;
    private final Rect mTextBounds;
    private final List<Rect> mAllBounds;
    private final NumberFormat mDistanceFormat;
    private final String[] mDirections;
    private final ValueAnimator mAnimator;

    public CompassView(Context context) {
        this(context, null, 0);
    }

    public CompassView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CompassView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mPaint = new Paint();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setAntiAlias(true);
        mPaint.setTextSize(DIRECTION_TEXT_HEIGHT);
        mPaint.setTypeface(Typeface.createFromFile(new File("/system/glass_fonts",
                "Roboto-Thin.ttf")));

        mTickPaint = new Paint();
        mTickPaint.setStyle(Paint.Style.STROKE);
        mTickPaint.setStrokeWidth(TICK_WIDTH);
        mTickPaint.setAntiAlias(true);
        mTickPaint.setColor(Color.WHITE);

        mPlacePaint = new TextPaint();
        mPlacePaint.setStyle(Paint.Style.FILL);
        mPlacePaint.setAntiAlias(true);
        mPlacePaint.setColor(Color.WHITE);
        mPlacePaint.setTextSize(PLACE_TEXT_HEIGHT);
        mPlacePaint.setTypeface(Typeface.createFromFile(new File("/system/glass_fonts",
                "Roboto-Light.ttf")));

        mPath = new Path();
        mTextBounds = new Rect();
        mAllBounds = new ArrayList<Rect>();

        mDistanceFormat = NumberFormat.getNumberInstance();
        mDistanceFormat.setMinimumFractionDigits(0);
        mDistanceFormat.setMaximumFractionDigits(1);

        mPlaceBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.place_mark);

        // We use NaN to indicate that the compass is being drawn for the first
        // time, so that we can jump directly to the starting orientation
        // instead of spinning from a default value of 0.
        mAnimatedHeading = Float.NaN;

        mDirections = context.getResources().getStringArray(R.array.direction_abbreviations);

        mAnimator = new ValueAnimator();
        setupAnimator();
    }

    /**
     * Sets the instance of {@link OrientationManager} that this view will use to get the current
     * heading and location.
     *
     * @param orientationManager the instance of {@code OrientationManager} that this view will use
     */
    public void setOrientationManager(OrientationManager orientationManager) {
        mOrientation = orientationManager;
    }

    /**
     * Gets the current heading in degrees.
     *
     * @return the current heading.
     */
    public float getHeading() {
        return mHeading;
    }

    /**
     * Sets the current heading in degrees and redraws the compass. If the angle is not between 0
     * and 360, it is shifted to be in that range.
     *
     * @param degrees the current heading
     */
    public void setHeading(float degrees) {
        mHeading = MathUtils.mod(degrees, 360.0f);
        animateTo(mHeading);
    }

    /**
     * Sets the list of nearby places that the compass should display. This list is recalculated
     * whenever the user's location changes, so that only locations within a certain distance will
     * be displayed.
     *
     * @param places the list of {@code Place}s that should be displayed
     */
    public void setNearbyPlaces(List<Place> places) {
        mNearbyPlaces = places;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // The view displays 90 degrees across its width so that one 90 degree head rotation is
        // equal to one full view cycle.
        float pixelsPerDegree = getWidth() / 90.0f;
        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;

        canvas.save();
        canvas.translate(-mAnimatedHeading * pixelsPerDegree + centerX, centerY);

        drawCompassDirections(canvas, pixelsPerDegree);

        canvas.restore();

        mPaint.setColor(NEEDLE_COLOR);
        drawNeedle(canvas, false);
        drawNeedle(canvas, true);
    }

    /**
     * Draws the compass direction strings (N, NW, W, etc.).
     *
     * @param canvas the {@link Canvas} upon which to draw
     * @param pixelsPerDegree the size, in pixels, of one degree step
     */
    private void drawCompassDirections(Canvas canvas, float pixelsPerDegree) {
        float degreesPerTick = 360.0f / mDirections.length;

        mPaint.setColor(Color.RED);

        // We draw two extra ticks/labels on each side of the view so that the
        // full range is visible even when the heading is approximately 0.
        for (int i = -2; i <= mDirections.length + 2; i++) {
            if (MathUtils.mod(i, 2) == 0) {
                // Draw a text label for the even indices.
                String direction = mDirections[MathUtils.mod(i, mDirections.length)];
                mPaint.getTextBounds(direction, 0, direction.length(), mTextBounds);

                canvas.drawText(direction,
                        i * degreesPerTick * pixelsPerDegree - mTextBounds.width() / 2,
                        mTextBounds.height() / 2, mPaint);
            } else {
                // Draw a tick mark for the odd indices.
                canvas.drawLine(i * degreesPerTick * pixelsPerDegree, -TICK_HEIGHT / 2, i
                        * degreesPerTick * pixelsPerDegree, TICK_HEIGHT / 2, mTickPaint);
            }
        }
    }


    /**
     * Draws a needle that is centered at the top or bottom of the compass.
     *
     * @param canvas the {@link Canvas} upon which to draw
     * @param bottom true to draw the bottom needle, or false to draw the top needle
     */
    private void drawNeedle(Canvas canvas, boolean bottom) {
        float centerX = getWidth() / 2.0f;
        float origin;
        float sign;

        // Flip the vertical coordinates if we're drawing the bottom needle.
        if (bottom) {
            origin = getHeight();
            sign = -1;
        } else {
            origin = 0;
            sign = 1;
        }

        float needleHalfWidth = NEEDLE_WIDTH / 2;

        mPath.reset();
        mPath.moveTo(centerX - needleHalfWidth, origin);
        mPath.lineTo(centerX - needleHalfWidth, origin + sign * (NEEDLE_HEIGHT - 4));
        mPath.lineTo(centerX, origin + sign * NEEDLE_HEIGHT);
        mPath.lineTo(centerX + needleHalfWidth, origin + sign * (NEEDLE_HEIGHT - 4));
        mPath.lineTo(centerX + needleHalfWidth, origin);
        mPath.close();

        canvas.drawPath(mPath, mPaint);
    }

    /**
     * Sets up a {@link ValueAnimator} that will be used to animate the compass
     * when the distance between two sensor events is large.
     */
    private void setupAnimator() {
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.setDuration(250);

        // Notifies us at each frame of the animation so we can redraw the view.
        mAnimator.addUpdateListener(new AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                mAnimatedHeading = MathUtils.mod((Float) mAnimator.getAnimatedValue(), 360.0f);
                invalidate();
            }
        });

        // Notifies us when the animation is over. During an animation, the user's head may have
        // continued to move to a different orientation than the original destination angle of the
        // animation. Since we can't easily change the animation goal while it is running, we call
        // animateTo() again, which will either redraw at the new orientation (if the difference is
        // small enough), or start another animation to the new heading. This seems to produce
        // fluid results.
        mAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animator) {
                animateTo(mHeading);
            }
        });
    }

    /**
     * Animates the view to the specified heading, or simply redraws it immediately if the
     * difference between the current heading and new heading are small enough that it wouldn't be
     * noticeable.
     *
     * @param end the desired heading
     */
    private void animateTo(float end) {
        // Only act if the animator is not currently running. If the user's orientation changes
        // while the animator is running, we wait until the end of the animation to update the
        // display again, to prevent jerkiness.
        if (!mAnimator.isRunning()) {
            float start = mAnimatedHeading;
            float distance = Math.abs(end - start);
            float reverseDistance = 360.0f - distance;
            float shortest = Math.min(distance, reverseDistance);

            if (Float.isNaN(mAnimatedHeading) || shortest < MIN_DISTANCE_TO_ANIMATE) {
                // If the distance to the destination angle is small enough (or if this is the
                // first time the compass is being displayed), it will be more fluid to just redraw
                // immediately instead of doing an animation.
                mAnimatedHeading = end;
                invalidate();
            } else {
                // For larger distances (i.e., if the compass "jumps" because of sensor calibration
                // issues), we animate the effect to provide a more fluid user experience. The
                // calculation below finds the shortest distance between the two angles, which may
                // involve crossing 0/360 degrees.
                float goal;

                if (distance < reverseDistance) {
                    goal = end;
                } else if (end < start) {
                    goal = end + 360.0f;
                } else {
                    goal = end - 360.0f;
                }

                mAnimator.setFloatValues(start, goal);
                mAnimator.start();
            }
        }
    }
}
