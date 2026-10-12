package com.android.BioKey.core.security

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Secure URI Handling & Content Isolation Engine
 *
 * Implements strict content isolation, scoped FileProvider enforcement, canonical path
 * traversal defense, and ephemeral URI permission lifecycle management.
 */
object SecureUriHandler {

    private const val TAG = "SecureUriHandler"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /**
     * Validates that the target file resides strictly within the application's private
     * internal storage directory, preventing directory traversal / symlink escapes.
     */
    fun isPathWithinSandbox(context: Context, file: File): Boolean {
        return try {
            val canonicalFile = file.canonicalFile
            val canonicalFilesDir = context.filesDir.canonicalFile
            val canonicalNoBackupDir = context.noBackupFilesDir.canonicalFile

            canonicalFile.toPath().startsWith(canonicalFilesDir.toPath()) ||
            canonicalFile.toPath().startsWith(canonicalNoBackupDir.toPath())
        } catch (e: IOException) {
            Log.e(TAG, "Path validation failed due to I/O error: ${e.message}")
            false
        }
    }

    /**
     * Safely constructs a content URI for private files, enforcing sandbox boundary validation.
     */
    fun getShareableUri(context: Context, file: File): Uri? {
        if (!file.exists()) {
            Log.w(TAG, "Cannot generate URI: file does not exist: ${file.path}")
            return null
        }

        if (!isPathWithinSandbox(context, file)) {
            Log.e(TAG, "Security violation: Attempted to generate URI outside sandbox boundary: ${file.path}")
            throw SecurityException("Path traversal attempt detected. File is outside app sandbox.")
        }

        val authority = "${context.packageName}$FILE_PROVIDER_SUFFIX"
        return try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider unable to resolve file: ${file.path}", e)
            null
        }
    }

    /**
     * Constructs a hardened Intent with ephemeral READ permissions and explicit ClipData binding.
     */
    fun createSecureShareIntent(
        context: Context,
        file: File,
        targetPackage: String? = null,
        mimeType: String = "image/jpeg"
    ): Intent? {
        val uri = getShareableUri(context, file) ?: return null

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "BioKey Evidence", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
        return intent
    }

    /**
     * Explicitly revokes granted URI permissions to prevent residual access.
     */
    fun revokeAccess(context: Context, uri: Uri) {
        try {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.d(TAG, "Granted URI permission revoked: $uri")
        } catch (e: Exception) {
            Log.w(TAG, "Failed revoking URI permission: ${e.message}")
        }
    }

    /**
     * Validates that an incoming URI is strictly a Content URI matching BioKey's authority.
     */
    fun isAuthorizedContentUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val expectedAuthority = "${context.packageName}$FILE_PROVIDER_SUFFIX"
        return uri.authority == expectedAuthority
    }
}
