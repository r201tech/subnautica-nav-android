package com.doubao.subnauticanav;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** 地图视图：底图 + 玩家位置标记，支持双指缩放 / 单指拖动 */
public class MapView extends View {

    private Bitmap map;
    private float bx = -1f, by = -1f;      // 玩家在底图上的像素坐标
    private float heading = Float.NaN;     // 朝向（度，0=上/北，顺时针）
    private float scale = 1f;              // 每个底图像素显示成多少屏幕像素
    private float tx = 0f, ty = 0f;
    private boolean inited = false;

    public interface OnPickDirectionListener { void onPickDirection(float headingDeg); }
    private OnPickDirectionListener pickListener;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public MapView(Context c) {
        super(c);
        linePaint.setColor(0xFF7FE9FF);
        linePaint.setStrokeWidth(dp(1));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setAlpha(150);

        dotPaint.setColor(0xFFFF3B30);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setShadowLayer(dp(6), 0, 0, 0xCC000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(11));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setShadowLayer(dp(3), 0, 0, Color.BLACK);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float target = scale * d.getScaleFactor();
                float min = minScale();
                target = Math.max(min, Math.min(8f, target));
                float ratio = target / scale;
                tx = d.getFocusX() - (d.getFocusX() - tx) * ratio;
                ty = d.getFocusY() - (d.getFocusY() - ty) * ratio;
                scale = target;
                invalidate();
                return true;
            }
        });

        arrowPaint.setColor(0xFF52D8FF);
        arrowPaint.setStyle(Paint.Style.FILL);

        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public void onLongPress(MotionEvent e) {
                if (bx < 0 || pickListener == null) return;
                float px = (e.getX() - tx) / scale;
                float py = (e.getY() - ty) / scale;
                float ddx = px - bx, ddy = py - by;
                if (Math.hypot(ddx, ddy) < 0.5f) return;
                // 底图 y 向下 = 世界 z 减小；正北 = 上方
                float deg = (float) Math.toDegrees(Math.atan2(ddx, -ddy));
                if (deg < 0) deg += 360f;
                heading = deg;
                pickListener.onPickDirection(deg);
                invalidate();
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e2.getPointerCount() > 1) return false;
                tx -= dx; ty -= dy;
                clampTranslation();
                invalidate();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (bx >= 0) focusOn(bx, by, 4f * getWidth() / map.getWidth());
                return true;
            }
        });

        setBackgroundColor(0xFF060A10);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setMap(Bitmap b) {
        map = b;
        inited = false;
        requestLayout();
        invalidate();
    }

    public void setMarker(float bitmapX, float bitmapY) {
        bx = bitmapX;
        by = bitmapY;
        if (getWidth() > 0 && map != null) {
            float targetScale = Math.max(minScale(), 3.2f * getWidth() / map.getWidth());
            focusOn(bx, by, targetScale);
        } else {
            invalidate();
        }
    }

    public void setHeading(float deg) {
        heading = deg;
        invalidate();
    }

    public float getHeading() { return heading; }

    public void setOnPickDirectionListener(OnPickDirectionListener l) { pickListener = l; }

    private float minScale() {
        if (map == null || getWidth() == 0) return 0.2f;
        return Math.max(getWidth() / (float) map.getWidth(), getHeight() / (float) map.getHeight());
    }

    public void focusOn(float bitmapX, float bitmapY, float targetScale) {
        if (map == null || getWidth() == 0) return;
        scale = Math.max(minScale(), Math.min(8f, targetScale));
        tx = getWidth() / 2f - bitmapX * scale;
        ty = getHeight() / 2f - bitmapY * scale;
        clampTranslation();
        invalidate();
    }

    private void clampTranslation() {
        if (map == null) return;
        float w = map.getWidth() * scale, h = map.getHeight() * scale;
        if (w <= getWidth()) tx = (getWidth() - w) / 2f;
        else tx = Math.min(0f, Math.max(getWidth() - w, tx));
        if (h <= getHeight()) ty = (getHeight() - h) / 2f;
        else ty = Math.min(0f, Math.max(getHeight() - h, ty));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (map != null && !inited) {
            inited = true;
            if (bx >= 0) focusOn(bx, by, 3.2f * w / map.getWidth());
            else focusOn(map.getWidth() / 2f, map.getHeight() / 2f, minScale());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null) return;

        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);
        canvas.drawBitmap(map, 0, 0, bmpPaint);
        canvas.restore();

        if (bx < 0) return;
        float sx = bx * scale + tx, sy = by * scale + ty;

        // 十字定位线
        canvas.drawLine(0, sy, getWidth(), sy, linePaint);
        canvas.drawLine(sx, 0, sx, getHeight(), linePaint);

        // 光圈 + 圆点
        dotPaint.setAlpha(40);
        canvas.drawCircle(sx, sy, dp(18), dotPaint);
        dotPaint.setAlpha(255);
        canvas.drawCircle(sx, sy, dp(5.5f), dotPaint);
        dotPaint.setColor(Color.WHITE);
        canvas.drawCircle(sx, sy, dp(2), dotPaint);
        dotPaint.setColor(0xFFFF3B30);

        canvas.drawText("你在这里", sx + dp(10), sy - dp(10), textPaint);

        // 朝向三角：绕玩家点旋转
        if (!Float.isNaN(heading)) {
            canvas.save();
            canvas.rotate(heading, sx, sy);
            arrowPath.reset();
            arrowPath.moveTo(sx, sy - dp(34));
            arrowPath.lineTo(sx - dp(8), sy - dp(19));
            arrowPath.lineTo(sx + dp(8), sy - dp(19));
            arrowPath.close();
            canvas.drawPath(arrowPath, arrowPaint);
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() == 1) gestureDetector.onTouchEvent(e);
        return true;
    }
}
