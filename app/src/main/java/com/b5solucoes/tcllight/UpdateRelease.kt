package com.b5solucoes.tcllight

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class UpdateFailure(message: String) : Exception(message)

internal data class UpdateRelease(val versionName: String, val versionCode: Long,
    val apkUrl: String, val size: Long, val sha256: String, val minSdk: Int,
    val certificate: String)

internal data class InstalledUpdateApp(val packageName: String, val versionCode: Long,
    val certificate: String)

/** Public release metadata is not authority to change the app's package or signing key. */
internal object UpdatePolicy {
    const val PACKAGE = "com.b5solucoes.tcllight"
    const val REPOSITORY = "https://github.com/emanoelb5-lgtm/Tvtcl"
    const val FEED = "$REPOSITORY/releases/download/beta/TCL-Light-atualizacao.json"
    const val CERTIFICATE = "21e8b079624ca39fcb84817f2a711c46e474d2ccdb65f6e629e26fe153fdb688"
    const val MAX_APK_SIZE = 64L * 1024L * 1024L
    const val MAX_FEED_SIZE = 16 * 1024

    fun parse(json: String): UpdateRelease {
        try {
            if (json.toByteArray(Charsets.UTF_8).size > MAX_FEED_SIZE) throw IllegalArgumentException()
            val obj = JSONObject(json)
            for (field in listOf("schema", "version_code", "size_bytes", "min_sdk"))
                require(obj.get(field) is Int || obj.get(field) is Long)
            val name = obj.getString("version_name")
            val code = obj.getLong("version_code")
            val url = obj.getString("apk_url")
            val size = obj.getLong("size_bytes")
            val hash = obj.getString("sha256").lowercase(java.util.Locale.ROOT)
            val cert = obj.getString("certificate_sha256").lowercase(java.util.Locale.ROOT)
            val min = obj.getInt("min_sdk")
            require(obj.getInt("schema") == 1 && obj.getString("package") == PACKAGE)
            require(name.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")))
            require(code in 1..Int.MAX_VALUE.toLong() && size in 1..MAX_APK_SIZE && min in 23..100)
            require(hash.matches(Regex("[0-9a-f]{64}")) && cert == CERTIFICATE)
            require(url == "$REPOSITORY/releases/download/v$name/TCL-Light-$name.apk")
            return UpdateRelease(name, code, url, size, hash, min, cert)
        } catch (_: Exception) {
            throw UpdateFailure("As informações da atualização não puderam ser confirmadas. Tente novamente mais tarde.")
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal object UpdateApkVerifier {
    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certificate(info: PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners ?: info.signatures
            else info.signatures
        if (signatures == null || signatures.size != 1) return ""
        return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun installed(context: Context): InstalledUpdateApp {
        val info = context.packageManager.getPackageInfo(context.packageName, flags())
        return InstalledUpdateApp(info.packageName, code(info), certificate(info))
    }

    @Suppress("DEPRECATION")
    fun verify(manager: PackageManager, file: File, release: UpdateRelease,
               installed: InstalledUpdateApp) {
        if (installed.packageName != UpdatePolicy.PACKAGE || installed.certificate != UpdatePolicy.CERTIFICATE)
            throw UpdateFailure("Esta instalação não usa a assinatura oficial. Instale a versão oficial do TCL Light para receber atualizações.")
        if (release.certificate != UpdatePolicy.CERTIFICATE || release.versionCode <= installed.versionCode)
            throw UpdateFailure("Esta atualização não é mais recente que a versão instalada.")
        if (!file.isFile || file.length() != release.size || UpdatePolicy.sha256(file) != release.sha256)
            throw UpdateFailure("O download está incompleto ou foi alterado. Baixe a atualização novamente.")
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags())
            ?: throw UpdateFailure("O arquivo baixado não é um aplicativo válido.")
        val app = archive.applicationInfo
        if (archive.packageName != UpdatePolicy.PACKAGE)
            throw UpdateFailure("O arquivo baixado não é do TCL Light.")
        if (code(archive) != release.versionCode || archive.versionName != release.versionName)
            throw UpdateFailure("O arquivo baixado não corresponde à versão anunciada.")
        if (certificate(archive) != UpdatePolicy.CERTIFICATE)
            throw UpdateFailure("A assinatura da atualização não é a oficial do TCL Light.")
        if (app == null || app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            throw UpdateFailure("O arquivo baixado não é uma versão de distribuição do TCL Light.")
        if (Build.VERSION.SDK_INT >= 24 && app.minSdkVersion > Build.VERSION.SDK_INT)
            throw UpdateFailure("A atualização não é compatível com o Android desta TV.")
    }
}
