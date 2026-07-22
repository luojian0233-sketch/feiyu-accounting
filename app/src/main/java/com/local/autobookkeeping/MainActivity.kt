package com.local.autobookkeeping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.autobookkeeping.ui.navigation.AppDestination

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutoBookkeepingRoot() }
    }
}

@Composable
fun AutoBookkeepingRoot() {
    var current by rememberSaveable { mutableStateOf(AppDestination.HOME) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { current = destination },
                        modifier = if (destination == AppDestination.ENTRY) {
                            Modifier.semantics { contentDescription = "\u7471\u9881\u7AF4\u7ED7?" }
                        } else {
                            Modifier
                        },
                        icon = {
                            Text(if (destination == AppDestination.ENTRY) "\u951B?" else destination.label.take(1))
                        },
                        label = {
                            if (destination != AppDestination.ENTRY) {
                                Text(destination.label)
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag("screen-${current.label}"),
        ) {
            Text(
                text = current.label,
                modifier = Modifier.padding(20.dp),
                fontSize = 20.sp,
            )
        }
    }
}
