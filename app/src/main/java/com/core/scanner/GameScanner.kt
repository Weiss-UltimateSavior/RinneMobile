package com.core.scanner

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.core.data.GameRepository
import com.core.model.EngineType
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Phaser

/**
 * Bounded, cache-assisted game scanner.
 *
 * Directory metadata is read through [ScanDirectoryReader], which guarantees one directory
 * listing and no per-entry DocumentFile metadata queries. Directory work uses four workers;
 * SAF provider queries are independently limited to two concurrent calls.
 */
object GameScanner {
    private const val TAG = "GameScanner"
    private const val SCAN_WORKER_COUNT = 4

    const val SCAN_ALL_LEVELS = -1
    const val SCAN_UNTIL_GAME_MATCH = -2

    @JvmStatic
    fun scan(context: Context, rootUri: Uri): List<ScanResult> = scan(context, rootUri, 2)

    @JvmStatic
    fun scan(context: Context, rootUri: Uri, maxDepth: Int): List<ScanResult> =
        scan(context, rootUri, ScanRequest.defaults(maxDepth)).results

    @JvmStatic
    fun scan(context: Context, rootUri: Uri, request: ScanRequest): ScanReport {
        val startedAt = SystemClock.elapsedRealtime()
        val report = ScanReport()
        val requestedDepth = request.maxDepth
        val unbounded = requestedDepth == SCAN_ALL_LEVELS || requestedDepth == SCAN_UNTIL_GAME_MATCH
        val maxDepth = if (unbounded) Int.MAX_VALUE else requestedDepth.coerceIn(1, 4)
        val traverseMatchedGames = shouldTraverseMatchedGames(requestedDepth)
        val reader = ScanDirectoryReader(context.applicationContext, rootUri, request)
        val root = runCatching { reader.root() }.getOrNull()
        if (root == null) {
            report.setStopReason(ScanReport.StopReason.INVALID_ROOT)
            report.addError("无法访问扫描目录：$rootUri")
            return report
        }
        Log.i(
            TAG,
            "scan start root=${root.uri} mode=${if (root.localFile != null) "FILE" else "SAF"} " +
                "workers=$SCAN_WORKER_COUNT cache=${request.isCacheEnabled}"
        )

        val cached = if (request.isCacheEnabled) {
            ScanCacheStore.load(context.applicationContext, rootUri.toString(), requestedDepth)
        } else {
            emptyMap()
        }
        val seenUris: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val executor = Executors.newFixedThreadPool(SCAN_WORKER_COUNT)
        val phaser = Phaser(1)
        lateinit var schedule: (ScanNode, Int, Boolean) -> Unit
        schedule = { directory, level, selectedRoot ->
            if (!report.shouldStop(request)) {
                phaser.register()
                executor.execute {
                    try {
                        scanDirectory(
                            directory = directory,
                            level = level,
                            selectedRoot = selectedRoot,
                            maxDepth = maxDepth,
                            traverseMatchedGames = traverseMatchedGames,
                            reader = reader,
                            cached = cached,
                            report = report,
                            seenUris = seenUris,
                            request = request,
                            schedule = schedule
                        )
                    } finally {
                        phaser.arriveAndDeregister()
                    }
                }
            }
        }

        if (report.tryVisit(request, root.uri.toString())) schedule(root, 0, true)
        phaser.arriveAndAwaitAdvance()
        executor.shutdown()
        report.shouldStop(request)
        if (!report.isPartial) {
            ScanCacheStore.save(
                context.applicationContext,
                rootUri.toString(),
                requestedDepth,
                report.results
            )
        }
        Log.i(
            TAG,
            "scan finish root=${root.uri} elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                "visited=${report.visitedNodes} found=${report.results.size} stop=${report.stopReason}"
        )
        return report
    }

