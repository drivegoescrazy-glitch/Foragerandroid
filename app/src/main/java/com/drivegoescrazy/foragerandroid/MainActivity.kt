package com.drivegoescrazy.foragerandroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.InputStream
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseButton: Button
    private lateinit var playButton: Button
    private val folderRequest = 1001
    private val officialPackage = "com.humblebundle.forager"
    private val officialStoreUrl = "https://play.google.com/store/apps/details?id=com.humblebundle.forager"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Forager Android Companion"
            textSize = 25f
        })
        status = TextView(this).apply {
            text = "Forager's Linux executable cannot be converted into an Android APK by copying files. For a playable version, the reliable route is the Android release. This companion can open it if installed, or open its store listing."
            textSize = 16f
            setPadding(0, 18, 0, 18)
        }
        root.addView(status)
        playButton = Button(this).apply {
            text = "Open / Install Forager for Android"
            setOnClickListener { openOfficialForager() }
        }
        root.addView(playButton)
        chooseButton = Button(this).apply {
            text = "Analyze Linux Forager folder"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, folderRequest)
            }
        }
        root.addView(chooseButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12))
        report = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(report) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun openOfficialForager() {
        val launchIntent = packageManager.getLaunchIntentForPackage(officialPackage)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through to the store listing.
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$officialPackage")))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(officialStoreUrl)))
        }
    }

    @Deprecated("Deprecated API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != folderRequest || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not support persistent grants.
        }
        chooseButton.isEnabled = false
        playButton.isEnabled = false
        progress.progress = 0
        status.text = "Inspecting game files…"
        Thread {
            val result = inspectTree(tree) { value ->
                runOnUiThread { progress.progress = value }
            }
            runOnUiThread {
                status.text = "Analysis complete. No conversion was attempted."
                report.text = result
                chooseButton.isEnabled = true
                playButton.isEnabled = true
            }
        }.start()
    }

    private fun inspectTree(tree: Uri, onProgress: (Int) -> Unit): String {
        val output = mutableListOf<String>()
        val likelyFiles = mutableListOf<String>()
        val queue = ArrayDeque<Uri>()
        var visited = 0
        var totalBytes = 0L
        var hasGameUnx = false
        var hasDataWin = false
        var hasLinuxExecutable = false
        var hasForagerName = false
        var hasUnityData = false
        var hasUnityPlayer = false
        var firstError: String? = null
        val limit = 20000
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            queue.add(DocumentsContract.buildDocumentUriUsingTree(tree, rootId))
            while (queue.isNotEmpty() && visited < limit) {
                val parent = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getDocumentId(parent)
                )
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                )
                contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext() && visited < limit) {
                        visited++
                        val id = cursor.getString(idCol)
                        val name = cursor.getString(nameCol) ?: "(unnamed)"
                        val mime = cursor.getString(mimeCol)
                        val lower = name.lowercase(Locale.ROOT)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                            if (lower.endsWith("_data") || lower == "data") hasUnityData = true
                        } else {
                            val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                            totalBytes += size
                            if (lower.contains("forager")) hasForagerName = true
                            if (lower == "game.unx" || lower == "game.ios") hasGameUnx = true
                            if (lower == "data.win") hasDataWin = true
                            if (lower == "unityplayer.so" || lower == "libunity.so") hasUnityPlayer = true
                            if (lower.endsWith(".x86_64") || lower.endsWith(".x86") ||
                                lower.endsWith(".elf") || lower == "forager") {
                                if (isLinuxElf(tree, id)) hasLinuxExecutable = true
                            }
                            if (lower.contains("forager") || lower == "game.unx" || lower == "data.win" ||
                                lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".exe") ||
                                lower.endsWith(".x86_64") || lower.endsWith(".pak") || lower.endsWith(".dat")) {
                                likelyFiles.add("$name (${formatBytes(size)})")
                            }
                        }
                        if (visited % 100 == 0) onProgress((visited / 200).coerceIn(1, 95))
                    }
                }
            }
        } catch (e: Exception) {
            firstError = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }

        output.add("INPUT ANALYSIS")
        output.add("Entries inspected: $visited${if (visited >= limit) " (limit reached)" else ""}")
        output.add("Reported file size: ${formatBytes(totalBytes)}")
        output.add("Forager-named files: ${if (hasForagerName) "found" else "not identified"}")
        output.add("GameMaker game.unx / game.ios: ${if (hasGameUnx) "found" else "not found"}")
        output.add("GameMaker data.win: ${if (hasDataWin) "found" else "not found"}")
        output.add("Linux ELF executable: ${if (hasLinuxExecutable) "confirmed" else "not confirmed"}")
        output.add("Unity data directory: ${if (hasUnityData) "found" else "not found"}")
        output.add("Unity native library: ${if (hasUnityPlayer) "found" else "not found"}")
        if (firstError != null) output.add("Scan warning: $firstError")
        output.add("")
        output.add("Potential game/runtime files:")
        output.addAll(likelyFiles.take(80).ifEmpty { listOf("No likely game files found by filename.") })
        output.add("")
        output.add("WHY CONVERSION IS NOT AVAILABLE")
        output.add("This Linux folder does not include an Android-compatible Forager game executable. Forager's GameMaker YYC/native build contains platform-specific machine code; an Android APK cannot turn x86_64 Linux code into ARM64 Android code by renaming or repackaging it.")
        output.add("The open-source Butterscotch GameMaker runner does not support Forager's YYC build. A true custom APK would require an Android-compiled Forager build or a complete Linux x86_64 emulation/graphics/audio environment and device testing.")
        output.add("")
        output.add("PLAYABLE PATH")
        output.add("Use 'Open / Install Forager for Android' above. If the official Android app is installed, it launches it; otherwise the button opens its store listing. This is separate from the Linux files selected here.")
        output.add("")
        output.add("No source files were modified, and no APK was created from the Linux folder.")
        onProgress(100)
        return output.joinToString("\n")
    }

    private fun isLinuxElf(tree: Uri, documentId: String): Boolean {
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                val header = ByteArray(5)
                val count = stream.read(header)
                count >= 5 && header[0] == 0x7f.toByte() &&
                    header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        if (value < 1024L * 1024L) return String.format(Locale.US, "%.1f KiB", value / 1024.0)
        return String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024.0))
    }
}
