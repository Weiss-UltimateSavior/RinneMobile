package com.core.nativeplugin

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class NativePluginImportResult(
    val success: Boolean,
    val code: String,
    val zipSha256: String? = null,
)

/**
 * Imports native engine plugins from user-selected zip files.
 *
 * The importer verifies only the whole zip SHA-256 against the expected hash configured by
 * the app, then performs structural checks and zip-slip protection before replacing current.
 */
object NativePluginInstaller {
    private const val TAG = "NativePluginInstaller"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ZIP_ENTRIES = 32
    private const val MAX_ENTRY_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L

    @JvmStatic
    fun importKirikiroid2(context: Context, uri: Uri?): NativePluginImportResult =
        importPlugin(context, uri, engineName = "Kirikiroid2", expectedShaProvider = {
            NativePluginManager.expectedKirikiroid2ZipSha256(context.applicationContext)
        }, rootDirProvider = {
            NativePluginManager.kirikiroid2RootDir(context.applicationContext)
        }, currentDirProvider = {
            NativePluginManager.kirikiroid2CurrentDir(context.applicationContext)
        }, defaultBridgeAbi = NativePluginConstants.KIRIKIROID2_BRIDGE_ABI, validator = {
            NativePluginManager.validateKirikiroid2Directory(it)
        }, recorder = { appContext, sha, version, abi, at ->
            NativePluginManager.recordKirikiroid2Install(appContext, sha, version, abi, at)
        })

    @JvmStatic
    fun importOns(context: Context, uri: Uri?): NativePluginImportResult =
        importPlugin(context, uri, engineName = "ONS", expectedShaProvider = {
            NativePluginManager.expectedOnsZipSha256(context.applicationContext)
        }, rootDirProvider = {
            NativePluginManager.onsRootDir(context.applicationContext)
        }, currentDirProvider = {
            NativePluginManager.onsCurrentDir(context.applicationContext)
        }, defaultBridgeAbi = NativePluginConstants.ONS_BRIDGE_ABI, validator = {
            NativePluginManager.validateOnsDirectory(it)
        }, recorder = { appContext, sha, version, abi, at ->
            NativePluginManager.recordOnsInstall(appContext, sha, version, abi, at)
        })

    @JvmStatic
    fun importArtemis(context: Context, uri: Uri?): NativePluginImportResult =
        importPlugin(context, uri, engineName = "Artemis", expectedShaProvider = {
            NativePluginManager.expectedArtemisZipSha256(context.applicationContext)
        }, rootDirProvider = {
            NativePluginManager.artemisRootDir(context.applicationContext)
        }, currentDirProvider = {
            NativePluginManager.artemisCurrentDir(context.applicationContext)
        }, defaultBridgeAbi = NativePluginConstants.ARTEMIS_BRIDGE_ABI, validator = {
            NativePluginManager.validateArtemisDirectory(it)
        }, recorder = { appContext, sha, version, abi, at ->
            NativePluginManager.recordArtemisInstall(appContext, sha, version, abi, at)
        })

