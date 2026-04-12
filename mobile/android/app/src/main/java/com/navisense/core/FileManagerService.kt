package com.navisense.core

import android.content.Context
import android.os.StatFs
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service responsible for managing local file operations for the visual scanner module.
 * Handles CRUD operations for temporary scan images and error logging.
 */
class FileManagerService(private val context: Context) {

    companion object {
        private const val MIN_STORAGE_BYTES = 50L * 1024L * 1024L // 50 MB
        private const val TEMP_SCANS_DIR = "TempScans"
        private const val ERROR_LOG_FILE = "error_logs.txt"
        private const val FILE_PREFIX = "scan_"
        private const val FILE_SUFFIX = ".jpg"
        private val RANDOM = SecureRandom()
    }

    // Custom exception for insufficient storage
    class InsufficientStorageException(message: String) : IOException(message)

    // Custom exception for file operations
    class FileManagerException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Saves image data as a JPEG file in the TempScans directory.
     * @param imageBytes ByteArray containing JPEG image data
     * @return File reference to the created temporary file
     * @throws InsufficientStorageException if free space is less than 50 MB
     * @throws FileManagerException on I/O or security errors
     */
    @Throws(InsufficientStorageException::class, FileManagerException::class)
    fun saveImage(imageBytes: ByteArray): File {
        checkStorage()
        val tempDir = getTempScansDir()
        val fileName = generateUniqueFileName()
        val file = File(tempDir, fileName)

        return try {
            FileOutputStream(file).use { fos ->
                fos.write(imageBytes)
                fos.flush()
            }
            file
        } catch (e: SecurityException) {
            throw FileManagerException("Security exception while saving image", e)
        } catch (e: IOException) {
            throw FileManagerException("I/O error while saving image", e)
        }
    }

    /**
     * Reads a JPEG file and prepares it as a MultipartBody.Part for Retrofit upload.
     * @param file the JPEG file to upload
     * @return MultipartBody.Part ready for network request
     * @throws FileManagerException if the file does not exist or cannot be read
     */
    @Throws(FileManagerException::class)
    fun prepareImagePart(file: File): MultipartBody.Part {
        if (!file.exists() || !file.isFile) {
            throw FileManagerException("File does not exist or is not a regular file")
        }
        return try {
            val requestFile = RequestBody.create(MediaType.get("image/jpeg"), file)
            MultipartBody.Part.createFormData("image", file.name, requestFile)
        } catch (e: SecurityException) {
            throw FileManagerException("Security exception while preparing image part", e)
        } catch (e: IOException) {
            throw FileManagerException("I/O error while preparing image part", e)
        }
    }

    /**
     * Securely deletes a JPEG file after successful network response.
     * @param file the file to delete
     * @return true if the file was successfully deleted, false otherwise
     */
    fun deleteImage(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true // already deleted
            }
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Appends an error message with a timestamp to the error log file.
     * @param errorMessage the error description
     * @throws FileManagerException if writing fails
     */
    @Throws(FileManagerException::class)
    fun logError(errorMessage: String) {
        val logFile = getErrorLogFile()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$timestamp - $errorMessage\n"
        return try {
            logFile.appendText(logEntry)
        } catch (e: SecurityException) {
            throw FileManagerException("Security exception while logging error", e)
        } catch (e: IOException) {
            throw FileManagerException("I/O error while logging error", e)
        }
    }

    /**
     * Checks available free space in internal storage.
     * @throws InsufficientStorageException if free space < 50 MB
     */
    @Throws(InsufficientStorageException::class)
    private fun checkStorage() {
        val availableBytes = getAvailableStorageBytes()
        if (availableBytes < MIN_STORAGE_BYTES) {
            throw InsufficientStorageException(
                "Insufficient storage. Required: $MIN_STORAGE_BYTES bytes, available: $availableBytes bytes"
            )
        }
    }

    /**
     * Returns the number of free bytes in the internal storage partition.
     */
    private fun getAvailableStorageBytes(): Long {
        val path = context.filesDir.absolutePath
        val stat = StatFs(path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Returns the TempScans directory, creating it if necessary.
     */
    private fun getTempScansDir(): File {
        val dir = File(context.filesDir, TEMP_SCANS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns the error log file, creating it if necessary.
     */
    private fun getErrorLogFile(): File {
        val file = File(context.filesDir, ERROR_LOG_FILE)
        if (!file.exists()) {
            file.createNewFile()
        }
        return file
    }

    /**
     * Generates a unique filename with prefix, timestamp, random suffix.
     */
    private fun generateUniqueFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val random = RANDOM.nextInt(10000)
        return "${FILE_PREFIX}${timestamp}_${random}${FILE_SUFFIX}"
    }
}