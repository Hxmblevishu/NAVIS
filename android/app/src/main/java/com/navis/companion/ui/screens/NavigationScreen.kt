package com.navis.companion.ui.screens

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.view.View
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import com.navis.companion.ui.viewmodel.NavisUiState

@Composable
fun NavigationScreen(
    uiState: NavisUiState,
    onBack: () -> Unit,
    onStartNavigation: (String) -> Unit
) {
    var destination by remember { mutableStateOf(uiState.destinationName) }
    LaunchedEffect(uiState.destinationName) { destination = uiState.destinationName }
    val hasRoute = uiState.navigationRouteId != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Column {
                Text("Pedestrian navigation", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Dehradun, Uttarakhand", fontSize = 12.sp, color = NavisTextSecondary)
            }
        }

        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Destination") },
            placeholder = { Text("Enter a place or address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .clip(RoundedCornerShape(20.dp))
                .semantics { contentDescription = "Interactive pedestrian map" }
        ) {
            NavigationMapView(
                uiState = uiState,
                modifier = Modifier.fillMaxSize()
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsWalk, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasRoute) "Walking route" else "Map is ready", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text(
                    if (hasRoute) "${uiState.remainingDistance} • ${uiState.remainingTime}  ·  ${uiState.nextTurnInstruction}"
                    else uiState.mapLocationMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.navigationStatusMessage != "No pedestrian route has been requested.") {
                    Text(uiState.navigationStatusMessage, fontSize = 12.sp, color = NavisTextSecondary)
                }
                uiState.routeAttribution?.let { Text(it, fontSize = 10.sp, color = NavisTextSecondary) }
            }
        }

        Button(
            onClick = { onStartNavigation(destination) },
            enabled = destination.trim().length >= 2 && !uiState.navigationActive,
            modifier = Modifier.fillMaxWidth().height(52.dp).semantics {
                contentDescription = "Request walking route to $destination"
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NavisBlack, contentColor = NavisWhite)
        ) {
            Text(if (uiState.navigationActive) "Navigation active" else "Calculate walking route", fontSize = 16.sp)
        }
    }
}

@Composable
private fun NavigationMapView(uiState: NavisUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mapStatus by remember { mutableStateOf("loading") }
    val gson = remember { Gson() }
    val currentLocation = uiState.currentLocation?.let {
        mapOf("latitude" to it.latitude, "longitude" to it.longitude)
    }
    val mapStateJson = remember(currentLocation, uiState.navigationPolyline) {
        gson.toJson(mapOf("location" to currentLocation, "polyline" to uiState.navigationPolyline))
    }
    val latestMapStateJson by rememberUpdatedState(mapStateJson)
    val html = remember {
        val page = context.assets.open("navigation_map.html").bufferedReader().use { it.readText() }
        val css = context.assets.open("leaflet.css").bufferedReader().use { it.readText() }
        val js = context.assets.open("leaflet.js").bufferedReader().use { it.readText() }
        page.replace("__LEAFLET_CSS__", css).replace("__LEAFLET_JS__", js)
    }
    val webViewState = remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { viewContext ->
            WebView(viewContext).apply {
            setBackgroundColor(Color.rgb(231, 237, 232))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.userAgentString = "${settings.userAgentString} NAVIS-Companion/1.0.7"
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    Log.i("NavisMap", "Map document loading")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.i("NavisMap", "Map document finished loading")
                    view.tag = true
                    view.evaluateJavascript(
                        "JSON.stringify({leaflet:typeof window.L, updater:typeof window.navisSetState, ready:document.readyState})"
                    ) { result -> Log.i("NavisMap", "Map JS state: $result") }
                    view.evaluateJavascript("window.navisSetState(${latestMapStateJson})", null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) Log.e("NavisMap", "Map document failed: ${error.description}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.e("NavisMap", "Map JavaScript: ${consoleMessage.message()} (${consoleMessage.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onStatus(status: String) {
                    Handler(Looper.getMainLooper()).post { mapStatus = status }
                }
            }, "NavisMapBridge")
            webViewState.value = this
            loadDataWithBaseURL(
                "https://appassets.androidplatform.net/assets/navigation_map.html",
                html,
                "text/html",
                "UTF-8",
                null
            )
        }
        },
        modifier = modifier,
        update = { view ->
            if (view.tag == true) view.evaluateJavascript("window.navisSetState($mapStateJson)", null)
            else view.tag = mapStateJson
        }
    )
    if (mapStatus != "ready") {
        val message = when (mapStatus) {
            "offline" -> "Map tiles are offline. Check the phone’s internet connection."
            "loading" -> "Loading the walking map…"
            else -> "Opening the walking map…"
        }
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            ) {
                Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 13.sp)
            }
        }
    }
    DisposableEffect(webViewState.value) {
        val webView = webViewState.value
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}
