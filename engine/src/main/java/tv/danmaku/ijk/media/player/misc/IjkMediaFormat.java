package tv.danmaku.ijk.media.player.misc;

import android.text.TextUtils;
import java.util.HashMap;
import java.util.Map;
import tv.danmaku.ijk.media.player.IjkMediaMeta;

/* JADX INFO: loaded from: classes.dex */
public class IjkMediaFormat implements IMediaFormat {
    public static final String CODEC_NAME_H264 = "h264";
    public static final String KEY_IJK_BIT_RATE_UI = "ijk-bit-rate-ui";
    public static final String KEY_IJK_CHANNEL_UI = "ijk-channel-ui";
    public static final String KEY_IJK_CODEC_LONG_NAME_UI = "ijk-codec-long-name-ui";
    public static final String KEY_IJK_CODEC_NAME_UI = "ijk-codec-name-ui";
    public static final String KEY_IJK_CODEC_PIXEL_FORMAT_UI = "ijk-pixel-format-ui";
    public static final String KEY_IJK_CODEC_PROFILE_LEVEL_UI = "ijk-profile-level-ui";
    public static final String KEY_IJK_FRAME_RATE_UI = "ijk-frame-rate-ui";
    public static final String KEY_IJK_RESOLUTION_UI = "ijk-resolution-ui";
    public static final String KEY_IJK_SAMPLE_RATE_UI = "ijk-sample-rate-ui";
    private static final Map<String, IjkStringFormatter> sFormatterMap = new HashMap();
    public final IjkMediaMeta.IjkStreamMeta mMediaFormat;

    public IjkMediaFormat(IjkMediaMeta.IjkStreamMeta ijkStreamMeta) {
        Map<String, IjkStringFormatter> map = sFormatterMap;
        map.put(KEY_IJK_CODEC_LONG_NAME_UI, new CodecLongNameFormatter(this));
        map.put(KEY_IJK_CODEC_NAME_UI, new CodecNameFormatter(this));
        map.put(KEY_IJK_BIT_RATE_UI, new CompositeMediaFormatter(0));
        map.put(KEY_IJK_CODEC_PROFILE_LEVEL_UI, new CompositeMediaFormatter(1));
        map.put(KEY_IJK_CODEC_PIXEL_FORMAT_UI, new CompositeMediaFormatter(2));
        map.put(KEY_IJK_RESOLUTION_UI, new CompositeMediaFormatter(3));
        map.put(KEY_IJK_FRAME_RATE_UI, new CompositeMediaFormatter(4));
        map.put(KEY_IJK_SAMPLE_RATE_UI, new CompositeMediaFormatter(5));
        map.put(KEY_IJK_CHANNEL_UI, new CompositeMediaFormatter(6));
        this.mMediaFormat = ijkStreamMeta;
    }

    @Override // tv.danmaku.ijk.media.player.misc.IMediaFormat
    public int getInteger(String str) {
        IjkMediaMeta.IjkStreamMeta ijkStreamMeta = this.mMediaFormat;
        if (ijkStreamMeta == null) {
            return 0;
        }
        return ijkStreamMeta.getInt(str);
    }

    @Override // tv.danmaku.ijk.media.player.misc.IMediaFormat
    public String getString(String str) {
        if (this.mMediaFormat == null) {
            return null;
        }
        Map<String, IjkStringFormatter> map = sFormatterMap;
        if (!map.containsKey(str)) {
            return this.mMediaFormat.getString(str);
        }
        String strA = map.get(str).format(this);
        return TextUtils.isEmpty(strA) ? "N/A" : strA;
    }
}
