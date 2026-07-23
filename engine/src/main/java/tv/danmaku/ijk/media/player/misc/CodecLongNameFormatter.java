package tv.danmaku.ijk.media.player.misc;

import tv.danmaku.ijk.media.player.IjkMediaMeta;

/* JADX INFO: loaded from: classes.dex */
public final class CodecLongNameFormatter extends IjkStringFormatter {

    public final /* synthetic */ IjkMediaFormat mFormat;

    public CodecLongNameFormatter(IjkMediaFormat ijkMediaFormat) {
        this.mFormat = ijkMediaFormat;
    }

    @Override // IjkStringFormatter
    public final String format(IjkMediaFormat ijkMediaFormat) {
        return this.mFormat.mMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_LONG_NAME);
    }
}
