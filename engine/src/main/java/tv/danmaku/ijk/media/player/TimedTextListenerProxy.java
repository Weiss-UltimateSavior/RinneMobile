package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class TimedTextListenerProxy implements IMediaPlayer.OnTimedTextListener {

    public final /* synthetic */ IMediaPlayer.OnTimedTextListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public TimedTextListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnTimedTextListener onTimedTextListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onTimedTextListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnTimedTextListener
    public final void onTimedText(IMediaPlayer iMediaPlayer, IjkTimedText ijkTimedText) {
        this.mListener.onTimedText(this.mProxy, ijkTimedText);
    }
}
