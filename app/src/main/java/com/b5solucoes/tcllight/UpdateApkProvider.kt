package com.b5solucoes.tcllight

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Two exact read-only export paths, each shared solely through a temporary URI grant. */
class UpdateApkProvider : ContentProvider() {
    companion object {
        internal fun apk(context: Context) = File(context.cacheDir, "updates/update.apk")
        internal fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.updates/update.apk")
        internal fun backup(context: Context) = File(context.cacheDir, "exports/backup.json")
        internal fun backupUri(context: Context): Uri = Uri.parse("content://${context.packageName}.updates/backup.json")
        const val MIME = "application/vnd.android.package-archive"
        internal fun installIntent(context: Context): Intent {
            val uri = uri(context)
            @Suppress("DEPRECATION")
            return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, MIME)
                clipData = ClipData.newRawUri("Atualização TCL Light", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
        }
    }

    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val app = context ?: throw FileNotFoundException()
        val file = when (uri) {
            UpdateApkProvider.uri(app) -> apk(app)
            backupUri(app) -> backup(app)
            else -> throw FileNotFoundException("Arquivo indisponível")
        }
        return file.takeIf { it.isFile } ?: throw FileNotFoundException("Arquivo indisponível")
    }

    override fun getType(uri: Uri): String { resolve(uri); return if (uri == backupUri(context!!)) "application/json" else MIME }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Somente leitura")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else if (file.extension == "json") "TCL-Light-Backup.json" else "TCL-Light-atualizacao.apk" })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
