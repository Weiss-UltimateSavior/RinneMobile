package com.apps.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;
import com.apps.LauncherActivity;

public class LauncherParticleView extends View {
    private static final int PARTICLE_COUNT = 56;
    private static final int SAKURA_ACTIVE_COUNT = 20;
    private static final int RIPPLES_ACTIVE_COUNT = 8;
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
    private final RadialGradient[] fireflyShaders = new RadialGradient[COLORS.length];
    private final Path shapePath = new Path();
    private String fireflyShaderTheme = "";
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
            particles[i] = createParticle(i, width, height);
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

        // 更新阶段
        for (Particle particle : particles) {
            if (particle == null) continue;
            if (isStarStyle()) {
                // 不更新
            } else if (isSakuraStyle()) {
                updateSakura(particle, deltaSeconds, getWidth(), getHeight());
            } else if (isFirefliesStyle()) {
                updateFireflies(particle, deltaSeconds, getWidth(), getHeight());
            } else if (isConstellationStyle()) {
                updateConstellation(particle, deltaSeconds, getWidth(), getHeight());
            } else if (isRipplesStyle()) {
                updateRipples(particle, deltaSeconds, getWidth(), getHeight());
            } else {
                particle.update(deltaSeconds, getWidth(), getHeight());
            }
        }

