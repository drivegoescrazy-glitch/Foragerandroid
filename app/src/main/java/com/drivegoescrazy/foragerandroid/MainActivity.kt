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
    private val folderRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 28, 24, 24)

        val title = TextView(this)
        title.text = "Forager Android Porter"
        title.textSize = 25f
        root.addView(title)

        status = TextView(this)
        status.text = "Select the folder containing your legally obtained Linux copy of Forager."
        status.textSize = 16f
        status.setPadding(0, 18, 0, 18)
        root.addView(status)

        val choose = Button(this)
        choose.text = "Select Linux game folder"
        choose.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(intent, folderRequest)
        }
        root.addView(choose)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progress = 0
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12))

        report = TextView(this)
        report.textSize = 14f
        report.setPadding(0, 16, 0, 0)
        val scroll = ScrollView(this)
        scroll.addView(report)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        status.text = "Scanning game folder…"
        progress.progress = 10
        Thread {
            val result = inspectTree(tree)
            runOnUiThread {
                progress.progress = 100
                status.text = "Folder analysis complete"
                report.text = result
            }
        }.start()
    }

    private fun inspectTree(tree: Uri): String {
        val output = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        val queue = ArrayDeque<Uri>()
        var visited = 0
        var totalBytes = 0L
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
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        } else {
                            val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                            totalBytes += size
                            val lower = name.lowercase(Locale.ROOT)
                            if (lower.contains("forager") || lower.endsWith(".so") ||
                                lower.endsWith(".dll") || lower.endsWith(".exe") ||
                                lower.endsWith(".win") || lower.endsWith(".pak")) {
                                candidates.add(name + " (" + formatBytes(size) + ")")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            output.add("Folder scan encountered an error: " + e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
        }
        output.add("Entries inspected: " + visited)
        output.add("Reported file size total: " + formatBytes(totalBytes))
        output.add("")
        output.add("Potential game/runtime files:")
        output.addAll(candidates.take(100).ifEmpty { listOf("No recognisable filenames found.") })
        if (visited >= limit) output.add("\nScan stopped at the safety limit of " + limit + " entries.")
        output.add("")
        output.add("STATUS: diagnostic prototype only. This app does not yet convert or launch Forager. A Linux executable cannot become a native Android game by repackaging alone; the game engine/runtime and Android-compatible native libraries must be identified and implemented before a playable port can be built.")
        return output.joinToString("\n")
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return value.toString() + " B"
        if (value < 1024L * 1024L) return String.format(Locale.US, "%.1f KiB", value / 1024.0)
        return String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024.0))
    }
}
