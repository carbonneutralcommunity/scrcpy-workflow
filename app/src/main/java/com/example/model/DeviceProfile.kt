package com.example.model

import android.os.Build

data class DeviceProfile(
    val name: String,
    val manufacturer: String,
    val checkboxViewIds: List<String>,
    val allowButtonViewIds: List<String>,
    val checkboxTexts: List<String>,
    val allowButtonTexts: List<String>,
    val targetPackages: Set<String>,
    val notes: String
)

object DeviceProfileResolver {

    private val MULTI_LANG_CHECKBOX_TEXTS = listOf(
        "always allow",
        "always allow from this computer",
        "toujours autoriser",
        "toujours autoriser sur cet ordinateur",
        "immer zulassen",
        "von diesem computer immer zulassen",
        "permitir siempre",
        "permitir siempre desde esta computadora",
        "consenti sempre",
        "consenti sempre da questo computer",
        "sempre permitir",
        "sempre permitir a partir deste computador",
        "всегда разрешать",
        "всегда разрешать с этого компьютера",
        "常に許可",
        "このパソコンからの接続を常に許可する",
        "항상 허용",
        "이 컴퓨터에서 항상 허용",
        "始终允许",
        "始终允许使用这台计算机进行调试",
        "一律允許",
        "永遠允許透過這部電腦進行調試",
        "總是允許"
    )

    private val MULTI_LANG_ALLOW_BUTTON_TEXTS = listOf(
        "allow",
        "ok",
        "autoriser",
        "zulassen",
        "permitir",
        "consenti",
        "разрешить",
        "ок",
        "許可",
        "허용",
        "확인",
        "允许",
        "確定",
        "确定"
    )

    val SONY_PROFILE = DeviceProfile(
        name = "Sony Xperia (Near-Stock)",
        manufacturer = "sony",
        checkboxViewIds = listOf(
            "android:id/alwaysUse",
            "com.android.systemui:id/alwaysUse",
            "com.android.settings:id/alwaysUse",
            "alwaysUse"
        ),
        allowButtonViewIds = listOf(
            "android:id/button1",
            "com.android.systemui:id/button1",
            "button1"
        ),
        checkboxTexts = MULTI_LANG_CHECKBOX_TEXTS,
        allowButtonTexts = MULTI_LANG_ALLOW_BUTTON_TEXTS,
        targetPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.sonymobile.systemui"
        ),
        notes = "Sony Xperia XZ3 (Android 10, API 29): Uses near-stock AOSP system dialogs. " +
                "Note: 'Disable adb authorization timeout' is only available on Android 11+ (API 30+)."
    )

    val SAMSUNG_PROFILE = DeviceProfile(
        name = "Samsung Galaxy (One UI)",
        manufacturer = "samsung",
        checkboxViewIds = listOf(
            "android:id/alwaysUse",
            "com.android.systemui:id/alwaysUse",
            "com.android.systemui:id/checkbox",
            "com.samsung.android.systemui:id/alwaysUse",
            "alwaysUse",
            "checkbox"
        ),
        allowButtonViewIds = listOf(
            "android:id/button1",
            "com.android.systemui:id/button1",
            "button1"
        ),
        checkboxTexts = MULTI_LANG_CHECKBOX_TEXTS,
        allowButtonTexts = MULTI_LANG_ALLOW_BUTTON_TEXTS,
        targetPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.samsung.android.settings",
            "com.samsung.android.systemui"
        ),
        notes = "Samsung One UI (One UI 6, 7 & 8 / Android 14, 15, 16): " +
                "Auto Blocker (Settings > Security and privacy > Auto Blocker) blocks USB commands if enabled. " +
                "Restricted Settings must be allowed for Accessibility on Android 13+. " +
                "Battery must be set to 'Unrestricted'."
    )

    val GENERIC_PROFILE = DeviceProfile(
        name = "Generic AOSP / Pixel",
        manufacturer = "generic",
        checkboxViewIds = listOf(
            "android:id/alwaysUse",
            "com.android.systemui:id/alwaysUse",
            "alwaysUse"
        ),
        allowButtonViewIds = listOf(
            "android:id/button1",
            "com.android.systemui:id/button1",
            "button1"
        ),
        checkboxTexts = MULTI_LANG_CHECKBOX_TEXTS,
        allowButtonTexts = MULTI_LANG_ALLOW_BUTTON_TEXTS,
        targetPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings"
        ),
        notes = "Standard Android AOSP profile with fallback multi-language matching and coordinate tap dispatch."
    )

    fun resolve(): DeviceProfile {
        val mfg = Build.MANUFACTURER.lowercase()
        return when {
            mfg.contains("sony") -> SONY_PROFILE
            mfg.contains("samsung") -> SAMSUNG_PROFILE
            else -> GENERIC_PROFILE
        }
    }
}