    private fun scanDirectory(
        directory: ScanNode,
        level: Int,
        selectedRoot: Boolean,
        maxDepth: Int,
        traverseMatchedGames: Boolean,
        reader: ScanDirectoryReader,
        cached: Map<String, ScanResult>,
        report: ScanReport,
        seenUris: MutableSet<String>,
        request: ScanRequest,
        schedule: (ScanNode, Int, Boolean) -> Unit
    ) {
        if (report.shouldStop(request)) return

        val cachedDirectory = cached[normalized(directory.uri)]
            ?.takeIf { isDirectoryEngine(it.engine) }
        if (cachedDirectory != null) {
            addResult(report, seenUris, cachedDirectory)
            if (!traverseMatchedGames) return
        }

        val children = try {
            reader.listChildren(directory)
        } catch (t: Exception) {
            if (!request.isCancelled && !request.isDeadlineReached) {
                Log.w(TAG, "list directory failed uri=${directory.uri}", t)
                report.addError("无法读取目录：${directory.uri}")
            }
            return
        }
        if (report.shouldStop(request)) return

        val psp = ArrayList<ScanNode>()
        val n3ds = ArrayList<ScanNode>()
        val switch = ArrayList<ScanNode>()
        val desktops = ArrayList<ScanNode>()
        val childDirectories = ArrayList<ScanNode>()
        var bestCover: ScanNode? = null
        var bestCoverScore = Int.MIN_VALUE

        // One classification pass for every direct child.
        for (node in children) {
            if (!report.tryVisit(request, node.uri.toString())) return
            val lower = node.name.lowercase(Locale.ROOT)
            if (node.isDirectory) {
                childDirectories.add(node)
                continue
            }
            when {
                isPspFile(lower) -> psp.add(node)
                isN3dsFile(lower) -> n3ds.add(node)
                isNintendoSwitchFile(lower) -> switch.add(node)
                lower.endsWith(".desktop") -> desktops.add(node)
            }
            if (isImageFile(lower)) {
                val score = coverNameScore(lower)
                if (bestCover == null || score > bestCoverScore) {
                    bestCover = node
                    bestCoverScore = score
                }
            }
        }

        val coverUri = bestCover?.uri?.toString().orEmpty()
        var fileEntryMatched = false
        fileEntryMatched = emitFileGroup(
            report, seenUris, directory, selectedRoot, psp, EngineType.PSP,
            "未命名PSP游戏", coverUri
        ) || fileEntryMatched
        fileEntryMatched = emitFileGroup(
            report, seenUris, directory, selectedRoot, n3ds, EngineType.NINTENDO_3DS,
            "未命名3DS游戏", coverUri
        ) || fileEntryMatched
        fileEntryMatched = emitFileGroup(
            report, seenUris, directory, selectedRoot, switch, EngineType.NINTENDO_SWITCH,
            "未命名Switch游戏", coverUri
        ) || fileEntryMatched
        fileEntryMatched = emitDesktopGroup(
            report, seenUris, directory, selectedRoot, desktops, coverUri
        ) || fileEntryMatched

        // Entry files directly under the user-selected scan root are independent games. They
        // must not turn the scan root itself into a matched game and prune all sibling folders.
        // For a nested folder, retaining the old behavior is intentional: ROM/desktop entries
        // identify that folder as a game container, so its internal asset folders are skipped.
        var gameMatched = shouldPruneAfterFileMatches(selectedRoot, fileEntryMatched)

        val internalAsset = !selectedRoot &&
            isInternalAssetDir(directory.name.lowercase(Locale.ROOT))
        if (!gameMatched && !internalAsset) {
            val detected = NodeEngineDetector.detect(children, 2, reader::listChildren)
            if (detected.confidence > 0 && isDirectoryEngine(detected.engine)) {
                addResult(
                    report,
                    seenUris,
                    ScanResult(
                        title = directory.name.ifBlank { "未命名游戏" },
                        uri = directory.uri.toString(),
                        engine = detected.engine,
                        confidence = detected.confidence,
                        launchTarget = detected.launchTarget,
                        coverUri = coverUri,
                        xp3Candidates = detected.xp3Candidates,
                        rpgMakerSubtype = detected.rpgMakerSubtype,
                        renpySubtype = detected.renpySubtype,
                        godotSubtype = detected.godotSubtype
                    )
                )
                gameMatched = true
            }
        }

        request.markProgress()
        if (level < maxDepth && (!gameMatched || traverseMatchedGames)) {
            for (child in childDirectories) {
                if (report.shouldStop(request)) return
                schedule(child, level + 1, false)
            }
        }
    }

