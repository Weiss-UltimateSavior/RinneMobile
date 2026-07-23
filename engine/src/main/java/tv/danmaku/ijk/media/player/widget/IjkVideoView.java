package tv.danmaku.ijk.media.player.widget;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.MediaController;
import com.ies_net.artemis.VideoViewActivity;
import java.io.FileDescriptor;
import java.io.IOException;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
@SuppressLint("ViewConstructor") // Created programmatically with its owning VideoViewActivity.
public final class IjkVideoView extends SurfaceView implements MediaController.MediaPlayerControl {

    public final String TAG;

    public final IjkVideoViewListener mOnBufferingUpdateListenerInternal;

    public final IjkVideoViewListener mOnCompletionListenerInternal;

    public final VideoViewActivity mContext;

    public int mBufferPercentage;

    public int mCurrentState;

    public int mDuration;

    public final IjkVideoViewListener mOnErrorListenerInternal;

    public FileDescriptor mFileDescriptor;
    public MediaController mMediaController;

    public IjkMediaPlayer mMediaPlayer;

    public IMediaPlayer.OnCompletionListener mOnCompletionListener;

    public IMediaPlayer.OnErrorListener mOnErrorListener;

    public IMediaPlayer.OnPreparedListener mOnPreparedListener;

    public final IjkVideoViewListener mOnPreparedListenerInternal;

    public int mSeekWhenPrepared;

    public final IjkVideoViewListener mOnVideoSizeChangedListenerInternal;
    public int mSurfaceHeight;

    public SurfaceHolder mSurfaceHolder;

    public int mSurfaceWidth;

    public int mTargetState;

    public int mVideoHeight;

    public int mVideoWidth;

    public float mVolume;

    public IjkVideoView(VideoViewActivity videoViewActivity) {
        super(videoViewActivity);
        this.TAG = "VideoView";
        this.mCurrentState = 0;
        this.mTargetState = 0;
        this.mSurfaceHolder = null;
        this.mMediaPlayer = null;
        this.mOnVideoSizeChangedListenerInternal = new IjkVideoViewListener(this);
        this.mOnPreparedListenerInternal = new IjkVideoViewListener(this);
        this.mOnCompletionListenerInternal = new IjkVideoViewListener(this);
        this.mOnErrorListenerInternal = new IjkVideoViewListener(this);
        this.mOnBufferingUpdateListenerInternal = new IjkVideoViewListener(this);
        IjkVideoViewSurfaceCallback surfaceCallback = new IjkVideoViewSurfaceCallback(this);
        this.mContext = videoViewActivity;
        this.mVideoWidth = 0;
        this.mVideoHeight = 0;
        getHolder().addCallback(surfaceCallback);
        getHolder().setType(3);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        this.mCurrentState = 0;
        this.mTargetState = 0;
    }

    public final void attachMediaController() {
        MediaController mediaController;
        if (this.mMediaPlayer == null || (mediaController = this.mMediaController) == null) {
            return;
        }
        mediaController.setMediaPlayer(this);
        this.mMediaController.setAnchorView(getParent() instanceof View ? (View) getParent() : this);
        this.mMediaController.setEnabled(isInPlaybackState());
    }

    public final boolean isInPlaybackState() {
        int i8;
        return (this.mMediaPlayer == null || (i8 = this.mCurrentState) == -1 || i8 == 0 || i8 == 1) ? false : true;
    }

