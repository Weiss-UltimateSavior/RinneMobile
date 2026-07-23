package tv.danmaku.ijk.media.player.widget;

import android.util.Log;
import android.widget.MediaController;
import tv.danmaku.ijk.media.player.IMediaPlayer;

public final class IjkVideoViewListener implements IMediaPlayer.OnVideoSizeChangedListener, IMediaPlayer.OnPreparedListener, IMediaPlayer.OnCompletionListener, IMediaPlayer.OnErrorListener, IMediaPlayer.OnBufferingUpdateListener {
    public final IjkVideoView mVideoView;

    public IjkVideoViewListener(IjkVideoView videoView) {
        this.mVideoView = videoView;
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer iMediaPlayer, int percent) {
        this.mVideoView.mBufferPercentage = percent;
    }

    @Override
    public void onCompletion(IMediaPlayer iMediaPlayer) {
        IjkVideoView videoView = this.mVideoView;
        videoView.mCurrentState = 5;
        videoView.mTargetState = 5;
        MediaController mediaController = videoView.mMediaController;
        if (mediaController != null) mediaController.hide();
        IMediaPlayer.OnCompletionListener listener = videoView.mOnCompletionListener;
        if (listener != null) listener.onCompletion(videoView.mMediaPlayer);
    }

    @Override
    public boolean onError(IMediaPlayer iMediaPlayer, int what, int extra) {
        IjkVideoView videoView = this.mVideoView;
        Log.d(videoView.TAG, "Error: " + what + "," + extra);
        videoView.mCurrentState = -1;
        videoView.mTargetState = -1;
        MediaController mediaController = videoView.mMediaController;
        if (mediaController != null) mediaController.hide();
        IMediaPlayer.OnErrorListener listener = videoView.mOnErrorListener;
        if (listener != null) listener.onError(videoView.mMediaPlayer, what, extra);
        return true;
    }

    @Override
    public void onPrepared(IMediaPlayer iMediaPlayer) {
        IjkVideoView videoView = this.mVideoView;
        videoView.mCurrentState = 2;
        IMediaPlayer.OnPreparedListener listener = videoView.mOnPreparedListener;
        if (listener != null) listener.onPrepared(videoView.mMediaPlayer);
        MediaController mediaController = videoView.mMediaController;
        if (mediaController != null) mediaController.setEnabled(true);
        videoView.mVideoWidth = iMediaPlayer.getVideoWidth();
        videoView.mVideoHeight = iMediaPlayer.getVideoHeight();
        int seek = videoView.mSeekWhenPrepared;
        if (seek != 0) videoView.seekTo(seek);
        if (videoView.mVideoWidth == 0 || videoView.mVideoHeight == 0) {
            if (videoView.mTargetState == 3) videoView.start();
            return;
        }
        videoView.getHolder().setFixedSize(videoView.mVideoWidth, videoView.mVideoHeight);
        if (videoView.mSurfaceWidth == videoView.mVideoWidth && videoView.mSurfaceHeight == videoView.mVideoHeight) {
            if (videoView.mTargetState == 3) {
                videoView.start();
                if (videoView.mMediaController != null) videoView.mMediaController.show();
            } else if (!videoView.isPlaying() && (seek != 0 || videoView.getCurrentPosition() > 0) && videoView.mMediaController != null) {
                videoView.mMediaController.show(0);
            }
        }
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int width, int height, int sarNum, int sarDen) {
        IjkVideoView videoView = this.mVideoView;
        videoView.mVideoWidth = iMediaPlayer.getVideoWidth();
        videoView.mVideoHeight = iMediaPlayer.getVideoHeight();
        if (videoView.mVideoWidth != 0 && videoView.mVideoHeight != 0) videoView.getHolder().setFixedSize(videoView.mVideoWidth, videoView.mVideoHeight);
    }
}
