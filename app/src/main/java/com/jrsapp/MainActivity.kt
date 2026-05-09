package com.jrsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jrsapp.navigation.AppNavGraph
import com.jrsapp.ui.theme.JrsAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JrsAppTheme {
                AppNavGraph()
            }
        }
    }
}
