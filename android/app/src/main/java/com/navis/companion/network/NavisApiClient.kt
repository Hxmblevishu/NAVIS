package com.navis.companion.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.navis.companion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** REST client for the NAVIS FastAPI contract. */
class NavisApiClient(
    private val baseUrl: String,
    private val apiToken: String = BuildConfig.NAVIS_API_TOKEN
) {
    private val tag = "NavisAPI"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
            if (apiToken.isNotBlank()) builder.header("X-API-Token", apiToken)
            chain.proceed(builder.build())
        }
        .build()
    private val cameraClient = client.newBuilder()
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jpegMediaType = "image/jpeg".toMediaType()

    private fun url(path: String): String = "${baseUrl.trimEnd('/')}$path"

    private fun jsonRequest(path: String, value: Any): Request = Request.Builder()
        .url(url(path))
        .post(gson.toJson(value).toRequestBody(jsonMediaType))
        .build()

    private suspend fun <T> executeJson(request: Request, responseClass: Class<T>): T? =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        gson.fromJson(body, responseClass)
                    } else {
                        Log.e(tag, "${request.method} ${request.url.encodedPath} failed (HTTP ${response.code}): $body")
                        null
                    }
                }
            } catch (error: Exception) {
                Log.e(tag, "${request.method} ${request.url.encodedPath} failed: ${error.message}")
                null
            }
        }

    private suspend fun <T> get(path: String, responseClass: Class<T>): T? =
        executeJson(Request.Builder().url(url(path)).get().build(), responseClass)

    private suspend fun <T> postEmpty(path: String, responseClass: Class<T>): T? {
        // OkHttp refuses to build a multipart body with zero parts. The backend
        // treats the image field as optional, so send an ignored marker instead
        // and let it use the latest camera frame.
        val latestFrameForm = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("_navis_use_latest_frame", "true")
            .build()
        return executeJson(Request.Builder().url(url(path)).post(latestFrameForm).build(), responseClass)
    }

    suspend fun getHealth(): BackendHealth? = get("/health", BackendHealth::class.java)

    suspend fun getDeviceStatus(): DeviceStatus? = get("/api/device", DeviceStatus::class.java)

    suspend fun getDistanceReading(): DistanceReading? = get("/api/sensors/distance", DistanceReading::class.java)

    /** Fetch the latest JPEG frame from the backend's bounded camera buffer. */
    suspend fun getCameraSnapshot(): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("/api/camera/snapshot")).get().build()
        try {
            cameraClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes()
                else {
                    Log.d(tag, "Camera snapshot unavailable (HTTP ${response.code})")
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.d(tag, "Camera snapshot unavailable: ${error.message}")
            null
        }
    }

    suspend fun getLatestPerception(): LatestPerceptionResponse? =
        get("/api/perception/latest", LatestPerceptionResponse::class.java)

    suspend fun sendCommand(text: String, location: LocationCoordinate? = null): CommandResponse? =
        executeJson(jsonRequest("/api/command", CommandRequest(text = text, location = location)), CommandResponse::class.java)

    suspend fun resolveVoiceCommand(text: String): HybridVoiceCommand? =
        executeJson(
            jsonRequest("/api/voice/command", HybridVoiceCommandRequest(text)),
            HybridVoiceCommand::class.java
        )

    suspend fun sendVoiceAssistantQuery(question: String): VoiceCommandResponse? =
        executeJson(
            jsonRequest("/api/voice-command", VoiceCommandRequest(question = question)),
            VoiceCommandResponse::class.java
        )

    suspend fun streamVoiceAssistantQuery(
        question: String,
        responseStyle: String,
        history: List<Map<String, String>>?,
        onDelta: (String) -> Unit
    ): VoiceStreamResult = withContext(Dispatchers.IO) {
        val request = jsonRequest(
            "/api/voice-command/stream",
            VoiceCommandRequest(
                question = question,
                responseStyle = responseStyle,
                history = history
            )
        ).newBuilder()
            .header("Accept", "text/event-stream")
            .build()
        val call = client.newCall(request)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(tag, "POST ${request.url.encodedPath} failed (HTTP ${response.code})")
                    val error = if (response.code == 401) {
                        "The backend rejected its API token. Check Connected Devices & Backend settings."
                    } else {
                        "NAVIS could not start the live answer (HTTP ${response.code}). Please try again."
                    }
                    return@withContext VoiceStreamResult(completed = false, errorMessage = error)
                }

                val source = response.body?.source()
                    ?: return@withContext VoiceStreamResult(false, "The backend returned an empty answer stream.")
                var completed = false
                var streamError: String? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val encoded = line.removePrefix("data:").trim()
                    if (encoded.isBlank()) continue

                    val event = try {
                        JsonParser.parseString(encoded).asJsonObject
                    } catch (error: Exception) {
                        Log.w(tag, "Ignoring malformed voice stream event: ${error.javaClass.simpleName}")
                        continue
                    }
                    when (event.get("type")?.asString) {
                        "delta" -> event.get("text")?.asString?.let(onDelta)
                        "done" -> completed = true
                        "error" -> streamError = event.get("text")?.asString
                    }
                    if (streamError != null) break
                }

                if (completed) VoiceStreamResult(completed = true)
                else VoiceStreamResult(
                    completed = false,
                    errorMessage = streamError ?: "The live answer ended before it was complete. Please try again."
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.e(tag, "POST ${request.url.encodedPath} stream failed: ${error.message}")
            VoiceStreamResult(completed = false)
        } finally {
            cancellationHandle.dispose()
        }
    }

    suspend fun detectLatestFrame(): DetectionResponse? = postEmpty("/api/detect", DetectionResponse::class.java)

    suspend fun readTextFromCamera(): OcrResponse? = postEmpty("/api/ocr", OcrResponse::class.java)

    suspend fun analyzeCurrentScene(prompt: String = "Describe my surroundings concisely."): SceneAnalysisResponse? {
        val encodedPrompt = URLEncoder.encode(prompt, Charsets.UTF_8.name())
        return postEmpty("/api/analyze?prompt=$encodedPrompt", SceneAnalysisResponse::class.java)
    }

    suspend fun calculateRoute(destination: String, origin: LocationCoordinate): NavigationRouteResponse? =
        executeJson(
            jsonRequest(
                "/api/navigation/route",
                mapOf("destination" to destination, "origin" to origin, "mode" to "pedestrian")
            ),
            NavigationRouteResponse::class.java
        )

    suspend fun updateNavigationProgress(routeId: String, location: LocationCoordinate): NavigationProgressResponse? =
        executeJson(
            jsonRequest("/api/navigation/progress", mapOf("route_id" to routeId, "location" to location)),
            NavigationProgressResponse::class.java
        )

    /** Upload a JPEG frame for remote YOLO detection. */
    suspend fun detectImage(jpegBytes: ByteArray): DetectionResponse? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "camera-frame.jpg", jpegBytes.toRequestBody(jpegMediaType))
            .build()
        return executeJson(Request.Builder().url(url("/api/detect")).post(body).build(), DetectionResponse::class.java)
    }

    /** Upload a JPEG frame for the scene query endpoint. */
    suspend fun describeScene(
        jpegBytes: ByteArray,
        question: String = "Explain my surroundings"
    ): DescribeSceneResponse? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "navis-mobile-frame.jpg", jpegBytes.toRequestBody(jpegMediaType))
            .addFormDataPart("question", question)
            .build()
        return executeJson(Request.Builder().url(url("/api/describe-scene")).post(body).build(), DescribeSceneResponse::class.java)
    }
}
