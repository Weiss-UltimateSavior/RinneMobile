package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class InfoListenerProxy implements IMediaPlayer.OnInfoListener {

    public final /* synthetic */ IMediaPlayer.OnInfoListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public InfoListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnInfoListener onInfoListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onInfoListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnInfoListener
    public final boolean onInfo(IMediaPlayer iMediaPlayer, int i8, int i9) {
        return this.mListener.onInfo(this.mProxy, i8, i9);
    }
}
