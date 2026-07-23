package tv.danmaku.ijk.media.player;

import android.media.MediaPlayer;
import android.media.TimedText;
import java.lang.ref.WeakReference;

/* JADX INFO: loaded from: classes.dex */
public final class AndroidMediaPlayerListenerAdapter implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnVideoSizeChangedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener, MediaPlayer.OnTimedTextListener {

    public final WeakReference mWeakPlayer;

    public final /* synthetic */ AndroidMediaPlayer mOwner;

    public AndroidMediaPlayerListenerAdapter(AndroidMediaPlayer owner, AndroidMediaPlayer weakPlayer) {
        this.mOwner = owner;
        this.mWeakPlayer = new WeakReference(weakPlayer);
    }

    @Override // android.media.MediaPlayer.OnBufferingUpdateListener
    public final void onBufferingUpdate(MediaPlayer mediaPlayer, int i8) {
        if (((AndroidMediaPlayer) this.mWeakPlayer.get()) == null) {
            return;
        }
        this.mOwner.notifyOnBufferingUpdate(i8);
    }

    @Override // android.media.MediaPlayer.OnCompletionListener
    public final void onCompletion(MediaPlayer mediaPlayer) {
        if (((AndroidMediaPlayer) this.mWeakPlayer.get()) == null) {
            return;
        }
        this.mOwner.notifyOnCompletion();
    }

    @Override // android.media.MediaPlayer.OnErrorListener
    public final boolean onError(MediaPlayer mediaPlayer, int i8, int i9) {
        return ((AndroidMediaPlayer) this.mWeakPlayer.get()) != null && this.mOwner.notifyOnError(i8, i9);
    }

    @Override // android.media.MediaPlayer.OnInfoListener
    public final boolean onInfo(MediaPlayer mediaPlayer, int i8, int i9) {
        return ((AndroidMediaPlayer) this.mWeakPlayer.get()) != null && this.mOwner.notifyOnInfo(i8, i9);
    }

    @Override // android.media.MediaPlayer.OnPreparedListener
    public final void onPrepared(MediaPlayer mediaPlayer) {
        if (((AndroidMediaPlayer) this.mWeakPlayer.get()) == null) {
            return;
        }
        this.mOwner.notifyOnPrepared();
    }

    @Override // android.media.MediaPlayer.OnSeekCompleteListener
    public final void onSeekComplete(MediaPlayer mediaPlayer) {
        if (((AndroidMediaPlayer) this.mWeakPlayer.get()) == null) {
            return;
        }
        this.mOwner.notifyOnSeekComplete();
    }

    @Override // android.media.MediaPlayer.OnTimedTextListener
    public final void onTimedText(MediaPlayer mediaPlayer, TimedText timedText) {
        if (((AndroidMediaPlayer) this.mWeakPlayer.get()) == null) {
            return;
        }
        this.mOwner.notifyOnTimedText(timedText != null ? new IjkTimedText(timedText.getBounds(), timedText.getText()) : null);
    }

    @Override // android.media.MediaPlayer.OnVideoSizeChangedListener
    public final void onVideoSizeChanged(MediaPlayer mediaPlayer, int i8, int i9) {
        if (((AndroidMediaPlayer) this.mWeakPlayer.get()) == null) {
            return;
        }
        this.mOwner.notifyOnVideoSizeChanged(i8, i9, 1, 1);
    }
}
