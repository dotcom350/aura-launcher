package com.david.aura

/**
 * Pantallas de los ajustes del Fire TV que Aura puede abrir (desde la TV y desde la web).
 * Acciones comprobadas en Fire OS 8 (AFTKM): las resuelve com.amazon.tv.settings.v2.
 */
object FireSettings {
    class Page(val id: String, val action: String, val icon: Int, val es: String, val en: String, val subEs: String, val subEn: String) {
        fun title(lang: String) = if (lang == "en") en else es
        fun sub(lang: String) = if (lang == "en") subEn else subEs
    }

    val PAGES = listOf(
        Page("network", "android.settings.WIFI_SETTINGS", R.drawable.ri_wifi,
            "Red y Wi-Fi", "Network & Wi-Fi", "Conectar a otra red", "Join another network"),
        Page("display", "android.settings.DISPLAY_SETTINGS", R.drawable.ri_display,
            "Pantalla y sonido", "Display & sound", "Resolución, HDR, audio, HDMI-CEC", "Resolution, HDR, audio, HDMI-CEC"),
        Page("apps", "android.settings.APPLICATION_SETTINGS", R.drawable.ri_grid,
            "Aplicaciones", "Applications", "Gestionar, borrar datos, desinstalar", "Manage, clear data, uninstall"),
        Page("controllers", "android.settings.CONTROLLERS_SETTINGS", R.drawable.ri_gamepad,
            "Mandos y Bluetooth", "Controllers & Bluetooth", "Emparejar mandos, auriculares…", "Pair remotes, headphones…"),
        Page("preferences", "com.amazon.device.settings.action.PREFERENCES", R.drawable.ri_sliders,
            "Preferencias", "Preferences", "Privacidad, idioma, zona horaria", "Privacy, language, time zone"),
        Page("accessibility", "com.amazon.device.settings.action.ACCESSIBILITY", R.drawable.ri_accessibility,
            "Accesibilidad", "Accessibility", "Subtítulos, texto, VoiceView", "Captions, text, VoiceView"),
        Page("device", "android.settings.DEVICE_INFO_SETTINGS", R.drawable.ri_information,
            "Mi Fire TV", "My Fire TV", "Información, actualizaciones, reiniciar", "About, updates, restart"),
        Page("all", "android.settings.SETTINGS", R.drawable.ri_settings,
            "Todos los ajustes", "All settings", "Menú completo de Amazon", "Full Amazon menu"),
    )

    fun byId(id: String?) = PAGES.firstOrNull { it.id == id }
}
