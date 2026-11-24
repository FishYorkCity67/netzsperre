package app.netzsperre;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Schlichter Schalter: flache Kapsel mit rundem Knopf, gleitet beim Umschalten. */
public class Toggle extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final int onColor, offColor, knobColor;
    private final float density;
    private boolean checked;
    private float pos;
    private ValueAnimator anim;

    public Toggle(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        onColor = c.getColor(R.color.danger);
        offColor = c.getColor(R.color.toggle_off);
        knobColor = c.getColor(R.color.knob);
    }

    void setChecked(boolean value, boolean animate) {
        if (anim != null) anim.cancel();
        checked = value;
        float target = value ? 1f : 0f;
        if (!animate || !isAttachedToWindow()) {
            pos = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(v -> {
            pos = (float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    boolean isChecked() {
        return checked;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(44 * density), Math.round(26 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        rect.set(0, 0, w, h);
        paint.setColor((Integer) argb.evaluate(pos, offColor, onColor));
        canvas.drawRoundRect(rect, r, r, paint);
        float knobR = r - 3 * density;
        float cx = r + (w - h) * pos;
        paint.setColor(knobColor);
        canvas.drawCircle(cx, r, knobR, paint);
    }
}