    public final void openVideoInternal() {
        IjkVideoViewListener aVar = this.mOnErrorListenerInternal;
        if (this.mFileDescriptor == null || this.mSurfaceHolder == null) {
            return;
        }
        Intent intent = new Intent("com.android.music.musicservicecommand");
        intent.putExtra("command", "pause");
        this.mContext.sendBroadcast(intent);
        IjkMediaPlayer ijkMediaPlayer = this.mMediaPlayer;
        if (ijkMediaPlayer != null) {
            ijkMediaPlayer.reset();
            this.mMediaPlayer.release();
            this.mMediaPlayer = null;
            this.mCurrentState = 0;
        }
        try {
            IjkMediaPlayer ijkMediaPlayer2 = new IjkMediaPlayer();
            this.mMediaPlayer = ijkMediaPlayer2;
            ijkMediaPlayer2.setOnPreparedListener(this.mOnPreparedListenerInternal);
            this.mMediaPlayer.setOnVideoSizeChangedListener(this.mOnVideoSizeChangedListenerInternal);
            this.mDuration = -1;
            this.mMediaPlayer.setOnCompletionListener(this.mOnCompletionListenerInternal);
            this.mMediaPlayer.setOnErrorListener(aVar);
            this.mMediaPlayer.setOnBufferingUpdateListener(this.mOnBufferingUpdateListenerInternal);
            this.mBufferPercentage = 0;
            this.mMediaPlayer.setDataSource(this.mFileDescriptor);
            this.mMediaPlayer.setDisplay(this.mSurfaceHolder);
            this.mMediaPlayer.setAudioStreamType(3);
            IjkMediaPlayer ijkMediaPlayer3 = this.mMediaPlayer;
            float f8 = this.mVolume;
            ijkMediaPlayer3.setVolume(f8, f8);
            this.mMediaPlayer.setScreenOnWhilePlaying(true);
            this.mMediaPlayer.prepareAsync();
            this.mCurrentState = 1;
            attachMediaController();
        } catch (IOException | IllegalArgumentException e8) {
            Log.w(this.TAG, "Unable to open content: " + this.mFileDescriptor, e8);
            this.mCurrentState = -1;
            this.mTargetState = -1;
            aVar.onError(this.mMediaPlayer, 1, 0);
        }
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean canPause() {
        return false;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean canSeekBackward() {
        return false;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean canSeekForward() {
        return false;
    }

    public final void setDataSource(FileDescriptor fileDescriptor, int i8) {
        this.mFileDescriptor = fileDescriptor;
        if (i8 >= 1000) {
            this.mVolume = 1.0f;
        } else if (i8 <= 0) {
            this.mVolume = 0.0f;
        } else {
            this.mVolume = i8 / 1000.0f;
        }
        this.mSeekWhenPrepared = 0;
        openVideoInternal();
        requestLayout();
        invalidate();
    }

    public final void toggleMediaControlsVisibility() {
        if (this.mMediaController.isShowing()) {
            this.mMediaController.hide();
        } else {
            this.mMediaController.show();
        }
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getAudioSessionId() {
        return 0;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getBufferPercentage() {
        if (this.mMediaPlayer != null) {
            return this.mBufferPercentage;
        }
        return 0;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return (int) this.mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public int getDuration() {
        if (!isInPlaybackState()) {
            this.mDuration = -1;
            return -1;
        }
        int i8 = this.mDuration;
        if (i8 > 0) {
            return i8;
        }
        int duration = (int) this.mMediaPlayer.getDuration();
        this.mDuration = duration;
        return duration;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final boolean isPlaying() {
        return isInPlaybackState() && this.mMediaPlayer.isPlaying();
    }

    @Override // android.view.View, android.view.KeyEvent.Callback
    public final boolean onKeyDown(int i8, KeyEvent keyEvent) {
        boolean z = (i8 == 4 || i8 == 24 || i8 == 25 || i8 == 82 || i8 == 5 || i8 == 6) ? false : true;
        if (isInPlaybackState() && z && this.mMediaController != null) {
            if (i8 == 79 || i8 == 85) {
                if (this.mMediaPlayer.isPlaying()) {
                    pause();
                    this.mMediaController.show();
                    return true;
                }
                start();
                this.mMediaController.hide();
                return true;
            }
            if (i8 == 86 && this.mMediaPlayer.isPlaying()) {
                pause();
                this.mMediaController.show();
            } else {
                toggleMediaControlsVisibility();
            }
        }
        return super.onKeyDown(i8, keyEvent);
    }

    @Override // android.view.SurfaceView, android.view.View
    public final void onMeasure(int i8, int i9) {
        int i10;
        int defaultSize = View.getDefaultSize(this.mVideoWidth, i8);
        int defaultSize2 = View.getDefaultSize(this.mVideoHeight, i9);
        int i11 = this.mVideoWidth;
        if (i11 > 0 && (i10 = this.mVideoHeight) > 0) {
            if (i11 * defaultSize2 > i10 * defaultSize) {
                defaultSize2 = (i10 * defaultSize) / i11;
            } else if (i11 * defaultSize2 < i10 * defaultSize) {
                defaultSize = (i11 * defaultSize2) / i10;
            }
        }
        setMeasuredDimension(defaultSize, defaultSize2);
    }

    @Override // android.view.View
    public final boolean onTouchEvent(MotionEvent motionEvent) {
        if (!isInPlaybackState() || this.mMediaController == null) {
            return false;
        }
        toggleMediaControlsVisibility();
        if (motionEvent.getAction() == MotionEvent.ACTION_UP) performClick();
        return false;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override // android.view.View
    public final boolean onTrackballEvent(MotionEvent motionEvent) {
        if (!isInPlaybackState() || this.mMediaController == null) {
            return false;
        }
        toggleMediaControlsVisibility();
        return false;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final void pause() {
        if (isInPlaybackState() && this.mMediaPlayer.isPlaying()) {
            this.mMediaPlayer.pause();
            this.mCurrentState = 4;
        }
        this.mTargetState = 4;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final void seekTo(int i8) {
        if (!isInPlaybackState()) {
            this.mSeekWhenPrepared = i8;
        } else {
            this.mMediaPlayer.seekTo(i8);
            this.mSeekWhenPrepared = 0;
        }
    }

    public void setMediaController(MediaController mediaController) {
        MediaController mediaController2 = this.mMediaController;
        if (mediaController2 != null) {
            mediaController2.hide();
        }
        this.mMediaController = mediaController;
        attachMediaController();
    }

    public void setOnCompletionListener(IMediaPlayer.OnCompletionListener onCompletionListener) {
        this.mOnCompletionListener = onCompletionListener;
    }

    public void setOnErrorListener(IMediaPlayer.OnErrorListener onErrorListener) {
        this.mOnErrorListener = onErrorListener;
    }

    public void setOnPreparedListener(IMediaPlayer.OnPreparedListener onPreparedListener) {
        this.mOnPreparedListener = onPreparedListener;
    }

    @Override // android.widget.MediaController.MediaPlayerControl
    public final void start() {
        if (isInPlaybackState()) {
            this.mMediaPlayer.start();
            this.mCurrentState = 3;
        }
        this.mTargetState = 3;
    }
}
