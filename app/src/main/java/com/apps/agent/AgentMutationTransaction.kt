package com.apps.agent

import java.io.IOException
import java.util.Arrays

/** Provider-neutral write/verify/rollback core so destructive failure semantics are unit-testable. */
internal object AgentMutationTransaction {
    @JvmStatic
    @Throws(Exception::class)
    fun replace(io: DocumentIo, expectedBefore: ByteArray, after: ByteArray,
                cancellation: Cancellation, commit: Commit?) {
        if (!cancellation.isActive()) throw InterruptedException("cancelled")
        if (!Arrays.equals(expectedBefore, io.read())) throw IOException("写入前文件再次变化，已取消写入")
        try {
            io.write(after)
            if (!Arrays.equals(after, io.read())) throw IOException("写入后校验失败")
            if (!cancellation.isActive()) throw InterruptedException("cancelled")
            commit?.run()
        } catch (error: Throwable) {
            var restored = false
            try {
                io.write(expectedBefore)
                restored = Arrays.equals(expectedBefore, io.read())
            } catch (restoreError: Throwable) {
                error.addSuppressed(restoreError)
            }
            throw Failure(restored, error)
        }
    }

    interface DocumentIo {
        @Throws(Exception::class)
        fun read(): ByteArray
        @Throws(Exception::class)
        fun write(value: ByteArray)
    }

    fun interface Cancellation {
        fun isActive(): Boolean
    }

    fun interface Commit {
        @Throws(Exception::class)
        fun run()
    }

    class Failure(
        @JvmField val restored: Boolean,
        cause: Throwable
    ) : IOException(
        if (restored) "写入失败，已恢复原文件" else "写入和自动恢复均失败",
        cause
    )
}
