package com.apps.PadUi;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 横屏游戏卡片容器，强制 3:5 宽高比（宽:高 = 3:5）。
 * 高度在 onMeasure 中根据实际测量宽度自动计算，彻底消除外部 setLayoutParams 的时序问题。
 */
public class PadGameCardView extends FrameLayout {

    public PadGameCardView(Context context) {
        super(context);
    }

    public PadGameCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PadGameCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * 5f / 3f);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
