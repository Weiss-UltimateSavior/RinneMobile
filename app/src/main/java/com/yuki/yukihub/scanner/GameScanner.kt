package com.yuki.yukihub.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yuki.yukihub.data.GameRepository
import com.yuki.yukihub.model.EngineType
import java.util.Locale

object GameScanner {
    private const val TAG = "GameScanner"

    /** Traverse every descendant directory regardless of depth. */
    const val SCAN_ALL_LEVELS = -1

    /** Traverse descendants until a directory is identified as a game, then stop below it. */
    const val SCAN_UNTIL_GAME_MATCH = -2

    @JvmStatic
    fun scan(context: Context, rootUri: Uri): List<ScanResult> = scan(context, rootUri, 2)

    @JvmStatic
    fun scan(context: Context, rootUri: Uri, maxDepth: Int): List<ScanResult> =
        scan(context, rootUri, ScanRequest.defaults(maxDepth)).results

    /** Runs a bounded scan and returns both partial results and stop/error information. */
    @JvmStatic
    fun scan(context: Context, rootUri: Uri, request: ScanRequest): ScanReport {
        val report = ScanReport()
        val seenUris = HashSet<String>()
        // request.maxDepth 在 try 块外解引用：保持参数非空契约，Java 调用方传 null 将由 Kotlin 运行时抛 NPE。
        val requestedDepth = request.maxDepth
        val unbounded = requestedDepth == SCAN_ALL_LEVELS || requestedDepth == SCAN_UNTIL_GAME_MATCH
        // Only the explicit all-levels mode traverses inside an already identified game.
        // Fixed-depth scans are bounded searches, while match mode is their unbounded,
        // prune-on-match counterpart.
        val traverseMatchedGames = shouldTraverseMatchedGames(requestedDepth)
        val depth = if (unbounded) Int.MAX_VALUE else maxOf(1, minOf(4, requestedDepth))

        val root: DocumentFile? = try {
            DocumentFile.fromTreeUri(context, rootUri)
        } catch (t: Throwable) {
            Log.w(TAG, "fromTreeUri failed uri=$rootUri", t)
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT)
            report.addError("无法访问扫描目录：$rootUri")
            return report
        }
        if (root == null) {
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT)
            report.addError("扫描目录不存在：$rootUri")
            return report
        }
        try {
            if (!root.isDirectory) {
                report.setStopReason(ScanReport.StopReason.INVALID_ROOT)
                report.addError("扫描目标不是目录：$rootUri")
                return report
            }
        } catch (t: Throwable) {
            Log.w(TAG, "root isDirectory failed uri=$rootUri", t)
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT)
            report.addError("无法读取扫描目录：$rootUri")
            return report
        }
        // A user may select one game directory itself rather than its parent. Probe the
        // root before traversing children so that directory-oriented engine roots are
        // not skipped merely because they have no game-directory child.
        val rootGameMatched = detectGameDirectory(root, report, seenUris, request)
        if (!rootGameMatched || traverseMatchedGames) {
            scanChildren(root, 1, depth, traverseMatchedGames, report, seenUris, request)
        }
        return report
    }

    private fun detectGameDirectory(
        dir: DocumentFile?,
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        request: ScanRequest
    ): Boolean {
        if (dir == null || report == null || !report.tryVisit(request, safeUri(dir))) return false
        try {
            val detected = EngineDetector.detect(dir, 2)
            if (detected.confidence <= 0 || !isRootDirectoryEngine(detected.engine)) return false
            val uri = dir.uri.toString()
            if (markSeen(seenUris, uri)) {
                report.addResult(
                    ScanResult(
                        title = safeName(dir),
                        uri = uri,
                        engine = detected.engine,
                        confidence = detected.confidence,
                        launchTarget = detected.launchTarget,
                        coverUri = "",
                        xp3Candidates = detected.xp3Candidates,
                        rpgMakerSubtype = detected.rpgMakerSubtype,
                        renpySubtype = detected.renpySubtype,
                        godotSubtype = detected.godotSubtype
                    )
                )
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "root engine detection failed uri=${safeUri(dir)}", t)
            report.addError("识别扫描目录失败：${safeUri(dir)}")
            return false
        }
    }

    /** File-oriented engines are emitted per entry file; directory-oriented engines may use the selected root. */
    private fun isRootDirectoryEngine(engine: EngineType): Boolean = when (engine) {
        EngineType.KIRIKIRI,
        EngineType.ONS,
        EngineType.TYRANO,
        EngineType.ARTEMIS,
        EngineType.RPGMAKER,
        EngineType.RENPY,
        EngineType.GODOT -> true
        else -> false
    }

    internal fun shouldTraverseMatchedGames(requestedDepth: Int): Boolean {
        return requestedDepth == SCAN_ALL_LEVELS
    }

    private fun scanChildren(
        dir: DocumentFile?,
        level: Int,
        maxDepth: Int,
        traverseMatchedGames: Boolean,
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        request: ScanRequest
    ) {
        if (dir == null || report == null || report.shouldStop(request)) return
        val children: Array<DocumentFile>?
        try {
            children = dir.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "listFiles failed uri=${safeUri(dir)}", t)
            report.addError("无法读取目录：${safeUri(dir)}")
            return
        }
        if (children == null) return

        for (child in children) {
            if (child == null) continue
            if (!report.tryVisit(request, safeUri(child))) return
            try {
                if (child.isFile) {
                    val name = safeName(child)
                    val lowerName = name.lowercase(Locale.ROOT)
                    // 情况1：单个PSP文件在根目录
                    if (lowerName.endsWith(".iso") || lowerName.endsWith(".cso") || lowerName.endsWith(".chd") ||
                        lowerName.endsWith(".elf") || lowerName.endsWith(".pbp")
                    ) {
                        addPspFileResult(report, seenUris, child, name)
                        continue
                    }
                    // 情况1：单个 Nintendo 3DS 文件在根目录
                    if (isN3dsFile(lowerName)) {
                        addN3dsFileResult(report, seenUris, child, name)
                        continue
                    }
                    if (lowerName.endsWith(".desktop")) {
                        addDesktopResult(report, seenUris, stripDesktopSuffix(name), child.uri.toString(), name, "")
                    }
                    continue
                }
                if (!child.isDirectory) continue

                // 识别目录本身的 PSP / 3DS / desktop 入口；是否继续遍历由扫描模式决定。
                // 只有全层模式会穿透已命中的游戏目录；固定深度和命中模式都会在此剪枝。
                val childFiles = child.listFiles() ?: emptyArray<DocumentFile>()
                val pspDirectory = tryAddPspDirectory(child, childFiles, report, seenUris)
                val n3dsDirectory = tryAddN3dsDirectory(child, childFiles, report, seenUris)
                val desktopDirectory = tryAddDesktopDirectory(child, childFiles, report, seenUris)

                val childName = safeName(child).lowercase(Locale.ROOT)
                val internalAssetDir = isInternalAssetDir(childName)

                var gameDirectoryMatched = pspDirectory || n3dsDirectory || desktopDirectory
                if (!internalAssetDir && !gameDirectoryMatched) {
                    val detected = EngineDetector.detect(child, 2, childFiles)
                    if (detected.confidence > 0) {
                        val uri = child.uri.toString()
                        if (markSeen(seenUris, uri)) {
                            report.addResult(
                                ScanResult(
                                    title = safeName(child),
                                    uri = uri,
                                    engine = detected.engine,
                                    confidence = detected.confidence,
                                    launchTarget = detected.launchTarget,
                                    coverUri = "",
                                    xp3Candidates = detected.xp3Candidates,
                                    rpgMakerSubtype = detected.rpgMakerSubtype,
                                    renpySubtype = detected.renpySubtype,
                                    godotSubtype = detected.godotSubtype
                                )
                            )
                        }
                        gameDirectoryMatched = true
                    }
                }

                if (level < maxDepth && (!gameDirectoryMatched || traverseMatchedGames)) {
                    scanChildren(
                        child, level + 1, maxDepth, traverseMatchedGames,
                        report, seenUris, request
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "scan child failed uri=${safeUri(child)}", t)
                report.addError("扫描项目失败：${safeUri(child)}")
            }
        }
    }

    private fun tryAddDesktopDirectory(
        dir: DocumentFile?,
        files: Array<DocumentFile>?,
        report: ScanReport?,
        seenUris: MutableSet<String>?
    ): Boolean {
        if (dir == null || report == null) return false
        try {
            if (files == null || files.isEmpty()) return false

            val desktops = mutableListOf<DocumentFile>()
            for (f in files) {
                if (f == null || !f.isFile) continue
                val name = safeName(f).lowercase(Locale.ROOT)
                if (name.endsWith(".desktop")) desktops.add(f)
            }
            if (desktops.isEmpty()) return false

            var coverUri = ""
            val folderCover = findBestImage(files)
            if (folderCover != null) coverUri = folderCover.uri.toString()

            if (desktops.size == 1) {
                // 情况2：文件夹内只有一个 desktop，标题取文件夹名，但入口仍然是 .desktop 文件本身。
                val desktop = desktops[0]
                return addDesktopResult(
                    report, seenUris, safeName(dir),
                    desktop.uri.toString(), safeName(desktop), coverUri
                )
            }

            // 情况3：文件夹里有多个 desktop，按多个单独条目识别。
            var added = false
            for (desktop in desktops) {
                val name = safeName(desktop)
                added = added or addDesktopResult(
                    report, seenUris, stripDesktopSuffix(name),
                    desktop.uri.toString(), name, coverUri
                )
            }
            return added
        } catch (t: Throwable) {
            Log.w(TAG, "tryAddDesktopDirectory failed uri=${safeUri(dir)}", t)
            return false
        }
    }

    private fun addDesktopResult(
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        title: String,
        resultUri: String,
        launchTarget: String,
        coverUri: String
    ): Boolean {
        if (report == null || !markSeen(seenUris, resultUri)) return false
        report.addResult(
            ScanResult(
                title = if (title.trim().isEmpty()) "未命名游戏" else title,
                uri = resultUri,
                engine = EngineType.WINLATOR,
                confidence = 90,
                launchTarget = launchTarget,
                coverUri = coverUri
            )
        )
        return true
    }

    private fun addPspFileResult(
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        pspFile: DocumentFile?,
        fileName: String
    ): Boolean {
        if (report == null || pspFile == null) return false
        val uri = pspFile.uri.toString()
        if (!markSeen(seenUris, uri)) return false

        // 从文件名中提取游戏标题（去掉扩展名）
        var title = fileName
        val dotIndex = title.lastIndexOf('.')
        if (dotIndex > 0) {
            title = title.substring(0, dotIndex)
        }

        report.addResult(
            ScanResult(
                title = if (title.trim().isEmpty()) "未命名PSP游戏" else title,
                uri = uri,
                engine = EngineType.PSP,
                confidence = 95,
                launchTarget = fileName, // launchTarget设置为文件名
                coverUri = ""
            )
        )
        return true
    }

    /**
     * 尝试添加文件夹里的PSP游戏文件
     * 情况2：文件夹里只有1个PSP文件，游戏名取文件夹名，但入口仍然是PSP文件本身
     * 情况3：文件夹里有多个PSP文件，按多个单独条目识别
     */
    private fun tryAddPspDirectory(
        dir: DocumentFile?,
        files: Array<DocumentFile>?,
        report: ScanReport?,
        seenUris: MutableSet<String>?
    ): Boolean {
        if (dir == null || report == null) return false
        try {
            if (files == null || files.isEmpty()) return false

            val pspFiles = mutableListOf<DocumentFile>()
            for (f in files) {
                if (f == null || !f.isFile) continue
                val name = safeName(f).lowercase(Locale.ROOT)
                if (name.endsWith(".iso") || name.endsWith(".cso") || name.endsWith(".chd") ||
                    name.endsWith(".elf") || name.endsWith(".pbp")
                ) {
                    pspFiles.add(f)
                }
            }
            if (pspFiles.isEmpty()) return false

            var coverUri = ""
            val folderCover = findBestImage(files)
            if (folderCover != null) coverUri = folderCover.uri.toString()

            if (pspFiles.size == 1) {
                // 情况2：文件夹内只有一个 PSP文件，标题取文件夹名，但入口仍然是 PSP文件本身。
                val pspFile = pspFiles[0]
                return addPspFileResultWithCover(
                    report, seenUris, safeName(dir),
                    pspFile.uri.toString(), safeName(pspFile), coverUri
                )
            }

            // 情况3：文件夹里有多个 PSP文件，按多个单独条目识别。
            var added = false
            for (pspFile in pspFiles) {
                val name = safeName(pspFile)
                var title = name
                val dotIndex = title.lastIndexOf('.')
                if (dotIndex > 0) {
                    title = title.substring(0, dotIndex)
                }
                added = added or addPspFileResultWithCover(
                    report, seenUris, title,
                    pspFile.uri.toString(), name, coverUri
                )
            }
            return added
        } catch (t: Throwable) {
            Log.w(TAG, "tryAddPspDirectory failed uri=${safeUri(dir)}", t)
            return false
        }
    }

    private fun addPspFileResultWithCover(
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        title: String?,
        resultUri: String?,
        launchTarget: String,
        coverUri: String
    ): Boolean {
        if (report == null || resultUri == null || !markSeen(seenUris, resultUri)) return false
        report.addResult(
            ScanResult(
                title = if (title == null || title.trim().isEmpty()) "未命名PSP游戏" else title,
                uri = resultUri,
                engine = EngineType.PSP,
                confidence = 95,
                launchTarget = launchTarget,
                coverUri = coverUri
            )
        )
        return true
    }

    private fun isN3dsFile(lowerName: String?): Boolean {
        if (lowerName == null) return false
        return lowerName.endsWith(".3ds") || lowerName.endsWith(".cci") || lowerName.endsWith(".zcci") ||
            lowerName.endsWith(".cxi") || lowerName.endsWith(".zcxi") || lowerName.endsWith(".cia") ||
            lowerName.endsWith(".zcia") || lowerName.endsWith(".3dsx") || lowerName.endsWith(".z3dsx")
    }

    private fun addN3dsFileResult(
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        n3dsFile: DocumentFile?,
        fileName: String
    ): Boolean {
        if (report == null || n3dsFile == null) return false
        val uri = n3dsFile.uri.toString()
        if (!markSeen(seenUris, uri)) return false

        // 从文件名中提取游戏标题（去掉扩展名）
        var title = fileName
        val dotIndex = title.lastIndexOf('.')
        if (dotIndex > 0) {
            title = title.substring(0, dotIndex)
        }

        report.addResult(
            ScanResult(
                title = if (title.trim().isEmpty()) "未命名3DS游戏" else title,
                uri = uri,
                engine = EngineType.NINTENDO_3DS,
                confidence = 95,
                launchTarget = fileName,
                coverUri = ""
            )
        )
        return true
    }

    /**
     * 尝试添加文件夹里的 Nintendo 3DS 游戏文件
     * 情况2：文件夹里只有1个3DS文件，游戏名取文件夹名，但入口仍然是3DS文件本身
     * 情况3：文件夹里有多个3DS文件，按多个单独条目识别
     */
    private fun tryAddN3dsDirectory(
        dir: DocumentFile?,
        files: Array<DocumentFile>?,
        report: ScanReport?,
        seenUris: MutableSet<String>?
    ): Boolean {
        if (dir == null || report == null) return false
        try {
            if (files == null || files.isEmpty()) return false

            val n3dsFiles = mutableListOf<DocumentFile>()
            for (f in files) {
                if (f == null || !f.isFile) continue
                val name = safeName(f).lowercase(Locale.ROOT)
                if (isN3dsFile(name)) {
                    n3dsFiles.add(f)
                }
            }
            if (n3dsFiles.isEmpty()) return false

            var coverUri = ""
            val folderCover = findBestImage(files)
            if (folderCover != null) coverUri = folderCover.uri.toString()

            if (n3dsFiles.size == 1) {
                // 情况2：文件夹内只有一个 3DS 文件，标题取文件夹名，但入口仍然是 3DS 文件本身。
                val n3dsFile = n3dsFiles[0]
                return addN3dsFileResultWithCover(
                    report, seenUris, safeName(dir),
                    n3dsFile.uri.toString(), safeName(n3dsFile), coverUri
                )
            }

            // 情况3：文件夹里有多个 3DS 文件，按多个单独条目识别。
            var added = false
            for (n3dsFile in n3dsFiles) {
                val name = safeName(n3dsFile)
                var title = name
                val dotIndex = title.lastIndexOf('.')
                if (dotIndex > 0) {
                    title = title.substring(0, dotIndex)
                }
                added = added or addN3dsFileResultWithCover(
                    report, seenUris, title,
                    n3dsFile.uri.toString(), name, coverUri
                )
            }
            return added
        } catch (t: Throwable) {
            Log.w(TAG, "tryAddN3dsDirectory failed uri=${safeUri(dir)}", t)
            return false
        }
    }

    private fun addN3dsFileResultWithCover(
        report: ScanReport?,
        seenUris: MutableSet<String>?,
        title: String?,
        resultUri: String?,
        launchTarget: String,
        coverUri: String
    ): Boolean {
        if (report == null || resultUri == null || !markSeen(seenUris, resultUri)) return false
        report.addResult(
            ScanResult(
                title = if (title == null || title.trim().isEmpty()) "未命名3DS游戏" else title,
                uri = resultUri,
                engine = EngineType.NINTENDO_3DS,
                confidence = 95,
                launchTarget = launchTarget,
                coverUri = coverUri
            )
        )
        return true
    }

    private fun findBestImage(files: Array<DocumentFile>?): DocumentFile? {
        try {
            if (files == null) return null
            var best: DocumentFile? = null
            var bestScore = Int.MIN_VALUE
            for (f in files) {
                if (f == null || !f.isFile) continue
                val name = safeName(f)
                if (!isImageFile(name)) continue
                val score = coverNameScore(name)
                if (best == null || score > bestScore) {
                    best = f
                    bestScore = score
                }
            }
            return best
        } catch (ignored: Throwable) {
            return null
        }
    }

    private fun isImageFile(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    private fun coverNameScore(name: String?): Int {
        if (name == null) return 0
        val lower = name.lowercase(Locale.ROOT)
        if (lower == "cover.jpg" || lower == "cover.png" || lower == "cover.webp") return 100
        if (lower == "folder.jpg" || lower == "folder.png" || lower == "folder.webp") return 95
        if (lower.contains("cover") || lower.contains("folder") || lower.contains("封面")) return 80
        if (lower.contains("poster") || lower.contains("package") || lower.contains("main")) return 60
        return 10
    }

    private fun isInternalAssetDir(name: String?): Boolean {
        if (name == null) return false
        return name == "data" || name == "tyrano" || name == "resources" || name == "arc" ||
            name == "scenario" || name == "system" || name == "bgimage" || name == "fgimage" ||
            name == "image" || name == "sound" || name == "bgm" || name == "voice" || name == "video" ||
            name == "movie" || name == "font" || name == "others" || name == "app"
    }

    private fun safeName(file: DocumentFile?): String {
        return try {
            val name = file?.name
            if (name == null || name.trim().isEmpty()) "未命名游戏" else name
        } catch (t: Throwable) {
            Log.w(TAG, "safeName failed uri=${safeUri(file)}", t)
            "未命名游戏"
        }
    }

    private fun markSeen(seenUris: MutableSet<String>?, uri: String): Boolean {
        if (seenUris == null) return true
        val key = GameRepository.normalizeRootUriKey(uri)
        if (key.isEmpty()) return true
        return seenUris.add(key)
    }

    private fun stripDesktopSuffix(name: String?): String {
        if (name == null) return "未命名游戏"
        return if (name.lowercase(Locale.ROOT).endsWith(".desktop")) name.substring(0, maxOf(0, name.length - 8)) else name
    }

    private fun safeUri(file: DocumentFile?): String {
        return try {
            file?.uri?.toString() ?: "null"
        } catch (ignored: Throwable) {
            "unknown"
        }
    }
}
