package tv.danmaku.ijk.media.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class CompletionListenerProxy implements IMediaPlayer.OnCompletionListener {

    public final /* synthetic */ IMediaPlayer.OnCompletionListener mListener;

    public final /* synthetic */ MediaPlayerProxy mProxy;

    public CompletionListenerProxy(MediaPlayerProxy mediaPlayerProxy, IMediaPlayer.OnCompletionListener onCompletionListener) {
        this.mProxy = mediaPlayerProxy;
        this.mListener = onCompletionListener;
    }

    @Override // tv.danmaku.ijk.media.player.IMediaPlayer.OnCompletionListener
    public final void onCompletion(IMediaPlayer iMediaPlayer) {
        this.mListener.onCompletion(this.mProxy);
    }
}
