package com.drivelift.app.feature.whatsappsender.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import java.io.File

/** Converts local files and SAF/MediaStore references into shareable content:// URIs without exposing file:// URIs. */
class SecureUriResolver(
    private val context: Context,
    private val fileUriFactory: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    },
) {

    fun resolve(item: LocalSendItem): Uri {
        val uriString = item.uriString?.trim().orEmpty()
        val resolved: Uri = when {
            uriString.startsWith("content://", ignoreCase = true) -> Uri.parse(uriString)
            uriString.startsWith("file://", ignoreCase = true) -> {
                val path = Uri.parse(uriString).path
                    ?: throw IllegalArgumentException("Missing file path for URI: ${item.name}")
                resolveFile(File(path))
            }
            !item.filePath.isNullOrBlank() -> resolveFile(File(item.filePath))
            else -> throw IllegalArgumentException("No shareable URI or local path for ${item.name}")
        }
        require(resolved.scheme == ContentResolver.SCHEME_CONTENT) {
            "Refusing to expose non-content URI for ${item.name}: ${resolved.scheme}"
        }
        return resolved
    }

    fun resolveFile(file: File): Uri {
        val resolved = fileUriFactory(file)
        require(resolved.scheme == ContentResolver.SCHEME_CONTENT) {
            "FileProvider returned non-content URI for ${file.name}: ${resolved.scheme}"
        }
        return resolved
    }

    companion object {
        fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"
    }
}
