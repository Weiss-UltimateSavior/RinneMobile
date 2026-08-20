package com.core.launcher;

import static org.junit.Assert.assertEquals;

import com.core.launcherbridge.LauncherKrkrBridge;
import org.junit.Test;

/**
 * 校验 krkr2 渲染/内存引擎键（P1+P2）的白名单归一化不变量：合法值保序返回、非法值归为
 * 空串（= 不管理）。这些归一化结果是 JSON 注入与 per-game 覆盖的取值依据。
 */
public class LauncherKrkrBridgeRenderConfigTest {

    @Test
    public void normalizeRenderer_acceptsSoftwareAndOpengl() {
        assertEquals("software", LauncherKrkrBridge.normalizeRenderer("software"));
        assertEquals("opengl", LauncherKrkrBridge.normalizeRenderer("opengl"));
        assertEquals("opengl", LauncherKrkrBridge.normalizeRenderer(" OpenGL "));
        assertEquals("software", LauncherKrkrBridge.normalizeRenderer("SOFTWARE"));
    }

    @Test
    public void normalizeRenderer_rejectsUnknown() {
        assertEquals("", LauncherKrkrBridge.normalizeRenderer("vulkan"));
        assertEquals("", LauncherKrkrBridge.normalizeRenderer(""));
        assertEquals("", LauncherKrkrBridge.normalizeRenderer(null));
        assertEquals("", LauncherKrkrBridge.normalizeRenderer("auto"));
    }

    @Test
    public void normalizeSoftwareDrawThread_acceptsAutoToEight() {
        assertEquals("0", LauncherKrkrBridge.normalizeSoftwareDrawThread("0"));
        assertEquals("4", LauncherKrkrBridge.normalizeSoftwareDrawThread("4"));
        assertEquals("8", LauncherKrkrBridge.normalizeSoftwareDrawThread("8"));
        assertEquals("5", LauncherKrkrBridge.normalizeSoftwareDrawThread(" 5 "));
    }

    @Test
    public void normalizeSoftwareDrawThread_rejectsOutOfRange() {
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareDrawThread("9"));
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareDrawThread("-1"));
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareDrawThread("abc"));
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareDrawThread(null));
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareDrawThread(""));
    }

    @Test
    public void normalizeSoftwareCompressTex_acceptsWhitelist() {
        assertEquals("none", LauncherKrkrBridge.normalizeSoftwareCompressTex("none"));
        assertEquals("halfline", LauncherKrkrBridge.normalizeSoftwareCompressTex("halfline"));
        assertEquals("lz4", LauncherKrkrBridge.normalizeSoftwareCompressTex("lz4"));
        assertEquals("lz4+tlg5", LauncherKrkrBridge.normalizeSoftwareCompressTex("lz4+tlg5"));
        assertEquals("lz4+tlg5", LauncherKrkrBridge.normalizeSoftwareCompressTex("LZ4+TLG5"));
    }

    @Test
    public void normalizeSoftwareCompressTex_rejectsUnknown() {
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareCompressTex("tlg5"));
        assertEquals("", LauncherKrkrBridge.normalizeSoftwareCompressTex(null));
    }

    @Test
    public void normalizeOglCompressTex_acceptsWhitelist() {
        assertEquals("none", LauncherKrkrBridge.normalizeOglCompressTex("none"));
        assertEquals("half", LauncherKrkrBridge.normalizeOglCompressTex("half"));
        assertEquals("etc2", LauncherKrkrBridge.normalizeOglCompressTex("etc2"));
        assertEquals("pvrtc", LauncherKrkrBridge.normalizeOglCompressTex("pvrtc"));
    }

    @Test
    public void normalizeOglCompressTex_rejectsUnknown() {
        assertEquals("", LauncherKrkrBridge.normalizeOglCompressTex("etc"));
        assertEquals("", LauncherKrkrBridge.normalizeOglCompressTex("bitmap"));
        assertEquals("", LauncherKrkrBridge.normalizeOglCompressTex(null));
    }

    @Test
    public void normalizeMemUsage_acceptsWhitelist() {
        assertEquals("unlimited", LauncherKrkrBridge.normalizeMemUsage("unlimited"));
        assertEquals("high", LauncherKrkrBridge.normalizeMemUsage("high"));
        assertEquals("medium", LauncherKrkrBridge.normalizeMemUsage("medium"));
        assertEquals("low", LauncherKrkrBridge.normalizeMemUsage("low"));
    }

    @Test
    public void normalizeMemUsage_rejectsUnknown() {
        assertEquals("", LauncherKrkrBridge.normalizeMemUsage("max"));
        assertEquals("", LauncherKrkrBridge.normalizeMemUsage(""));
        assertEquals("", LauncherKrkrBridge.normalizeMemUsage(null));
    }

    @Test
    public void normalizeOglMaxTexsize_acceptsAutoAndPowerSizes() {
        assertEquals("0", LauncherKrkrBridge.normalizeOglMaxTexsize("0"));
        assertEquals("1024", LauncherKrkrBridge.normalizeOglMaxTexsize("1024"));
        assertEquals("2048", LauncherKrkrBridge.normalizeOglMaxTexsize("2048"));
        assertEquals("8192", LauncherKrkrBridge.normalizeOglMaxTexsize("8192"));
        assertEquals("16384", LauncherKrkrBridge.normalizeOglMaxTexsize("16384"));
    }

    @Test
    public void normalizeOglMaxTexsize_rejectsOutOfRange() {
        assertEquals("", LauncherKrkrBridge.normalizeOglMaxTexsize("512"));
        assertEquals("", LauncherKrkrBridge.normalizeOglMaxTexsize("32768"));
        assertEquals("", LauncherKrkrBridge.normalizeOglMaxTexsize("abc"));
        assertEquals("", LauncherKrkrBridge.normalizeOglMaxTexsize(null));
    }

    @Test
    public void normalizeOglAccurateRender_acceptsBooleans() {
        assertEquals("1", LauncherKrkrBridge.normalizeOglAccurateRender("1"));
        assertEquals("1", LauncherKrkrBridge.normalizeOglAccurateRender("true"));
        assertEquals("1", LauncherKrkrBridge.normalizeOglAccurateRender(" TRUE "));
        assertEquals("0", LauncherKrkrBridge.normalizeOglAccurateRender("0"));
        assertEquals("0", LauncherKrkrBridge.normalizeOglAccurateRender("false"));
    }

    @Test
    public void normalizeOglAccurateRender_rejectsUnknown() {
        assertEquals("", LauncherKrkrBridge.normalizeOglAccurateRender("yes"));
        assertEquals("", LauncherKrkrBridge.normalizeOglAccurateRender(null));
        assertEquals("", LauncherKrkrBridge.normalizeOglAccurateRender(""));
    }

    @Test
    public void normalizeFpsLimit_acceptsWhitelist() {
        assertEquals("60", LauncherKrkrBridge.normalizeFpsLimit("60"));
        assertEquals("45", LauncherKrkrBridge.normalizeFpsLimit("45"));
        assertEquals("30", LauncherKrkrBridge.normalizeFpsLimit("30"));
        assertEquals("15", LauncherKrkrBridge.normalizeFpsLimit("15"));
    }

    @Test
    public void normalizeFpsLimit_rejectsUnknown() {
        assertEquals("", LauncherKrkrBridge.normalizeFpsLimit("120"));
        assertEquals("", LauncherKrkrBridge.normalizeFpsLimit("24"));
        assertEquals("", LauncherKrkrBridge.normalizeFpsLimit(null));
    }
}
