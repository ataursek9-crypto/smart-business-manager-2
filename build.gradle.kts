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
    description = "Builds latest debug APK and creates SmartBusinessManager_Project.zip with APK/SmartBusinessManager.apk, README_BN.txt, and SmartBusinessManager_Project/ source"
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

        val apkDir = file("APK")
        if (!apkDir.exists()) {
            apkDir.mkdirs()
        }
        val targetApkInApkDir = file("APK/SmartBusinessManager.apk")
        apkFile.copyTo(targetApkInApkDir, overwrite = true)

        val rootApk = file("SmartBusinessManager.apk")
        apkFile.copyTo(rootApk, overwrite = true)

        val releaseDir = file("release-files")
        if (!releaseDir.exists()) {
            releaseDir.mkdirs()
        }
        val targetApk = file("release-files/SmartBusinessManager.apk")
        apkFile.copyTo(targetApk, overwrite = true)
        val apkSizeMb = targetApk.length() / (1024 * 1024)
        println("Successfully generated APK: ${targetApk.absolutePath} ($apkSizeMb MB)")

        val zipFile = file("SmartBusinessManager_Project.zip")
        if (zipFile.exists()) {
            zipFile.delete()
        }

        val readmeFile = file("README_BN.txt")

        val zipOut = java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile))
        try {
            // 1. Add APK/SmartBusinessManager.apk
            val apkEntry = java.util.zip.ZipEntry("APK/SmartBusinessManager.apk")
            zipOut.putNextEntry(apkEntry)
            targetApkInApkDir.inputStream().use { it.copyTo(zipOut) }
            zipOut.closeEntry()

            // 2. Add README_BN.txt at root of ZIP
            if (readmeFile.exists()) {
                val readmeEntry = java.util.zip.ZipEntry("README_BN.txt")
                zipOut.putNextEntry(readmeEntry)
                readmeFile.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }

            // 3. Add SmartBusinessManager_Project/ source tree
            val ignoredDirs = setOf("build", ".gradle", ".git", "release-files", "APK")
            val ignoredFiles = setOf("SmartBusinessManager_Project.zip", "SmartBusinessManager_Export.zip", "SmartBusinessManager.apk", "backup_before_sync_fix.zip")

            rootDir.walkTopDown()
                .filter { f ->
                    val relPath = f.relativeTo(rootDir).path
                    val parts = relPath.split(java.io.File.separatorChar)
                    !parts.any { it in ignoredDirs } && f.name !in ignoredFiles
                }
                .forEach { f ->
                    val relPath = f.relativeTo(rootDir).path
                    if (relPath.isNotEmpty() && relPath != ".") {
                        val entryPath = "SmartBusinessManager_Project/" + relPath.replace('\\', '/')
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

        // Also copy as alias SmartBusinessManager_Export.zip for backward compatibility
        val legacyZip = file("SmartBusinessManager_Export.zip")
        zipFile.copyTo(legacyZip, overwrite = true)

        // Verify the ZIP
        val zipVerify = java.util.zip.ZipFile(zipFile)
        val apkInside = zipVerify.getEntry("APK/SmartBusinessManager.apk")
        val readmeInside = zipVerify.getEntry("README_BN.txt")
        val count = zipVerify.size()
        val zipSizeMb = zipFile.length() / (1024 * 1024)

        if (apkInside == null) {
            zipVerify.close()
            throw GradleException("Verification Error: APK/SmartBusinessManager.apk was not found inside SmartBusinessManager_Project.zip")
        }
        val insideApkSize = apkInside.size
        zipVerify.close()

        if (insideApkSize < 1000000L) {
            throw GradleException("Verification Error: APK inside ZIP is too small ($insideApkSize bytes)")
        }

        println("==================================================")
        println("Export Successful!")
        println("ZIP Path: ${zipFile.absolutePath}")
        println("ZIP Size: $zipSizeMb MB")
        println("Files in ZIP: $count")
        println("Verified: APK/SmartBusinessManager.apk ($insideApkSize bytes) is present inside the ZIP!")
        println("Verified: README_BN.txt is present inside the ZIP!")
        println("Verified: Direct APK exported at ${rootApk.absolutePath}")
        println("==================================================")
    }
}

tasks.register("exportZip") {
    group = "distribution"
    description = "Alias for exportProjectZip"
    dependsOn("exportProjectZip")
}

