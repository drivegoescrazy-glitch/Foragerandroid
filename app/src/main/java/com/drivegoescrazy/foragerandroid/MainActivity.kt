package com.drivegoescrazy.foragerandroid

import android.app.Activity
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
    private val folderRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Forager Android Porter"
            textSize = 25f
        })
        status = TextView(this).apply {
            text = "Select your Linux Forager installation. The app will identify the game format and tell you whether these files can be converted with the available runtime."
            textSize = 16f
            setPadding(0, 18, 0, 18)
        }
        root.addView(status)
        chooseButton = Button(this).apply {
            text = "Select Linux Forager folder"
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
        progress.progress = 0
        status.text = "Inspecting game files…"
        Thread {
            val result = inspectTree(tree) { value ->
                runOnUiThread { progress.progress = value }
            }
            runOnUiThread {
                status.text = "File analysis complete — no APK has been generated"
                report.text = result
                chooseButton.isEnabled = true
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
        output.add("CONVERSION RESULT")
        output.add("No APK was created. This app does not yet contain a compatible GameMaker runtime or an APK packaging/build engine.")
        output.add("A Linux executable is compiled for Linux and cannot be made Android-native by renaming it or placing it inside an APK. Forager has GameMaker builds that use platform-specific native code; if this copy is a YYC/native build, its game logic cannot be recovered from the executable by a file converter.")
        output.add("A real playable port needs the original GameMaker project/source or a verified compatible Android runner plus game data that runner supports. This build deliberately does not output a fake APK.")
        output.add("")
        output.add("No source files were modified.")
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
