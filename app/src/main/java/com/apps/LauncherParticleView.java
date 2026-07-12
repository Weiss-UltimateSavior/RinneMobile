package com.apps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;

public class LauncherParticleView extends View {
    private static final int PARTICLE_COUNT = 56;
    private static final long FRAME_DELAY_MS = 16L;
    private static final float MAX_FRAME_SECONDS = 0.04f;
    private static final int[] COLORS = {
            Color.rgb(34, 216, 142),
            Color.rgb(74, 144, 226),
            Color.rgb(255, 184, 76),
            Color.rgb(255, 100, 132),
            Color.rgb(150, 114, 255)
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(20260704L);
    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    private long lastFrameTime;
    private boolean running;
    private boolean particlesEnabled = true;
    private String particleStyle = LauncherActivity.PARTICLE_STYLE_FLOATING;
    private String activeThemeStyle = "";
    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };

    public LauncherParticleView(Context context) {
        super(context);
        init();
    }

    public LauncherParticleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LauncherParticleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (particlesEnabled) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            if (particlesEnabled) start();
        } else {
            stop();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        for (int i = 0; i < particles.length; i++) {
            particles[i] = createParticle(width, height);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!particlesEnabled) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;
        syncThemeColors();

        long now = System.nanoTime();
        float deltaSeconds = lastFrameTime == 0L
                ? 0f
                : Math.min((now - lastFrameTime) / 1_000_000_000f, MAX_FRAME_SECONDS);
        lastFrameTime = now;

        for (Particle particle : particles) {
            if (particle == null) continue;
            if (!isStarStyle()) particle.update(deltaSeconds, getWidth(), getHeight());
            paint.setColor(particle.color);
            paint.setAlpha(isStarStyle() ? starAlpha(particle, now) : particle.alpha);
            if (isRainStyle()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(particle.radius);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(particle.x - particle.length * 0.34f, particle.y - particle.length,
                        particle.x, particle.y, paint);
            } else if (isStarStyle()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(particle.x - particle.radius, particle.y,
                        particle.x + particle.radius, particle.y, paint);
                canvas.drawLine(particle.x, particle.y - particle.radius,
                        particle.x, particle.y + particle.radius, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(particle.x, particle.y, particle.radius, paint);
            }
        }
    }

    private void start() {
        if (!particlesEnabled) return;
        if (running) return;
        running = true;
        lastFrameTime = 0L;
        removeCallbacks(frameRunnable);
        post(frameRunnable);
    }

    private void stop() {
        running = false;
        lastFrameTime = 0L;
        removeCallbacks(frameRunnable);
    }

    public void setParticlesEnabled(boolean enabled) {
        if (particlesEnabled == enabled) return;
        particlesEnabled = enabled;
        if (enabled && getWindowVisibility() == VISIBLE && isAttachedToWindow()) {
            start();
        } else {
            stop();
            invalidate();
        }
    }

    public void setParticleStyle(String style) {
        String safeStyle = LauncherActivity.PARTICLE_STYLE_RAIN.equals(style)
                ? LauncherActivity.PARTICLE_STYLE_RAIN
                : LauncherActivity.PARTICLE_STYLE_STAR.equals(style)
                ? LauncherActivity.PARTICLE_STYLE_STAR
                : LauncherActivity.PARTICLE_STYLE_FLOATING;
        if (safeStyle.equals(particleStyle)) return;
        particleStyle = safeStyle;
        if (getWidth() > 0 && getHeight() > 0) {
            for (int i = 0; i < particles.length; i++) {
                particles[i] = createParticle(getWidth(), getHeight());
            }
        }
        invalidate();
    }

    private Particle createParticle(int width, int height) {
        Particle particle = new Particle();
        particle.x = random.nextFloat() * width;
        particle.y = random.nextFloat() * height;
        if (isRainStyle()) {
            particle.radius = dp(1.6f + random.nextFloat() * 1.2f);
            particle.length = dp(9f + random.nextFloat() * 13f);
            particle.speedX = dp(42f + random.nextFloat() * 32f);
            particle.speedY = dp(128f + random.nextFloat() * 96f);
        } else if (isStarStyle()) {
            particle.radius = dp(1.4f + random.nextFloat() * 2.5f);
            particle.length = 0f;
            particle.speedX = 0f;
            particle.speedY = 0f;
            particle.minAlpha = 18 + random.nextInt(28);
            particle.maxAlpha = 178 + random.nextInt(68);
            particle.pulsePhase = random.nextFloat() * (float) (Math.PI * 2d);
            particle.pulseSpeed = 1.15f + random.nextFloat() * 1.35f;
        } else {
            particle.radius = dp(2.2f + random.nextFloat() * 4.2f);
            particle.length = 0f;
            particle.speedX = dp((random.nextFloat() - 0.5f) * 10f);
            particle.speedY = -dp(7f + random.nextFloat() * 18f);
        }
        particle.colorIndex = random.nextInt(COLORS.length);
        particle.color = particleColor(particle.colorIndex);
        particle.alpha = 58 + random.nextInt(54);
        return particle;
    }

    private void syncThemeColors() {
        String style = LauncherActivity.getLauncherThemeStyle(getContext());
        if (style.equals(activeThemeStyle)) return;
        activeThemeStyle = style;
        for (Particle particle : particles) {
            if (particle == null) continue;
            particle.color = particleColor(particle.colorIndex);
        }
    }

    private boolean isRainStyle() {
        return LauncherActivity.PARTICLE_STYLE_RAIN.equals(particleStyle);
    }

    private boolean isStarStyle() {
        return LauncherActivity.PARTICLE_STYLE_STAR.equals(particleStyle);
    }

    private int starAlpha(Particle particle, long nowNanos) {
        float timeSeconds = nowNanos / 1_000_000_000f;
        float progress = (float) ((Math.sin(timeSeconds * particle.pulseSpeed + particle.pulsePhase) + 1d) * 0.5d);
        progress *= progress;
        return (int) (particle.minAlpha + (particle.maxAlpha - particle.minAlpha) * progress);
    }

    private int particleColor(int index) {
        boolean rinneTheme = LauncherActivity.isRinneTheme(getContext());
        boolean anriTheme = LauncherActivity.isAnriTheme(getContext());
        boolean xinhaitianTheme = LauncherActivity.isXinhaitianTheme(getContext());
        if (!rinneTheme && !anriTheme && !xinhaitianTheme) {
            return COLORS[Math.abs(index) % COLORS.length];
        }
        int baseColor = rinneTheme ? LauncherActivity.RINNE_PRIMARY_COLOR
                : anriTheme ? LauncherActivity.ANRI_PRIMARY_COLOR
                : LauncherActivity.XINHAITIAN_PRIMARY_COLOR;
        float[] hsv = new float[3];
        Color.colorToHSV(baseColor, hsv);
        hsv[1] = Math.max(0.22f, hsv[1] - 0.08f);
        hsv[2] = Math.min(1f, hsv[2] * (0.86f + (Math.abs(index) % COLORS.length) * 0.04f));
        return Color.HSVToColor(hsv);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class Particle {
        float x;
        float y;
        float radius;
        float speedX;
        float speedY;
        float length;
        int colorIndex;
        int color;
        int alpha;
        int minAlpha;
        int maxAlpha;
        float pulsePhase;
        float pulseSpeed;

        void update(float deltaSeconds, int width, int height) {
            x += speedX * deltaSeconds;
            y += speedY * deltaSeconds;

            float margin = radius * 2f;
            if (speedY < 0f && y < -margin) {
                y = height + margin;
            } else if (speedY > 0f && y > height + margin) {
                y = -margin;
            }

            if (x < -margin) {
                x = width + margin;
            } else if (x > width + margin) {
                x = -margin;
            }
        }
    }
}