        // 绘制阶段
        paint.setShader(null);
        if (isConstellationStyle()) {
            drawConstellation(canvas);
        } else {
            for (Particle particle : particles) {
                if (particle == null) continue;
                paint.setShader(null);
                paint.setColor(particle.color);
                if (isRainStyle()) {
                    paint.setAlpha(particle.alpha);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(particle.radius);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    canvas.drawLine(particle.x - particle.length * 0.34f, particle.y - particle.length,
                            particle.x, particle.y, paint);
                } else if (isStarStyle()) {
                    paint.setAlpha(starAlpha(particle, now));
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1f));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    canvas.drawLine(particle.x - particle.radius, particle.y,
                            particle.x + particle.radius, particle.y, paint);
                    canvas.drawLine(particle.x, particle.y - particle.radius,
                            particle.x, particle.y + particle.radius, paint);
                } else if (isSakuraStyle()) {
                    drawSakura(canvas, particle);
                } else if (isFirefliesStyle()) {
                    drawFireflies(canvas, particle, now);
                } else if (isRipplesStyle()) {
                    drawRipples(canvas, particle);
                } else {
                    paint.setAlpha(particle.alpha);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(particle.x, particle.y, particle.radius, paint);
                }
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
                : LauncherActivity.PARTICLE_STYLE_SAKURA.equals(style)
                ? LauncherActivity.PARTICLE_STYLE_SAKURA
                : LauncherActivity.PARTICLE_STYLE_FIREFLIES.equals(style)
                ? LauncherActivity.PARTICLE_STYLE_FIREFLIES
                : LauncherActivity.PARTICLE_STYLE_CONSTELLATION.equals(style)
                ? LauncherActivity.PARTICLE_STYLE_CONSTELLATION
                : LauncherActivity.PARTICLE_STYLE_RIPPLES.equals(style)
                ? LauncherActivity.PARTICLE_STYLE_RIPPLES
                : LauncherActivity.PARTICLE_STYLE_FLOATING;
        if (safeStyle.equals(particleStyle)) return;
        particleStyle = safeStyle;
        if (getWidth() > 0 && getHeight() > 0) {
            for (int i = 0; i < particles.length; i++) {
                particles[i] = createParticle(i, getWidth(), getHeight());
            }
        }
        invalidate();
    }

    private Particle createParticle(int index, int width, int height) {
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
        } else if (isSakuraStyle()) {
            particle.length = 0f;
            particle.rotation = random.nextFloat() * (float) (Math.PI * 2d);
            particle.rotationSpeed = (random.nextFloat() - 0.5f) * 2.0f;
            particle.shapeType = random.nextInt(4);
            if (index >= SAKURA_ACTIVE_COUNT) {
                particle.radius = 0f;
                particle.speedX = 0f;
                particle.speedY = 0f;
                particle.alpha = 0;
            } else {
                particle.radius = dp(7f);
                particle.speedX = dp((random.nextFloat() - 0.5f) * 40f);
                particle.speedY = dp(30f + random.nextFloat() * 30f);
                particle.alpha = 120 + random.nextInt(60);
            }
        } else if (isFirefliesStyle()) {
            particle.radius = dp(2f + random.nextFloat() * 2f);
            particle.length = 0f;
            particle.speedX = 0f;
            particle.speedY = 0f;
            particle.wanderAngle = random.nextFloat() * (float) (Math.PI * 2d);
            particle.wanderSpeed = dp(8f + random.nextFloat() * 10f);
            particle.minAlpha = 20 + random.nextInt(30);
            particle.maxAlpha = 180 + random.nextInt(60);
            particle.pulsePhase = random.nextFloat() * (float) (Math.PI * 2d);
            particle.pulseSpeed = 0.8f + random.nextFloat() * 1.2f;
        } else if (isConstellationStyle()) {
            particle.radius = dp(1.5f + random.nextFloat() * 1.5f);
            particle.length = 0f;
            particle.speedX = dp((random.nextFloat() - 0.5f) * 16f);
            particle.speedY = dp((random.nextFloat() - 0.5f) * 16f);
            particle.alpha = 100 + random.nextInt(60);
        } else if (isRipplesStyle()) {
            if (index >= RIPPLES_ACTIVE_COUNT) {
                particle.maxRadius = 0f;
                particle.rippleSpeed = 0f;
            } else {
                particle.rippleProgress = random.nextFloat();
                particle.rippleSpeed = 0.1f + random.nextFloat() * 0.15f;
                particle.maxRadius = dp(60f + random.nextFloat() * 40f);
            }
            particle.radius = 0f;
            particle.length = 0f;
            particle.speedX = 0f;
            particle.speedY = 0f;
            particle.alpha = 0;
        } else {
            particle.radius = dp(2.2f + random.nextFloat() * 4.2f);
            particle.length = 0f;
            particle.speedX = dp((random.nextFloat() - 0.5f) * 10f);
            particle.speedY = -dp(7f + random.nextFloat() * 18f);
        }
        particle.colorIndex = random.nextInt(COLORS.length);
        particle.color = particleColor(particle.colorIndex);
        if (!isSakuraStyle() && !isConstellationStyle() && !isFirefliesStyle()
                && !isRipplesStyle()) {
            particle.alpha = 58 + random.nextInt(54);
        }
        return particle;
    }

    private void syncThemeColors() {
        String style = LauncherActivity.getLauncherThemeStyle(getContext());
        if (style.equals(activeThemeStyle)) return;
        activeThemeStyle = style;
        for (int i = 0; i < fireflyShaders.length; i++) fireflyShaders[i] = null; // 清理 shader 缓存
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

    private boolean isSakuraStyle() {
        return LauncherActivity.PARTICLE_STYLE_SAKURA.equals(particleStyle);
    }

    private boolean isFirefliesStyle() {
        return LauncherActivity.PARTICLE_STYLE_FIREFLIES.equals(particleStyle);
    }

    private boolean isConstellationStyle() {
        return LauncherActivity.PARTICLE_STYLE_CONSTELLATION.equals(particleStyle);
    }

    private boolean isRipplesStyle() {
        return LauncherActivity.PARTICLE_STYLE_RIPPLES.equals(particleStyle);
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
        boolean natsumeTheme = LauncherActivity.isNatsumeTheme(getContext());
        if (!rinneTheme && !anriTheme && !xinhaitianTheme && !natsumeTheme) {
            return COLORS[Math.abs(index) % COLORS.length];
        }
        int baseColor = rinneTheme ? LauncherActivity.RINNE_PRIMARY_COLOR
                : anriTheme ? LauncherActivity.ANRI_PRIMARY_COLOR
                : xinhaitianTheme ? LauncherActivity.XINHAITIAN_PRIMARY_COLOR
                : LauncherActivity.NATSUME_PRIMARY_COLOR;
        float[] hsv = new float[3];
        Color.colorToHSV(baseColor, hsv);
        hsv[1] = Math.max(0.22f, hsv[1] - 0.08f);
        hsv[2] = Math.min(1f, hsv[2] * (0.86f + (Math.abs(index) % COLORS.length) * 0.04f));
        return Color.HSVToColor(hsv);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void updateSakura(Particle particle, float delta, int width, int height) {
        if (particle.radius <= 0f) return;
        particle.y += particle.speedY * delta;
        particle.x += particle.speedX * delta;
        particle.rotation += particle.rotationSpeed * delta;
        float margin = particle.radius;
        if (particle.y > height + margin) {
            particle.y = -margin;
            particle.x = random.nextFloat() * width;
        }
        if (particle.x < margin) {
            particle.x = margin;
            particle.speedX = -particle.speedX;
        } else if (particle.x > width - margin) {
            particle.x = width - margin;
            particle.speedX = -particle.speedX;
        }
    }

    private void updateFireflies(Particle particle, float delta, int width, int height) {
        particle.wanderAngle += (random.nextFloat() - 0.5f) * 3.0f * delta;
        particle.x += (float) Math.cos(particle.wanderAngle) * particle.wanderSpeed * delta;
        particle.y += (float) Math.sin(particle.wanderAngle) * particle.wanderSpeed * delta;
        float margin = dp(14f);
        if (particle.x < -margin) {
            particle.x = width + margin;
        } else if (particle.x > width + margin) {
            particle.x = -margin;
        }
        if (particle.y < -margin) {
            particle.y = height + margin;
        } else if (particle.y > height + margin) {
            particle.y = -margin;
        }
    }

    private void updateConstellation(Particle particle, float delta, int width, int height) {
        particle.x += particle.speedX * delta;
        particle.y += particle.speedY * delta;
        if (particle.x < 0f || particle.x > width) {
            particle.speedX = -particle.speedX;
        }
        if (particle.y < 0f || particle.y > height) {
            particle.speedY = -particle.speedY;
        }
    }

    private void updateRipples(Particle particle, float delta, int width, int height) {
        if (particle.maxRadius <= 0f) return;
        particle.rippleProgress += particle.rippleSpeed * delta;
        if (particle.rippleProgress >= 1f) {
            particle.rippleProgress = 0f;
            particle.x = random.nextFloat() * width;
            particle.y = random.nextFloat() * height;
            particle.colorIndex = random.nextInt(COLORS.length);
            particle.color = particleColor(particle.colorIndex);
        }
    }

    private void drawSakura(Canvas canvas, Particle particle) {
        if (particle.radius <= 0f) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(particle.alpha);
        canvas.save();
        canvas.translate(particle.x, particle.y);
        canvas.rotate((float) Math.toDegrees(particle.rotation));
        float r = particle.radius;
        switch (particle.shapeType) {
            case 0: // 方块
                canvas.drawRect(-r, -r, r, r, paint);
                break;
            case 1: // 三角
                shapePath.reset();
                shapePath.moveTo(0f, -r);
                shapePath.lineTo(r * 0.866f, r * 0.5f);
                shapePath.lineTo(-r * 0.866f, r * 0.5f);
                shapePath.close();
                canvas.drawPath(shapePath, paint);
                break;
            case 2: // 圆
                canvas.drawCircle(0f, 0f, r, paint);
                break;
            case 3: // 十字
                canvas.drawRect(-r, -r * 0.3f, r, r * 0.3f, paint);
                canvas.drawRect(-r * 0.3f, -r, r * 0.3f, r, paint);
                break;
        }
        canvas.restore();
    }

    private void drawFireflies(Canvas canvas, Particle particle, long now) {
        int alpha = starAlpha(particle, now);
        RadialGradient shader = getFireflyShader(particle);
        canvas.save();
        canvas.translate(particle.x, particle.y);
        paint.setShader(shader);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0f, 0f, dp(14f), paint);
        paint.setShader(null);
        paint.setColor(particle.color);
        canvas.drawCircle(0f, 0f, particle.radius, paint);
        canvas.restore();
    }

    private RadialGradient getFireflyShader(Particle particle) {
        if (!fireflyShaderTheme.equals(activeThemeStyle)) {
            for (int i = 0; i < fireflyShaders.length; i++) fireflyShaders[i] = null;
            fireflyShaderTheme = activeThemeStyle;
        }
        int idx = particle.colorIndex;
        if (fireflyShaders[idx] == null) {
            fireflyShaders[idx] = new RadialGradient(0f, 0f, dp(14f),
                    particle.color, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }
        return fireflyShaders[idx];
    }

    private void drawConstellation(Canvas canvas) {
        float maxDist = dp(80f);
        // 第一遍：画所有粒子圆点
        paint.setStyle(Paint.Style.FILL);
        for (Particle particle : particles) {
            if (particle == null) continue;
            paint.setColor(particle.color);
            paint.setAlpha(particle.alpha);
            canvas.drawCircle(particle.x, particle.y, particle.radius, paint);
        }
        // 第二遍：两两检测距离，<maxDist 时画连线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        for (int i = 0; i < particles.length; i++) {
            Particle a = particles[i];
            if (a == null) continue;
            for (int j = i + 1; j < particles.length; j++) {
                Particle b = particles[j];
                if (b == null) continue;
                float dx = a.x - b.x;
                float dy = a.y - b.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < maxDist) {
                    int lineAlpha = (int) ((1f - dist / maxDist) * 80f);
                    paint.setColor(a.color);
                    paint.setAlpha(lineAlpha);
                    canvas.drawLine(a.x, a.y, b.x, b.y, paint);
                }
            }
        }
    }

    private void drawRipples(Canvas canvas, Particle particle) {
        if (particle.maxRadius <= 0f) return;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        float r = particle.rippleProgress * particle.maxRadius;
        int a = (int) ((1f - particle.rippleProgress) * 180f);
        paint.setAlpha(a);
        canvas.drawCircle(particle.x, particle.y, r, paint);
        // 内涟漪：progress 偏移 0.3，alpha 减半
        float innerProgress = particle.rippleProgress - 0.3f;
        if (innerProgress > 0f) {
            float innerR = innerProgress * particle.maxRadius;
            int innerA = (int) ((1f - innerProgress) * 90f);
            paint.setAlpha(innerA);
            canvas.drawCircle(particle.x, particle.y, innerR, paint);
        }
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
        // Sakura 按键瀑布
        float rotation;
        float rotationSpeed;
        int shapeType;
        // Fireflies 萤火虫
        float wanderAngle;
        float wanderSpeed;
        // Ripples 涟漪
        float rippleProgress;
        float rippleSpeed;
        float maxRadius;

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
