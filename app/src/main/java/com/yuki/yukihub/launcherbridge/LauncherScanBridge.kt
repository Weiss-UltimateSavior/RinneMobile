package com.yuki.yukihub.launcherbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.model.EngineType
import com.yuki.yukihub.model.Game
import com.yuki.yukihub.scanner.EngineDetector
import com.yuki.yukihub.scanner.GameScanner
import com.yuki.yukihub.scanner.ScanReport
import com.yuki.yukihub.scanner.ScanRequest
import com.yuki.yukihub.scanner.ScanResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * 扫描桥接：引擎检测、目录扫描、游戏导入。
 */
object LauncherScanBridge {

    /** 扫描深度常量：扫描所有层级。对应 GameScanner.SCAN_ALL_LEVELS。 */
    const val SCAN_ALL_LEVELS = -1

    /** 扫描深度常量：扫描直到首次游戏匹配。对应 GameScanner.SCAN_UNTIL_GAME_MATCH。 */
    const val SCAN_UNTIL_GAME_MATCH = -2

    /**
     * 通过探测目录文件特征来检测游戏引擎。
     * 失败时返回 UNKNOWN 引擎和 0 置信度的 DetectionResult。
     */
    @JvmStatic
    fun detectEngine(dir: DocumentFile?, featureDepth: Int): DetectionResult {
        val out = DetectionResult()
        if (dir == null) return out
        try {
            val source = EngineDetector.detect(dir, featureDepth) ?: return out
            out.engine = source.engine ?: EngineType.UNKNOWN
            out.confidence = source.confidence
            out.launchTarget = source.launchTarget ?: ""
            out.rpgMakerSubtype = source.rpgMakerSubtype ?: ""
            out.renpySubtype = source.renpySubtype ?: ""
            out.godotSubtype = source.godotSubtype ?: ""
        } catch (_: Throwable) {
        }
        return out
    }

    /** 等价于 EngineDetector.Result 的值类，无需导入 scanner 层。 */
    class DetectionResult {
        @JvmField var engine: EngineType = EngineType.UNKNOWN
        @JvmField var confidence: Int = 0
        @JvmField var launchTarget: String = ""

        /**
         * 仅当 engine == RPGMAKER 时有意义。取值：
         * "rpgmxp" / "rpgmvx" / "rpgmvxace" / "mkxp-z"。空串表示需用户自行决定。
         */
        @JvmField var rpgMakerSubtype: String = ""

        /**
         * 仅当 engine == RENPY 时有意义。取值："renpy" 或 "renpy8"。
         */
        @JvmField var renpySubtype: String = ""

        /**
         * 仅当 engine == GODOT 时有意义。取值："godot4"。
         */
        @JvmField var godotSubtype: String = ""
    }

    @JvmStatic
    fun scanAndImport(context: Context?, roots: List<String>?, depth: Int): ImportStats =
        scanAndImport(context, roots, ScanRequest.defaults(depth)).importStats

    /** 受控变体；调用方可在导入前后检查部分发现结果。 */
    @JvmStatic
    fun scanAndImport(context: Context?, roots: List<String>?, request: ScanRequest?): ScanAndImportResult {
        val scan = scanWithReport(context, roots, request)
        val stats = ImportStats()
        stats.scanStopReason = scan.stopReason
        stats.partialDiscovery = scan.isPartial
        for (error in scan.errors) {
            stats.failed++
            stats.failedItems.add(error)
        }
        // 部分发现结果绝不能被静默持久化。UI 调用方必须明确确认
        // 返回的 ScanBatchResult，然后调用 importScanResults(results)。
        if (scan.isPartial) {
            stats.failed++
            stats.failedItems.add("扫描未完整结束，未自动导入；请确认后导入已发现结果。")
            return ScanAndImportResult(scan, stats)
        }
        if (context != null) importScannedGames(context.applicationContext, GameRepository(context.applicationContext), scan.results, stats)
        return ScanAndImportResult(scan, stats)
    }

    /** 仅执行发现。调用方可在导入前解析 [ScanResult.xp3Candidates]。 */
    @JvmStatic
    fun scan(context: Context?, roots: List<String>?, depth: Int): List<ScanResult> =
        scanWithReport(context, roots, ScanRequest.defaults(depth)).results