    private fun emitFileGroup(
        report: ScanReport,
        seenUris: MutableSet<String>,
        directory: ScanNode,
        selectedRoot: Boolean,
        files: List<ScanNode>,
        engine: EngineType,
        unnamedTitle: String,
        coverUri: String
    ): Boolean {
        if (files.isEmpty()) return false
        for (file in files) {
            val title = if (files.size == 1 && !selectedRoot) {
                directory.name
            } else {
                stripExtension(file.name)
            }.ifBlank { unnamedTitle }
            addResult(
                report,
                seenUris,
                ScanResult(
                    title = title,
                    uri = file.uri.toString(),
                    engine = engine,
                    confidence = 95,
                    launchTarget = file.name,
                    coverUri = coverUri
                )
            )
        }
        return true
    }

    private fun emitDesktopGroup(
        report: ScanReport,
        seenUris: MutableSet<String>,
        directory: ScanNode,
        selectedRoot: Boolean,
        files: List<ScanNode>,
        coverUri: String
    ): Boolean {
        if (files.isEmpty()) return false
        for (file in files) {
            val title = if (files.size == 1 && !selectedRoot) {
                directory.name
            } else {
                stripExtension(file.name)
            }.ifBlank { "未命名游戏" }
            addResult(
                report,
                seenUris,
                ScanResult(
                    title = title,
                    uri = file.uri.toString(),
                    engine = EngineType.WINLATOR,
                    confidence = 90,
                    launchTarget = file.name,
                    coverUri = coverUri
                )
            )
        }
        return true
    }

    private fun addResult(
        report: ScanReport,
        seenUris: MutableSet<String>,
        result: ScanResult
    ): Boolean {
        val uri = result.uri ?: return false
        val key = GameRepository.normalizeRootUriKey(uri)
        if (key.isNotEmpty() && !seenUris.add(key)) return false
        report.addResult(result)
        return true
    }

    private fun normalized(uri: Uri): String = GameRepository.normalizeRootUriKey(uri.toString())

    private fun isDirectoryEngine(engine: EngineType?): Boolean = when (engine) {
        EngineType.KIRIKIRI,
        EngineType.ONS,
        EngineType.TYRANO,
        EngineType.ARTEMIS,
        EngineType.RPGMAKER,
        EngineType.RENPY,
        EngineType.GODOT -> true
        else -> false
    }

    private fun isPspFile(name: String): Boolean =
        name.endsWith(".iso") || name.endsWith(".cso") || name.endsWith(".chd") ||
            name.endsWith(".elf") || name.endsWith(".pbp")

    private fun isN3dsFile(name: String): Boolean =
        name.endsWith(".3ds") || name.endsWith(".cci") || name.endsWith(".zcci") ||
            name.endsWith(".cxi") || name.endsWith(".zcxi") || name.endsWith(".cia") ||
            name.endsWith(".zcia") || name.endsWith(".3dsx") || name.endsWith(".z3dsx")

    private fun isNintendoSwitchFile(name: String): Boolean =
        name.endsWith(".xci") || name.endsWith(".nsp") ||
            name.endsWith(".nca") || name.endsWith(".nro")

    private fun isImageFile(name: String): Boolean =
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".bmp")

    private fun coverNameScore(name: String): Int = when {
        name == "cover.jpg" || name == "cover.png" || name == "cover.webp" -> 100
        name == "folder.jpg" || name == "folder.png" || name == "folder.webp" -> 95
        name.contains("cover") || name.contains("folder") || name.contains("封面") -> 80
        name.contains("poster") || name.contains("package") || name.contains("main") -> 60
        else -> 10
    }

    private fun stripExtension(name: String): String = name.substringBeforeLast('.', name)

    private fun isInternalAssetDir(name: String): Boolean =
        name == "data" || name == "tyrano" || name == "resources" || name == "arc" ||
            name == "scenario" || name == "system" || name == "bgimage" || name == "fgimage" ||
            name == "image" || name == "sound" || name == "bgm" || name == "voice" ||
            name == "video" || name == "movie" || name == "font" || name == "others" ||
            name == "app" || name == "game" || name == "renpy"

    internal fun shouldTraverseMatchedGames(requestedDepth: Int): Boolean =
        requestedDepth == SCAN_ALL_LEVELS

    internal fun shouldPruneAfterFileMatches(selectedRoot: Boolean, fileEntryMatched: Boolean): Boolean =
        fileEntryMatched && !selectedRoot
}
