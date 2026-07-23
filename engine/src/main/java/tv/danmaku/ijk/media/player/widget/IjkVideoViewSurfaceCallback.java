package tv.danmaku.ijk.media.player.widget;

import android.view.SurfaceHolder;
import android.widget.MediaController;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/* JADX INFO: loaded from: classes.dex */
public final class IjkVideoViewSurfaceCallback implements SurfaceHolder.Callback {

    public final /* synthetic */ IjkVideoView mVideoView;

    public IjkVideoViewSurfaceCallback(IjkVideoView videoView) {
        this.mVideoView = videoView;
    }

    @Override // android.view.SurfaceHolder.Callback
    public final void surfaceChanged(SurfaceHolder surfaceHolder, int i8, int i9, int i10) {
        IjkVideoView videoView = this.mVideoView;
        videoView.mSurfaceWidth = i9;
        videoView.mSurfaceHeight = i10;
        boolean z = false;
        boolean z8 = videoView.mTargetState == 3;
        if (videoView.mVideoWidth == i9 && videoView.mVideoHeight == i10) {
            z = true;
        }
        if (videoView.mMediaPlayer != null && z8 && z) {
            int i11 = videoView.mSeekWhenPrepared;
            if (i11 != 0) {
                videoView.seekTo(i11);
            }
            videoView.start();
            MediaController mediaController = videoView.mMediaController;
            if (mediaController != null) {
                if (mediaController.isShowing()) {
                    videoView.mMediaController.hide();
                }
                videoView.mMediaController.show();
            }
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public final void surfaceCreated(SurfaceHolder surfaceHolder) {
        IjkVideoView videoView = this.mVideoView;
        videoView.mSurfaceHolder = surfaceHolder;
        IjkMediaPlayer ijkMediaPlayer = videoView.mMediaPlayer;
        if (ijkMediaPlayer == null || videoView.mCurrentState != 6 || videoView.mTargetState != 7) {
            videoView.openVideoInternal();
            return;
        }
        ijkMediaPlayer.setDisplay(surfaceHolder);
        if (videoView.mSurfaceHolder == null && videoView.mCurrentState == 6) {
            videoView.mTargetState = 7;
            return;
        }
        IjkMediaPlayer ijkMediaPlayer2 = videoView.mMediaPlayer;
        if (ijkMediaPlayer2 == null || videoView.mCurrentState != 6) {
            if (videoView.mCurrentState == 8) {
                videoView.openVideoInternal();
            }
        } else {
            ijkMediaPlayer2.start();
            videoView.mCurrentState = 0;
            videoView.mTargetState = 0;
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public final void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        IjkMediaPlayer ijkMediaPlayer;
        IjkVideoView videoView = this.mVideoView;
        videoView.mSurfaceHolder = null;
        MediaController mediaController = videoView.mMediaController;
        if (mediaController != null) {
            mediaController.hide();
        }
        if (videoView.mCurrentState == 6 || (ijkMediaPlayer = videoView.mMediaPlayer) == null) {
            return;
        }
        ijkMediaPlayer.reset();
        videoView.mMediaPlayer.release();
        videoView.mMediaPlayer = null;
        videoView.mCurrentState = 0;
        videoView.mTargetState = 0;
    }
}
