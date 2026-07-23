package tv.danmaku.ijk.media.player;

import android.media.MediaDataSource;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/* JADX INFO: loaded from: classes.dex */
public final class AndroidMediaDataSourceProxy extends MediaDataSource {

    public final IMediaDataSource mDataSource;

    public AndroidMediaDataSourceProxy(IMediaDataSource iMediaDataSource) {
        this.mDataSource = iMediaDataSource;
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public final void close() {
        this.mDataSource.close();
    }

    @Override // android.media.MediaDataSource
    public final long getSize() {
        return this.mDataSource.getSize();
    }

    @Override // android.media.MediaDataSource
    public final int readAt(long j, byte[] bArr, int i8, int i9) {
        return this.mDataSource.readAt(j, bArr, i8, i9);
    }
}
