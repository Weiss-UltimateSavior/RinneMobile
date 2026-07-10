package com.apps.PadUi;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 横屏游戏卡片容器，默认使用 3:5 宽高比；平板双行布局可指定受可用空间约束的高度。
 * 高度统一在 onMeasure 中生效，避免外部直接修改 LayoutParams 的时序问题。
 */
public class PadGameCardView extends FrameLayout {
    private int fixedCardHeight;

    public PadGameCardView(Context context) {
        super(context);
    }

    public PadGameCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PadGameCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setFixedCardHeight(int height) {
        int normalizedHeight = Math.max(0, height);
        if (fixedCardHeight == normalizedHeight) return;
        fixedCardHeight = normalizedHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = fixedCardHeight > 0
                ? fixedCardHeight
                : Math.round(width * 5f / 3f);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
