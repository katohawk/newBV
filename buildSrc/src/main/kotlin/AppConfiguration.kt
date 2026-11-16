/**
 * 应用全局配置。
 *
 * 集中管理包名、SDK 版本、版本号等构建配置，
 * 供各模块 build.gradle.kts 引用。
 */
object AppConfiguration {
    const val appId = "dev.frost819.newbv"
    const val applicationId = "dev.frost819.newbv"
    const val compileSdk = 36
    const val minSdk = 23
    const val targetSdk = 36

    /** 对外显示的固定版本号。 */
    const val versionName = "1.0.0"

    val versionCode: Int
        get() = "git rev-list --count HEAD".exec().toIntOrNull() ?: 1
}

fun String.exec() = String(Runtime.getRuntime().exec(this).inputStream.readBytes()).trim()
