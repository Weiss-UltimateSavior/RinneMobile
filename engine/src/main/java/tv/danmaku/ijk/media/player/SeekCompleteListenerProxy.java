package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class SeekCompleteListenerProxy implements IMediaPlayer.OnSeekCompleteListener {

    public final /* synthetic */ IMediaPlayer.OnSeekCompleteListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public SeekCompleteListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnSeekCompleteListener onSeekCompleteListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onSeekCompleteListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnSeekCompleteListener
    public final void onSeekComplete(IMediaPlayer iMediaPlayer) {
        this.mListener.onSeekComplete(this.mProxy);
    }
}
