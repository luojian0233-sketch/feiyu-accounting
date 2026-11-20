package com.local.autobookkeeping.data.update

/**
 * 一个 APK（或已安装应用）的身份信息，只保留覆盖安装真正用得到的几项。
 *
 * [signerSha256] 取不到时为 null（极老的包或读取失败），这时不做签名比对，
 * 交给系统安装器自己拒绝——宁可少拦一次，也不要因为读不到证书挡住正常升级。
 */
data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val signerSha256: String? = null,
)

/** 本地安装包为什么不能装。 */
enum class LocalUpdateRejection {
    /** 包名不是本应用。 */
    ForeignPackage,

    /** 签名证书与已安装版本不同，系统会拒绝覆盖。 */
    SignatureMismatch,

    /** 版本号低于已安装版本，装上去等于降级。 */
    Downgrade,
}

sealed interface LocalUpdateVerdict {
    /**
     * 可以交给系统安装器。[replacesSameVersion] 为 true 表示同版本覆盖，
     * 用途是修复安装，界面上的措辞要跟着变。
     */
    data class Installable(
        val versionName: String,
        val versionCode: Int,
        val replacesSameVersion: Boolean,
    ) : LocalUpdateVerdict

    data class Rejected(
        val reason: LocalUpdateRejection,
        val message: String,
    ) : LocalUpdateVerdict
}

/**
 * 决定选中的 APK 能不能装。
 *
 * 顺序是有讲究的：先认包名（选错文件是最常见的误操作），再认签名
 * （换了签名的包系统必拒，提前说清楚比让用户看着安装失败好），最后比版本。
 * 同版本放行——覆盖安装是修复坏装的唯一办法。
 */
fun judgeLocalApk(candidate: ApkIdentity, installed: ApkIdentity): LocalUpdateVerdict {
    if (candidate.packageName != installed.packageName) {
        return LocalUpdateVerdict.Rejected(
            reason = LocalUpdateRejection.ForeignPackage,
            message = "这不是肥鱼记账的安装包（${candidate.packageName}）",
        )
    }
    val candidateSigner = candidate.signerSha256
    val installedSigner = installed.signerSha256
    if (candidateSigner != null && installedSigner != null &&
        !candidateSigner.equals(installedSigner, ignoreCase = true)
    ) {
        return LocalUpdateVerdict.Rejected(
            reason = LocalUpdateRejection.SignatureMismatch,
            message = "安装包签名与已安装版本不一致，无法覆盖安装",
        )
    }
    if (candidate.versionCode < installed.versionCode) {
        return LocalUpdateVerdict.Rejected(
            reason = LocalUpdateRejection.Downgrade,
            message = "安装包比已安装版本更旧（${candidate.versionName} < ${installed.versionName}）",
        )
    }
    return LocalUpdateVerdict.Installable(
        versionName = candidate.versionName,
        versionCode = candidate.versionCode,
        replacesSameVersion = candidate.versionCode == installed.versionCode,
    )
}
