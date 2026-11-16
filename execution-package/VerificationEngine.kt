package com.example.execution

import java.util.UUID

enum class VerificationType {
    TYPE_1, // File existence check
    TYPE_2, // File content check
    TYPE_3, // Directory existence check
    TYPE_4, // Directory content check
    TYPE_5, // Executable permission check
    TYPE_6, // Process availability check
    TYPE_7  // HTTP endpoint availability check
}

data class VerificationConfig(
    val type: VerificationType,
    val params: Map<String, Any>
)

data class VerificationResult(
    val passed: Boolean,
    val message: String,
    val taskComplete: Boolean
)

class VerificationEngine {

    fun verify(config: VerificationConfig): VerificationResult {
        val type = config.type
        val params = config.params

        return when (type) {
            VerificationType.TYPE_1 -> verifyFileExistence(params)
            VerificationType.TYPE_2 -> verifyFileContent(params)
            VerificationType.TYPE_3 -> verifyDirectoryExistence(params)
            VerificationType.TYPE_4 -> verifyDirectoryContent(params)
            VerificationType.TYPE_5 -> verifyExecutablePermission(params)
            VerificationType.TYPE_6 -> verifyProcessAvailability(params)
            VerificationType.TYPE_7 -> verifyHttpEndpoint(params)
            else -> VerificationResult(false, "Unknown verification type", false)
        }
    }

    private fun verifyFileExistence(params: Map<String, Any>): VerificationResult {
        val filePath = params["path"] as? String
            ?: return VerificationResult(false, "Missing 'path' parameter", false)
        
        val exists = java.io.File(filePath).exists()
        return VerificationResult(
            passed = exists,
            message = if (exists) "File exists" else "File does not exist",
            taskComplete = false
        )
    }

    private fun verifyFileContent(params: Map<String, Any>): VerificationResult {
        val filePath = params["path"] as? String
            ?: return VerificationResult(false, "Missing 'path' parameter", false)
        val expectedContent = params["content"] as? String
            ?: return VerificationResult(false, "Missing 'content' parameter", false)
        
        val fileContent = java.io.File(filePath).readText()
        val matches = fileContent == expectedContent
        return VerificationResult(
            passed = matches,
            message = if (matches) "Content matches" else "Content mismatch",
            taskComplete = false
        )
    }

    private fun verifyDirectoryExistence(params: Map<String, Any>): VerificationResult {
        val dirPath = params["path"] as? String
            ?: return VerificationResult(false, "Missing 'path' parameter", false)
        
        val exists = java.io.File(dirPath).exists() && java.io.File(dirPath).isDirectory()
        return VerificationResult(
            passed = exists,
            message = if (exists) "Directory exists" else "Directory does not exist",
            taskComplete = false
        )
    }

    private fun verifyDirectoryContent(params: Map<String, Any>): VerificationResult {
        val dirPath = params["path"] as? String
            ?: return VerificationResult(false, "Missing 'path' parameter", false)
        val expectedFiles = params["files"] as? List<String>
            ?: return VerificationResult(false, "Missing 'files' parameter", false)
        
        val actualFiles = java.io.File(dirPath).listFiles()?.map { it.name } ?: emptyList()
        val missingFiles = expectedFiles.filterNot { actualFiles.contains(it) }
        
        if (missingFiles.isNotEmpty()) {
            return VerificationResult(
                passed = false,
                message = "Missing files: ${missingFiles.joinToString(", ")}",
                taskComplete = false
            )
        }
        
        return VerificationResult(
            passed = true,
            message = "All expected files present",
            taskComplete = false
        )
    }

    private fun verifyExecutablePermission(params: Map<String, Any>): VerificationResult {
        val filePath = params["path"] as? String
            ?: return VerificationResult(false, "Missing 'path' parameter", false)
        
        val file = java.io.File(filePath)
        if (!file.exists()) {
            return VerificationResult(false, "File does not exist", false)
        }
        
        val executable = file.canRead() && file.canExecute()
        return VerificationResult(
            passed = executable,
            message = if (executable) "Executable permission granted" else "Missing executable permission",
            taskComplete = false
        )
    }

    private fun verifyProcessAvailability(params: Map<String, Any>): VerificationResult {
        val processName = params["process_name"] as? String
            ?: return VerificationResult(false, "Missing 'process_name' parameter", false)
        
        val process = java.lang.Runtime.getRuntime().exec(processName)
        val success = process.waitFor() == 0
        return VerificationResult(
            passed = success,
            message = if (success) "Process is running" else "Process not available",
            taskComplete = false
        )
    }

    private fun verifyHttpEndpoint(params: Map<String, Any>): VerificationResult {
        val url = params["url"] as? String
            ?: return VerificationResult(false, "Missing 'url' parameter", false)
        
        return try {
            java.net.URL(url).openConnection().inputStream.use { _ ->
                VerificationResult(true, "HTTP endpoint is available", false)
            }
        } catch (e: Exception) {
            VerificationResult(false, "HTTP endpoint unavailable: ${e.message}", false)
        }
    }
}