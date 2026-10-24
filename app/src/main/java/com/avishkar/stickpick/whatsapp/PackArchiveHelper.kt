package com.avishkar.stickpick.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.avishkar.stickpick.data.local.PackStorage
import com.avishkar.stickpick.data.model.Sticker
import com.avishkar.stickpick.data.model.StickerPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PackArchiveHelper {
    private const val TAG = "PackArchiveHelper"

    /**
     * Exports a pack into a standard .wastickers archive (compatible with WAStickerApps & Sticker Maker).
     */
    fun exportPackToWastickers(context: Context, pack: StickerPack, packStorage: PackStorage): File? {
        try {
            val shareDir = File(context.cacheDir, "shared_packs").also { it.mkdirs() }
            val safeName = pack.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val zipFile = File(shareDir, "$safeName.wastickers")

            val baseId = run {
                val last = pack.identifier.lastIndexOf('_')
                if (last > 0) pack.identifier.substring(0, last) else pack.identifier
            }
            val baseDir = packStorage.getConvertedDir(baseId)
            val exactDir = packStorage.getConvertedDir(pack.identifier)
            val trayFile = File(baseDir, pack.trayImageFile).let { if (it.exists()) it else File(exactDir, pack.trayImageFile) }

            val convertedDir = if (exactDir.exists() && exactDir.listFiles()?.isNotEmpty() == true) exactDir else baseDir

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. title.txt
                zos.putNextEntry(ZipEntry("title.txt"))
                zos.write(pack.name.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 2. author.txt
                zos.putNextEntry(ZipEntry("author.txt"))
                zos.write(pack.publisher.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 3. tray icon
                if (trayFile.exists()) {
                    val trayExt = if (trayFile.name.endsWith(".png", ignoreCase = true)) "tray.png" else "tray.webp"
                    zos.putNextEntry(ZipEntry(trayExt))
                    trayFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 4. stickers
                pack.stickers.forEachIndexed { index, sticker ->
                    val sFile = File(convertedDir, sticker.imageFileName)
                    if (sFile.exists()) {
                        val entryName = "%02d.webp".format(index + 1)
                        zos.putNextEntry(ZipEntry(entryName))
                        sFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            return zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting .wastickers: ${e.message}", e)
            return null
        }
    }

    /**
     * Launches Android Share sheet to send the .wastickers pack directly to WhatsApp contacts or other apps.
     */
    fun sharePack(context: Context, pack: StickerPack, packStorage: PackStorage) {
        val file = exportPackToWastickers(context, pack, packStorage)
        if (file == null || !file.exists()) {
            Toast.makeText(context, "Failed to create sticker pack archive", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${pack.name} Stickers")
            putExtra(Intent.EXTRA_TEXT, "Here is the \"${pack.name}\" sticker pack! Open this file with StickPick to add all stickers to WhatsApp.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Sticker Pack (${pack.name})"))
    }

    /**
     * Imports a .wastickers archive (from WhatsApp, file manager, etc.) into StickPick storage.
     */
    suspend fun importWastickers(context: Context, uri: Uri, packStorage: PackStorage): StickerPack? = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            var packTitle = "Imported Pack"
            var packAuthor = "StickPick"
            val tempDir = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}").also { it.mkdirs() }
            val extractedFiles = mutableListOf<File>()
            var trayFile: File? = null

            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        when {
                            name.equals("title.txt", ignoreCase = true) -> {
                                packTitle = zis.bufferedReader(Charsets.UTF_8).readText().trim()
                            }
                            name.equals("author.txt", ignoreCase = true) -> {
                                packAuthor = zis.bufferedReader(Charsets.UTF_8).readText().trim()
                            }
                            name.startsWith("tray", ignoreCase = true) -> {
                                val tFile = File(tempDir, name)
                                tFile.outputStream().use { zis.copyTo(it) }
                                trayFile = tFile
                            }
                            name.endsWith(".webp", ignoreCase = true) || name.endsWith(".png", ignoreCase = true) -> {
                                val sFile = File(tempDir, name)
                                sFile.outputStream().use { zis.copyTo(it) }
                                extractedFiles.add(sFile)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (extractedFiles.isEmpty()) {
                tempDir.deleteRecursively()
                return@withContext null
            }

            val packId = "pack_" + System.currentTimeMillis()
            val destDir = packStorage.getConvertedDir(packId)
            destDir.mkdirs()

            extractedFiles.sortWith(Comparator { f1, f2 -> f1.name.compareTo(f2.name) })

            var hasAnimation = false
            val stickersList = mutableListOf<Sticker>()

            extractedFiles.forEachIndexed { idx, srcFile ->
                val destName = "%02d.webp".format(idx + 1)
                val destFile = File(destDir, destName)
                srcFile.copyTo(destFile, overwrite = true)

                if (!hasAnimation && isAnimatedWebp(destFile)) {
                    hasAnimation = true
                }

                stickersList.add(Sticker(imageFileName = destName, emojis = listOf("✨")))
            }

            val trayName = if (trayFile != null && trayFile.name.endsWith(".png", ignoreCase = true)) "tray.png" else "tray.webp"
            val finalTray = File(destDir, trayName)
            if (trayFile != null && trayFile.exists()) {
                trayFile.copyTo(finalTray, overwrite = true)
            } else if (stickersList.isNotEmpty()) {
                File(destDir, stickersList.first().imageFileName).copyTo(finalTray, overwrite = true)
            }

            tempDir.deleteRecursively()

            val newPack = StickerPack(
                identifier = packId,
                name = packTitle.ifEmpty { "Sticker Pack" },
                publisher = packAuthor.ifEmpty { "StickPick" },
                trayImageFile = trayName,
                publisherEmail = "",
                publisherWebsite = "",
                privacyPolicyWebsite = "",
                licenseAgreementWebsite = "",
                imageDataVersion = "1",
                avoidCache = false,
                animatedStickerPack = hasAnimation,
                stickers = stickersList
            )

            packStorage.savePack(newPack)
            return@withContext newPack
        } catch (e: Exception) {
            Log.e(TAG, "Error importing .wastickers: ${e.message}", e)
            return@withContext null
        }
    }

    private fun isAnimatedWebp(file: File): Boolean {
        return try {
            if (file.length() < 30) return false
            val headerBytes = ByteArray(30)
            file.inputStream().use { it.read(headerBytes) }
            val header = String(headerBytes, Charsets.US_ASCII)
            header.contains("ANIM")
        } catch (e: Exception) {
            false
        }
    }
}
