package com.apps.agent;

import java.io.IOException;
import java.util.Arrays;

/** Provider-neutral write/verify/rollback core so destructive failure semantics are unit-testable. */
final class AgentMutationTransaction {
    interface DocumentIo {
        byte[] read() throws Exception;
        void write(byte[] value) throws Exception;
    }

    interface Cancellation { boolean isActive(); }
    interface Commit { void run() throws Exception; }

    static final class Failure extends IOException {
        final boolean restored;
        Failure(boolean restored, Throwable cause) {
            super(restored ? "写入失败，已恢复原文件" : "写入和自动恢复均失败", cause);
            this.restored = restored;
        }
    }

    private AgentMutationTransaction() { }

    static void replace(DocumentIo io, byte[] expectedBefore, byte[] after,
                        Cancellation cancellation, Commit commit) throws Exception {
        if (!cancellation.isActive()) throw new InterruptedException("cancelled");
        if (!Arrays.equals(expectedBefore, io.read())) throw new IOException("写入前文件再次变化，已取消写入");
        try {
            io.write(after);
            if (!Arrays.equals(after, io.read())) throw new IOException("写入后校验失败");
            if (!cancellation.isActive()) throw new InterruptedException("cancelled");
            if (commit != null) commit.run();
        } catch (Throwable error) {
            boolean restored = false;
            try {
                io.write(expectedBefore);
                restored = Arrays.equals(expectedBefore, io.read());
            } catch (Throwable restoreError) {
                error.addSuppressed(restoreError);
            }
            throw new Failure(restored, error);
        }
    }
}
