package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class BufferingUpdateListenerProxy implements IMediaPlayer.OnBufferingUpdateListener {

    public final /* synthetic */ IMediaPlayer.OnBufferingUpdateListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public BufferingUpdateListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnBufferingUpdateListener onBufferingUpdateListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onBufferingUpdateListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnBufferingUpdateListener
    public final void onBufferingUpdate(IMediaPlayer iMediaPlayer, int i8) {
        this.mListener.onBufferingUpdate(this.mProxy, i8);
    }
}
