package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class VideoSizeChangedListenerProxy implements IMediaPlayer.OnVideoSizeChangedListener {

    public final /* synthetic */ IMediaPlayer.OnVideoSizeChangedListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public VideoSizeChangedListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnVideoSizeChangedListener onVideoSizeChangedListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onVideoSizeChangedListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnVideoSizeChangedListener
    public final void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int i8, int i9, int i10, int i11) {
        this.mListener.onVideoSizeChanged(this.mProxy, i8, i9, i10, i11);
    }
}
