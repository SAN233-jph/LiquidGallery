package com.example.liquidgallery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class GlassPanel extends FrameLayout {
    private Paint backgroundPaint;
    private Paint borderPaint;
    private RectF rectF;
    private Path path;
    private float cornerRadius = 48f;

    public GlassPanel(Context context) {
        super(context);
        init();
    }

    public GlassPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlassPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        rectF = new RectF();
        path = new Path();

        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setStyle(Paint.Style.FILL);
        // Semi-transparent liquid glass translucent tint
        backgroundPaint.setColor(Color.argb(35, 255, 255, 255));

        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        // Specular highlight border simulating light catching a water droplet edge
        borderPaint.setColor(Color.argb(100, 255, 255, 255));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rectF.set(1.5f, 1.5f, w - 1.5f, h - 1.5f);
        path.reset();
        path.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.drawPath(path, backgroundPaint);
        canvas.drawPath(path, borderPaint);
        super.dispatchDraw(canvas);
    }
}
