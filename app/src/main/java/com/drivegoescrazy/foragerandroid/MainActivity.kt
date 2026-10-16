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
            text = "Step 1: select the folder containing your Linux Forager installation. The app will inspect it and report what it finds."
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
        status.text = "Inspecting the Linux game files…"
        progress.progress = 5
        Thread {
            val result = inspectTree(tree)
            runOnUiThread {
                progress.progress = 100
                status.text = "Analysis finished"
                report.text = result
                chooseButton.isEnabled = true
            }
        }.start()
    }

    private fun inspectTree(tree: Uri): String {
        val output = mutableListOf<String>()
        val likelyFiles = mutableListOf<String>()
        val queue = ArrayDeque<Uri>()
        var visited = 0
        var totalBytes = 0L
        var hasUnityData = false
        var hasUnityPlayer = false
        var hasForagerName = false
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
                            if (lower == "unityplayer.so" || lower == "libunity.so") hasUnityPlayer = true
                            if (lower.contains("forager") || lower.endsWith(".so") ||
                                lower.endsWith(".dll") || lower.endsWith(".exe") ||
                                lower.endsWith(".win") || lower.endsWith(".pak") ||
                                lower.endsWith(".dat")) {
                                likelyFiles.add("$name (${formatBytes(size)})")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            output.add("Folder scan error: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
        }
        output.add("FILESYSTEM ANALYSIS")
        output.add("Entries inspected: $visited")
        output.add("Reported total file size: ${formatBytes(totalBytes)}")
        output.add("Forager-named files detected: ${if (hasForagerName) "yes" else "not by filename"}")
        output.add("Unity data directory detected: ${if (hasUnityData) "yes" else "not detected"}")
        output.add("Unity native library detected: ${if (hasUnityPlayer) "yes" else "not detected"}")
        output.add("")
        output.add("Potential game/runtime files:")
        output.addAll(likelyFiles.take(80).ifEmpty { listOf("No likely game files found by filename.") })
        if (visited >= limit) output.add("\nScan stopped at the safety limit of $limit entries.")
        output.add("")
        output.add("PORT STATUS")
        output.add("This version analyses files only; it does not yet create a playable APK. A Linux executable needs an Android-compatible game runtime/engine or a tested compatibility layer. Copying Linux files into an APK will not make them run natively.")
        output.add("If this is a Unity Linux build, the Unity data folder alone is not an Android project. The original project/source or a suitable compatibility runtime must be available before a real port can be implemented.")
        output.add("")
        output.add("No game files were changed.")
        return output.joinToString("\n")
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        if (value < 1024L * 1024L) return String.format(Locale.US, "%.1f KiB", value / 1024.0)
        return String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024.0))
    }
}
