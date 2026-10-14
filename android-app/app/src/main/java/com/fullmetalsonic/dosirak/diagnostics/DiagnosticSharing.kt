package com.fullmetalsonic.dosirak.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object DiagnosticSharing {
    fun chooser(context: Context, file: File): Intent {
        val allowed = File(context.cacheDir, "analysis-share").canonicalFile
        require(file.canonicalFile.parentFile == allowed && file.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.analysis-files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "분석 로그", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "로그 전송하기").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