    /**
     * 跨多个根目录执行有界发现。当后续根目录失败或请求被停止时，
     * 已完成的根目录结果被保留，允许调用方提供部分导入。
     */
    @JvmStatic
    fun scanWithReport(context: Context?, roots: List<String>?, request: ScanRequest?): ScanBatchResult {
        val batch = ScanBatchResult()
        if (context == null || roots.isNullOrEmpty()) return batch
        val appContext = context.applicationContext
        val safeRequest = request ?: ScanRequest.defaults(2)
        val initialVisitedNodes = safeRequest.visitedNodes
        for (root in roots) {
            if (root.isBlank()) continue
            if (safeRequest.isCancelled) {
                batch.stopReason = ScanReport.StopReason.CANCELLED
                break
            }
            if (safeRequest.isDeadlineReached) {
                batch.stopReason = ScanReport.StopReason.DEADLINE
                break
            }
            if (safeRequest.isNodeLimitReached) {
                batch.stopReason = ScanReport.StopReason.NODE_LIMIT
                break
            }
            try {
                val report = GameScanner.scan(appContext, Uri.parse(root), safeRequest)
                batch.resultsInternal.addAll(report.results)
                batch.errorsInternal.addAll(report.errors)
                val reason = report.stopReason
                // 无效的 SAF 根目录仅影响该根目录。继续扫描其他已配置的根目录；
                // 取消和共享资源限制则停止整个批次。
                if (reason.stopsBatch()) {
                    batch.stopReason = report.stopReason
                    break
                }
            } catch (_: SecurityException) {
                batch.errorsInternal.add("目录权限已失效，请重新添加：${simplifyUri(root)}")
            } catch (_: Throwable) {
                batch.errorsInternal.add("扫描目录失败：${simplifyUri(root)}")
            }
        }
        batch.visitedNodes = maxOf(0, safeRequest.visitedNodes - initialVisitedNodes)
        return batch
    }

    /** 在任何交互式启动目标选择完成后导入结果。 */
    @JvmStatic
    fun importScanResults(context: Context?, results: List<ScanResult>?): ImportStats {
        val stats = ImportStats()
        if (context == null) return stats
        importScannedGames(context.applicationContext, GameRepository(context.applicationContext), results, stats)
        return stats
    }

    private fun simplifyUri(uri: String?): String {
        if (uri == null) return ""
        return try {
            val parsed = Uri.parse(uri)
            parsed.lastPathSegment ?: uri
        } catch (_: Throwable) {
            uri
        }
    }

    private fun importScannedGames(context: Context, repository: GameRepository?, results: List<ScanResult>?, stats: ImportStats) {
        stats.scanned = results?.size ?: 0
        if (repository == null || results.isNullOrEmpty()) return
        val existing = repository.getRootUriKeySet().toMutableSet()
        for (result in results) {
            if (result.uri.isNullOrBlank()) {
                stats.failed++
                stats.failedItems.add("无法读取路径的扫描结果")
                continue
            }
            val rootKey = GameRepository.normalizeRootUriKey(result.uri)
            if (existing.contains(rootKey)) {
                stats.skipped++
                stats.skippedItems.add(emptyText(result.title, result.uri))
                continue
            }
            val game = Game()
            game.title = result.title
            game.rootUri = result.uri
            game.engine = result.engine
            game.launchTarget = if (result.launchTarget.isNullOrBlank())
                defaultLaunchTargetForEngine(result.engine)
            else
                result.launchTarget
            game.emulatorPackage = emulatorPackageForResult(result)
            val restored = repository.findScannedMatch(game)
            if (restored != null) {
                if (rootKey != GameRepository.normalizeRootUriKey(restored.rootUri)) {
                    repository.bindScannedLocation(restored, game)
                }
                existing.add(rootKey)
                stats.skipped++
                stats.skippedItems.add(emptyText(result.title, result.uri))
                continue
            }
            val id = repository.insertIfNotExists(game)
            if (id > 0) {
                existing.add(rootKey)
                stats.added++
                stats.addedItems.add(emptyText(result.title, result.uri))
                game.id = id
                val cover = resolveLocalCover(context, result)
                if (cover != null) {
                    game.coverUri = cover
                    game.coverPersistUri = cover
                    game.coverSourceType = 1
                    try { repository.update(game) } catch (_: Throwable) {}
                } else {
                    LauncherCoverBridge.fetchCoverForGameAsync(context, game)
                }
            } else {
                stats.failed++
                stats.failedItems.add(emptyText(result.title, result.uri))
            }
        }
    }

    private fun resolveLocalCover(context: Context, result: ScanResult): String? {
        if (!result.coverUri.isNullOrBlank()) {
            val cover = copyCoverToInternalStorage(context, result.coverUri)
            if (cover != null) return cover
        }
        val dirImage = findFirstImageInDir(context, result.uri)
        if (dirImage != null) {
            return copyCoverToInternalStorage(context, dirImage)
        }
        return null
    }

