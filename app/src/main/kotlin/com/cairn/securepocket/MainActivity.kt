package com.cairn.securepocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cairn.securepocket.ui.AppRoot
import com.cairn.securepocket.ui.SecurePocketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SecurePocketTheme {
                AppRoot()
            }
        }
    }
}
