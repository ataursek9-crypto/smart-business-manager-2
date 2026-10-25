// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

tasks.register("exportProjectZip") {
    group = "distribution"
    description = "Builds latest debug APK, copies to release-files/SmartBusinessManager.apk, and creates SmartBusinessManager_Export.zip"
    dependsOn(":app:assembleDebug")
    notCompatibleWithConfigurationCache("Requires project root directory walk and zip packaging")

    doLast {
        val apkFile = file("app/build/outputs/apk/debug/app-debug.apk")
        if (!apkFile.exists()) {
            throw GradleException("Build Error: APK file was not found at ${apkFile.absolutePath}")
        }
        val apkSize = apkFile.length()
        if (apkSize < 1000000L) {
            throw GradleException("Build Error: Generated APK is suspiciously small (${apkSize} bytes). Build may be corrupted.")
        }

        val releaseDir = file("release-files")
        if (!releaseDir.exists()) {
            releaseDir.mkdirs()
        }
        val targetApk = file("release-files/SmartBusinessManager.apk")
        apkFile.copyTo(targetApk, overwrite = true)
        val apkSizeMb = targetApk.length() / (1024 * 1024)
        println("Successfully generated APK: ${targetApk.absolutePath} ($apkSizeMb MB)")

        val zipFile = file("SmartBusinessManager_Export.zip")
        if (zipFile.exists()) {
            zipFile.delete()
        }

        val zipOut = java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile))
        try {
            // 1. Add release-files/SmartBusinessManager.apk
            val apkEntry = java.util.zip.ZipEntry("release-files/SmartBusinessManager.apk")
            zipOut.putNextEntry(apkEntry)
            targetApk.inputStream().use { it.copyTo(zipOut) }
            zipOut.closeEntry()

            // 2. Add SmartBusinessManager/ source tree (excluding build, .gradle, and zip files)
            val ignoredDirs = setOf("build", ".gradle", ".git", "release-files")
            val ignoredFiles = setOf("SmartBusinessManager_Export.zip", "backup_before_sync_fix.zip")

            rootDir.walkTopDown()
                .filter { f ->
                    val relPath = f.relativeTo(rootDir).path
                    val parts = relPath.split(java.io.File.separatorChar)
                    !parts.any { it in ignoredDirs } && f.name !in ignoredFiles
                }
                .forEach { f ->
                    val relPath = f.relativeTo(rootDir).path
                    if (relPath.isNotEmpty() && relPath != ".") {
                        val entryPath = "SmartBusinessManager/" + relPath.replace('\\', '/')
                        if (f.isDirectory) {
                            val dirEntry = java.util.zip.ZipEntry(if (entryPath.endsWith("/")) entryPath else "$entryPath/")
                            zipOut.putNextEntry(dirEntry)
                            zipOut.closeEntry()
                        } else {
                            val fileEntry = java.util.zip.ZipEntry(entryPath)
                            zipOut.putNextEntry(fileEntry)
                            f.inputStream().use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    }
                }
        } finally {
            zipOut.close()
        }

        // Verify the ZIP
        val zipVerify = java.util.zip.ZipFile(zipFile)
        val hasApk = zipVerify.getEntry("release-files/SmartBusinessManager.apk") != null
        val count = zipVerify.size()
        val zipSizeMb = zipFile.length() / (1024 * 1024)
        zipVerify.close()

        if (!hasApk) {
            throw GradleException("Verification Error: release-files/SmartBusinessManager.apk was not found inside SmartBusinessManager_Export.zip")
        }

        println("==================================================")
        println("Export Successful!")
        println("ZIP Path: ${zipFile.absolutePath}")
        println("ZIP Size: $zipSizeMb MB")
        println("Files in ZIP: $count")
        println("Verified: release-files/SmartBusinessManager.apk is present inside the ZIP!")
        println("==================================================")
    }
}

tasks.register("exportZip") {
    group = "distribution"
    description = "Alias for exportProjectZip"
    dependsOn("exportProjectZip")
}