    private fun importPlugin(
        context: Context,
        uri: Uri?,
        engineName: String,
        expectedShaProvider: () -> String?,
        rootDirProvider: () -> File,
        currentDirProvider: () -> File,
        defaultBridgeAbi: Int,
        validator: (File) -> Boolean,
        recorder: (Context, String, Int, Int, Long) -> Unit,
    ): NativePluginImportResult {
        if (uri == null) return NativePluginImportResult(false, "uri_missing")
        val appContext = context.applicationContext
        val expected = expectedShaProvider()
            ?: return NativePluginImportResult(false, "expected_sha256_missing")
        val actual = try {
            calculateSha256(appContext, uri)
        } catch (error: IOException) {
            Log.w(TAG, "Failed to read $engineName plugin zip for SHA-256", error)
            return NativePluginImportResult(false, "read_failed")
        } catch (error: SecurityException) {
            Log.w(TAG, "No permission to read $engineName plugin zip", error)
            return NativePluginImportResult(false, "read_failed")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Invalid $engineName plugin zip uri", error)
            return NativePluginImportResult(false, "read_failed")
        }
        if (!expected.equals(actual, ignoreCase = true)) {
            return NativePluginImportResult(false, "sha256_mismatch", actual)
        }

        val root = rootDirProvider()
        val staging = File(root, "staging-${System.currentTimeMillis()}")
        if (!staging.mkdirs()) return NativePluginImportResult(false, "staging_failed", actual)
        try {
            unzipSafely(appContext, uri, staging)
            if (!validator(staging)) {
                return NativePluginImportResult(false, "invalid_structure", actual)
            }
            hardenInstalledFiles(staging)
            val manifest = NativePluginManager.parseManifest(staging)
            val pluginVersion = manifest?.optInt("pluginVersion", 1) ?: 1
            val bridgeAbi = manifest?.optInt("bridgeAbi", defaultBridgeAbi) ?: defaultBridgeAbi
            if (!replaceCurrent(staging, root, currentDirProvider())) {
                return NativePluginImportResult(false, "replace_failed", actual)
            }
            recorder(
                appContext,
                actual.lowercase(Locale.ROOT),
                pluginVersion,
                bridgeAbi,
                System.currentTimeMillis(),
            )
            return NativePluginImportResult(true, "ok", actual)
        } catch (error: ZipRejectedException) {
            Log.w(TAG, "Rejected $engineName plugin zip", error)
            return NativePluginImportResult(false, "zip_rejected", actual)
        } catch (error: IOException) {
            Log.w(TAG, "Failed to import $engineName plugin zip", error)
            return NativePluginImportResult(false, "import_failed", actual)
        } catch (error: SecurityException) {
            Log.w(TAG, "No permission to import $engineName plugin zip", error)
            return NativePluginImportResult(false, "import_failed", actual)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unexpected $engineName plugin import failure", error)
            return NativePluginImportResult(false, "import_failed", actual)
        } finally {
            if (staging.exists()) {
                NativePluginManager.prepareDirectoryForDelete(staging)
                staging.deleteRecursively()
            }
        }
    }

    private fun calculateSha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IllegalArgumentException("Input stream is null")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzipSafely(context: Context, uri: Uri, targetDir: File) {
        val targetRoot = targetDir.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw ZipRejectedException()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAX_ZIP_ENTRIES) throw ZipRejectedException()
                    if (entry.size > MAX_ENTRY_UNCOMPRESSED_BYTES) throw ZipRejectedException()
                    val name = entry.name ?: throw ZipRejectedException()
                    if (name.isEmpty() || name.startsWith("/") || name.contains('\\')) {
                        throw ZipRejectedException()
                    }
                    if (name.split('/').any { it == ".." }) throw ZipRejectedException()
                    val output = File(targetRoot, name).canonicalFile
                    if (!isInside(output, targetRoot)) throw ZipRejectedException()
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw ZipRejectedException()
                    } else {
                        output.parentFile?.let {
                            if (!it.exists() && !it.mkdirs()) throw ZipRejectedException()
                        }
                        FileOutputStream(output).use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                entryBytes += read
                                totalBytes += read
                                if (
                                    entryBytes > MAX_ENTRY_UNCOMPRESSED_BYTES ||
                                    totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES
                                ) {
                                    throw ZipRejectedException()
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun hardenInstalledFiles(directory: File) {
        directory.walkTopDown()
            .forEach { file ->
                file.setReadable(true, true)
                if (file.isDirectory) {
                    file.setExecutable(true, true)
                    file.setWritable(false, false)
                } else {
                    file.setWritable(false, false)
                    file.setExecutable(false, false)
                }
            }
    }

    private fun replaceCurrent(staging: File, root: File, current: File): Boolean {
        if (!root.exists() && !root.mkdirs()) return false
        val backup = File(root, "backup-delete")
        if (backup.exists()) {
            NativePluginManager.prepareDirectoryForDelete(backup)
            backup.deleteRecursively()
        }
        if (current.exists() && !current.renameTo(backup)) return false
        if (!staging.renameTo(current)) {
            if (backup.exists()) backup.renameTo(current)
            return false
        }
        if (backup.exists()) {
            NativePluginManager.prepareDirectoryForDelete(backup)
            backup.deleteRecursively()
        }
        return true
    }

    private fun isInside(file: File, root: File): Boolean {
        val filePath = file.path
        val rootPath = root.path
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private class ZipRejectedException : Exception()
}
