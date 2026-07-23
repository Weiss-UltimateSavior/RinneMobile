package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class PreparedListenerProxy implements IMediaPlayer.OnPreparedListener {

    public final /* synthetic */ IMediaPlayer.OnPreparedListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public PreparedListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnPreparedListener onPreparedListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onPreparedListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnPreparedListener
    public final void onPrepared(IMediaPlayer iMediaPlayer) {
        this.mListener.onPrepared(this.mProxy);
    }
}
