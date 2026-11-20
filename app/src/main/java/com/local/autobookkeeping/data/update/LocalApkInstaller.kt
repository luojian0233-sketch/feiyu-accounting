package com.local.autobookkeeping.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「从本地安装包更新」的执行端。
 *
 * 文件选择器给的是 content://，系统安装器只认文件，所以先复制到 `cacheDir/update/`，
 * 校验通过后用 FileProvider 把它换成一个带临时授权的 content:// 交给安装器。
 * 整个流程不需要任何存储权限，也不需要把 APK 落到用户可以长期访问的地方。
 */
object LocalApkInstaller {

    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    sealed interface Prepared {
        data class Installable(
            val apk: File,
            val versionName: String,
            val versionCode: Int,
            val replacesSameVersion: Boolean,
        ) : Prepared

        /** 包名、签名或版本不满足覆盖安装条件。 */
        data class Rejected(val message: String) : Prepared

        /** 文件读不到、不是合法 APK 之类的意外。 */
        data class Failed(val message: String) : Prepared
    }

    /** 系统是否允许本应用安装未知来源的应用（Android 8.0 起是每个应用单独授权）。 */
    fun canInstallPackages(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    /** 跳到「安装未知应用」里给本应用开权限。 */
    fun unknownSourcesSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 把选中的文件复制出 cache，读出包信息并与已安装版本比对。
     *
     * 上一轮的包在这里删掉：安装过程中的 APK 一旦被覆盖或删除，系统会直接装失败。
     */
    suspend fun prepare(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        try {
            val directory = File(context.cacheDir, "update")
            directory.listFiles()?.forEach { it.delete() }
            if (!directory.exists() && !directory.mkdirs()) {
                Prepared.Failed("无法创建缓存目录")
            } else {
                val target = File(directory, "feiyu-local-update.apk")
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    Prepared.Failed("无法打开所选文件")
                } else {
                    input.use { source ->
                        target.outputStream().use { output -> source.copyTo(output) }
                    }
                    val candidate = ApkInspector.inspect(context, target)
                    val installed = ApkInspector.installed(context)
                    when {
                        candidate == null -> {
                            target.delete()
                            Prepared.Failed("这不像一个完整的 APK 安装包")
                        }
                        installed == null -> {
                            target.delete()
                            Prepared.Failed("读不到已安装版本的信息")
                        }
                        else -> when (val verdict = judgeLocalApk(candidate, installed)) {
                            is LocalUpdateVerdict.Rejected -> {
                                target.delete()
                                Prepared.Rejected(verdict.message)
                            }
                            is LocalUpdateVerdict.Installable -> Prepared.Installable(
                                apk = target,
                                versionName = verdict.versionName,
                                versionCode = verdict.versionCode,
                                replacesSameVersion = verdict.replacesSameVersion,
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Prepared.Failed("读取所选文件失败：${error.message ?: "未知错误"}")
        }
    }

    /** 交给系统安装器。调用方负责 startActivity，并处理没有安装器的情况。 */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.localupdate",
            apk,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
