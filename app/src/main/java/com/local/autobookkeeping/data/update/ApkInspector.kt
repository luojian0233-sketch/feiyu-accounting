package com.local.autobookkeeping.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 读 APK 或已安装应用的 [ApkIdentity]。
 *
 * 只做读取，不安装、不写盘——复制到缓存与调起安装器是调用方的事。
 * 读不到（损坏的包、截断的下载）统一返回 null，由调用方提示重新选文件。
 */
object ApkInspector {

    /** 读磁盘上的 APK 文件。[file] 必须是真实路径，SAF 给的 content:// 要先复制出来。 */
    fun inspect(context: Context, file: File): ApkIdentity? = runCatching {
        val info = archiveInfo(context, file.absolutePath) ?: return null
        info.applicationInfo?.apply {
            // 没装过的包，系统不知道它的路径；补上后图标、标签这类信息才读得到。
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        identityOf(info)
    }.getOrNull()

    /** 读本机已安装的肥鱼记账。 */
    fun installed(context: Context): ApkIdentity? = runCatching {
        identityOf(installedInfo(context))
    }.getOrNull()

    private fun installedInfo(context: Context): PackageInfo {
        val manager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(READ_FLAGS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, READ_FLAGS)
        }
    }

    private fun archiveInfo(context: Context, path: String): PackageInfo? {
        val manager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(READ_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(path, READ_FLAGS)
        }
    }

    private fun identityOf(info: PackageInfo): ApkIdentity = ApkIdentity(
        packageName = info.packageName.orEmpty(),
        versionName = info.versionName.orEmpty(),
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        },
        signerSha256 = signerDigest(info),
    )

    /** 取第一枚签名证书的 SHA-256 十六进制串，与 `apksigner verify` 打印的摘要可比对。 */
    private fun signerDigest(info: PackageInfo): String? {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** API 28 起改用 signingInfo，更早的系统只有 signatures。 */
    private val READ_FLAGS: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
}