    private fun findFirstImageInDir(context: Context, dirUri: String?): String? {
        if (dirUri.isNullOrBlank()) return null
        try {
            val tree = Uri.parse(dirUri)
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(1)
                    if (mime != null && mime.startsWith("image/")) {
                        val docId = cursor.getString(0)
                        return DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    @JvmStatic
    fun copyCoverToInternalStorage(context: Context, sourceUriStr: String?): String? {
        if (sourceUriStr.isNullOrBlank()) return null
        var inputStream: InputStream? = null
        try {
            val source = Uri.parse(sourceUriStr)
            inputStream = context.contentResolver.openInputStream(source) ?: return null
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()
            inputStream = context.contentResolver.openInputStream(source) ?: return null
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1440) sampleSize *= 2
            val decodeOpts = BitmapFactory.Options()
            decodeOpts.inSampleSize = sampleSize
            var bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOpts)
            inputStream.close()
            inputStream = null
            if (bitmap == null) return null
            val maxPx = 720
            val scale = minOf(1f, maxPx.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f) {
                val nw = Math.round(bitmap.width * scale)
                val nh = Math.round(bitmap.height * scale)
                val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                bitmap.recycle()
                bitmap = scaled
            }
            val dir = File(context.filesDir, "covers")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "cover_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
            bitmap.recycle()
            return Uri.fromFile(out).toString()
        } catch (_: Throwable) {
            return null
        } finally {
            inputStream?.let { try { it.close() } catch (_: Throwable) {} }
        }
    }

    private fun emulatorPackageForEngine(engine: EngineType?): String = when (engine) {
        EngineType.KIRIKIRI -> "internal.krkr"
        EngineType.ONS -> "internal.ons"
        EngineType.TYRANO -> "internal.tyrano"
        EngineType.ARTEMIS -> "internal.artemis"
        EngineType.PSP -> "org.ppsspp.ppsspp"
        EngineType.NINTENDO_3DS -> "io.github.azaharplus.android"
        // 没有子类型的旧版/未来扫描结果保留保守的 RPG XP 回退。
        EngineType.RPGMAKER -> "internal.rpgmxp"
        EngineType.RENPY -> "internal.renpy"
        EngineType.GODOT -> "internal.godot"
        else -> ""
    }

    @JvmStatic
    fun emulatorPackageForResult(result: ScanResult?): String {
        if (result == null) return ""
        if (result.engine == EngineType.RPGMAKER) {
            val subtype = normalizeSubtype(result.rpgMakerSubtype)
            if (subtype == "rpgmxp" || subtype == "rpgmvx"
                || subtype == "rpgmvxace" || subtype == "mkxp-z"
            ) {
                return "internal.$subtype"
            }
        } else if (result.engine == EngineType.RENPY) {
            val subtype = normalizeSubtype(result.renpySubtype)
            if (subtype == "renpy" || subtype == "renpy8") {
                return "internal.$subtype"
            }
        } else if (result.engine == EngineType.GODOT) {
            val subtype = normalizeSubtype(result.godotSubtype)
            if (subtype == "godot" || subtype == "godot4") {
                return "internal.$subtype"
            }
        }
        return emulatorPackageForEngine(result.engine)
    }

    private fun normalizeSubtype(subtype: String?): String =
        subtype?.trim()?.lowercase(Locale.ROOT) ?: ""

    private fun defaultLaunchTargetForEngine(engine: EngineType?): String = "[游戏目录]"

    private fun emptyText(value: String?, fallback: String?): String =
        if (value.isNullOrBlank()) fallback ?: "" else value.trim()

    class ImportStats {
        @JvmField var scanned: Int = 0
        @JvmField var added: Int = 0
        @JvmField var skipped: Int = 0
        @JvmField var failed: Int = 0
        @JvmField val addedItems: MutableList<String> = ArrayList()
        @JvmField val skippedItems: MutableList<String> = ArrayList()
        @JvmField val failedItems: MutableList<String> = ArrayList()

        /** 旧版 scanAndImport 调用方的诊断信息。 */
        @JvmField var scanStopReason: ScanReport.StopReason = ScanReport.StopReason.COMPLETED
        @JvmField var partialDiscovery: Boolean = false
    }

    /** 多根目录扫描的聚合结果。即使部分完成也可导入结果。 */
    class ScanBatchResult {
        internal val resultsInternal: MutableList<ScanResult> = ArrayList()
        internal val errorsInternal: MutableList<String> = ArrayList()
        internal var visitedNodes: Int = 0
        internal var stopReason: ScanReport.StopReason = ScanReport.StopReason.COMPLETED

        val results: List<ScanResult> get() = ArrayList(resultsInternal)
        val errors: List<String> get() = ArrayList(errorsInternal)
        fun getVisitedNodes(): Int = visitedNodes
        fun getStopReason(): ScanReport.StopReason = stopReason
        val isPartial: Boolean get() = stopReason != ScanReport.StopReason.COMPLETED
    }

    class ScanAndImportResult(
        @JvmField val scan: ScanBatchResult,
        @JvmField val importStats: ImportStats
    )
}
