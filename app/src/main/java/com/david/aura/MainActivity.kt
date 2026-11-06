package com.david.aura

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Aura.init(this)
        WebService.start(this)
        setContent { AuraHome() }
    }

    override fun onPause() {
        Remote.auraInFront = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Remote.auraInFront = true
        // Recarga la lista de apps por si se instalo/desinstalo algo, y vuelve el foco a la primera app
        Aura.appsVersion.value++
        Aura.resumeTick.value++
    }
}
