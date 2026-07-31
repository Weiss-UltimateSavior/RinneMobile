package com.core.scanner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Immutable directory metadata obtained in one provider query.
 *
 * Unlike DocumentFile, reading these properties never performs another ContentResolver query.
 */
internal data class ScanNode(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val documentId: String,
    val localFile: File? = null
)

/**
 * Lists every directory at most once. Local ExternalStorageProvider trees use java.io.File;
 * other providers use one batched DocumentsContract query for id/name/MIME.
 */
internal class ScanDirectoryReader(
    private val context: Context,
    private val treeUri: Uri,
    private val request: ScanRequest
) {
    private val resolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, List<ScanNode>>()

    fun root(): ScanNode? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val local = resolveLocalRoot(treeUri, documentId)
        val name = local?.name?.takeIf { it.isNotBlank() }
            ?: documentId.substringAfterLast('/').substringAfterLast(':').ifBlank { "未命名游戏" }
        return ScanNode(documentUri, name, true, documentId, local)
    }

    fun listChildren(directory: ScanNode): List<ScanNode> {
        return cache.getOrPut(directory.uri.toString()) {
            if (directory.localFile != null) {
                listLocal(directory)
            } else {
                listSaf(directory)
            }
        }
    }

    private fun listLocal(directory: ScanNode): List<ScanNode> {
        if (request.isCancelled || request.isDeadlineReached) return emptyList()
        val files = directory.localFile?.listFiles() ?: return emptyList()
        val nodes = ArrayList<ScanNode>(files.size)
        for (file in files) {
            if (request.isCancelled || request.isDeadlineReached) break
            val documentId = "${directory.documentId}/${file.name}"
            nodes.add(
                ScanNode(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    name = file.name,
                    isDirectory = file.isDirectory,
                    documentId = documentId,
                    localFile = file
                )
            )
        }
        request.markProgress()
        return nodes
    }

    private fun listSaf(directory: ScanNode): List<ScanNode> {
        acquireSafPermit()
        val signal = request.createCancellationSignal()
        val timeout: ScheduledFuture<*> = QUERY_WATCHDOG.scheduleAtFixedRate({
            if (!request.isCancelled && request.isDeadlineReached) {
                request.markDeadlineReached()
            }
        }, 1L, 1L, TimeUnit.SECONDS)
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, directory.documentId
            )
            val nodes = ArrayList<ScanNode>()
            resolver.query(
                childrenUri,
                PROJECTION,
                null,
                null,
                null,
                signal
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (request.isCancelled || request.isDeadlineReached) break
                    val documentId = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex)
                    nodes.add(
                        ScanNode(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name = name,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            documentId = documentId
                        )
                    )
                }
            }
            request.markProgress()
            return nodes
        } catch (cancelled: OperationCanceledException) {
            if (!request.isCancelled) request.markDeadlineReached()
            return emptyList()
        } finally {
            timeout.cancel(false)
            request.releaseCancellationSignal(signal)
            SAF_QUERY_PERMITS.release()
        }
    }

    private fun acquireSafPermit() {
        while (!request.isCancelled && !request.isDeadlineReached) {
            if (SAF_QUERY_PERMITS.tryAcquire(200, TimeUnit.MILLISECONDS)) return
        }
        throw OperationCanceledException()
    }

    private fun resolveLocalRoot(uri: Uri, documentId: String): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return null
        }
        val separator = documentId.indexOf(':')
        val volume = if (separator >= 0) documentId.substring(0, separator) else documentId
        val relative = if (separator >= 0) documentId.substring(separator + 1) else ""
        // ExternalStorageProvider's synthetic "home:" root maps to Documents, not /storage/home.
        if (volume.equals("home", ignoreCase = true) || volume.equals("raw", ignoreCase = true)) {
            return null
        }
        val base = if (volume.equals("primary", ignoreCase = true)) {
            File("/storage/emulated/0")
        } else {
            File("/storage", volume)
        }
        val root = if (relative.isBlank()) base else File(base, relative)
        return root.takeIf { it.isDirectory && it.canRead() }
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private val SAF_QUERY_PERMITS = Semaphore(2, true)
        private val QUERY_WATCHDOG = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "game-scan-watchdog").apply { isDaemon = true }
        }
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
    }
}
