package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class ErrorListenerProxy implements IMediaPlayer.OnErrorListener {

    public final /* synthetic */ IMediaPlayer.OnErrorListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public ErrorListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnErrorListener onErrorListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onErrorListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnErrorListener
    public final boolean onError(IMediaPlayer iMediaPlayer, int i8, int i9) {
        return this.mListener.onError(this.mProxy, i8, i9);
    }
}
