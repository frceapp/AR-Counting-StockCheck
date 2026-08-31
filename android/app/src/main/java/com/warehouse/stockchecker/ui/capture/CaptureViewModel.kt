package com.warehouse.stockchecker.ui.capture

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warehouse.stockchecker.camera.CaptureGate
import com.warehouse.stockchecker.camera.DetectionAnalyzer
import com.warehouse.stockchecker.camera.OrientationProvider
import com.warehouse.stockchecker.ml.Detection
import com.warehouse.stockchecker.ml.PartDetector
import com.warehouse.stockchecker.session.WorldAnchorSnapshot
import com.warehouse.stockchecker.tracking.WorldAnchorTracker
import com.warehouse.stockchecker.tracking.WorldTrackedDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs [CaptureFragment]. Owns the model + tracker + orientation sensor and exposes the
 * current state of the world as [LiveData] so the fragment only has to render.
 *
 * The heavy objects are created on a background coroutine; UI just observes [state].
 */
class CaptureViewModel : ViewModel() {

    /**
     * One screen state, immutable from the outside. The fragment renders the latest value and
     * never mutates it.
     */
    data class State(
        val modelStatus: ModelStatus = ModelStatus.Loading,
        val capturing: Boolean = false,
        val anchors: List<WorldTrackedDetection> = emptyList(),
        val inferenceTimeMs: Long = 0L,
        val bestScore: Float = 0f,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val errorMessage: String? = null
    )

    sealed class ModelStatus {
        data object Loading : ModelStatus()
        data object Ready : ModelStatus()
        data class Failed(val cause: String) : ModelStatus()
    }

    private val _state = MutableLiveData(State())
    val state: LiveData<State> = _state

    private var detector: PartDetector? = null
    private val tracker = WorldAnchorTracker()
    private val gate = CaptureGate()
    val orientationProvider: OrientationProvider
        get() = orientationProviderInternal

    /**
     * Held weakly by the analyzer; only the fragment ever calls [orientationProvider]. Wrapping
     * in a property keeps construction here so the fragment does not see it before the model
     * is loaded.
     */
    private lateinit var orientationProviderInternal: OrientationProvider

    /**
     * Builds the analyzer + detector. Idempotent — second call is a no-op so a config change
     * does not re-load the model.
     */
    fun ensureLoaded(context: android.content.Context, onReady: (DetectionAnalyzer) -> Unit) {
        if (detector != null) return
        // Orientation listening is cheap and independent of model load, so start it the moment
        // the provider exists — otherwise the very first burst is captured with orientation
        // UNKNOWN and every initial anchor is glued to the captured pixel rectangle.
        if (!::orientationProviderInternal.isInitialized) {
            orientationProviderInternal = OrientationProvider(context).also { it.start() }
        }
        viewModelScope.launch {
            val partDetector = withContext(Dispatchers.IO) {
                runCatching { PartDetector.create(context) }
            }
            partDetector.fold(
                onSuccess = { detector ->
                    this@CaptureViewModel.detector = detector
                    val analyzer = DetectionAnalyzer(
                        detector = detector,
                        tracker = tracker,
                        gate = gate,
                        orientationProvider = orientationProviderInternal
                    ) { result -> publish(result) }
                    _state.postValue(_state.value!!.copy(modelStatus = ModelStatus.Ready))
                    onReady(analyzer)
                },
                onFailure = { error ->
                    _state.postValue(
                        _state.value!!.copy(
                            modelStatus = ModelStatus.Failed(error.message ?: "load failed")
                        )
                    )
                }
            )
        }
    }

    /** Called by the fragment when the screen becomes visible. */
    fun startOrientation() {
        if (::orientationProviderInternal.isInitialized) orientationProviderInternal.start()
    }

    /** Called by the fragment when the screen is paused. */
    fun stopOrientation() {
        if (::orientationProviderInternal.isInitialized) orientationProviderInternal.stop()
    }

    /** Arms a capture burst. The next few frames will reach the model. */
    fun startCapture() {
        if (detector == null) return
        gate.arm()
        _state.postValue(_state.value!!.copy(capturing = true))
    }

    /** Wipes every anchor. */
    fun clearAnchors() {
        gate.cancel()
        tracker.reset()
        _state.postValue(_state.value!!.copy(anchors = emptyList()))
    }

    /**
     * Builds a snapshot list suitable for handing to [com.warehouse.stockchecker.session.SessionRepository].
     * Returns an empty list when the model has not produced any anchors yet.
     */
    fun snapshot(): List<WorldAnchorSnapshot> = _state.value?.anchors?.map(::toSnapshot).orEmpty()

    private fun publish(result: DetectionAnalyzer.AnalysisResult) {
        val current = _state.value ?: State()
        _state.postValue(
            current.copy(
                capturing = result.capturing,
                anchors = result.tracks,
                inferenceTimeMs = result.inferenceTimeMs,
                // Idle re-projection publishes come with frameWidth=0 to signal "no new model
                // output". Keep the last real dimensions so the overlay keeps mapping boxes.
                frameWidth = if (result.frame.frameWidth > 0) result.frame.frameWidth else current.frameWidth,
                frameHeight = if (result.frame.frameHeight > 0) result.frame.frameHeight else current.frameHeight,
                bestScore = result.bestScore
            )
        )
    }

    private fun toSnapshot(track: WorldTrackedDetection) = WorldAnchorSnapshot(
        trackId = track.trackId,
        label = track.detection.label,
        classId = track.detection.classId,
        score = track.detection.score,
        hits = track.hits,
        boxLeft = track.box.left,
        boxTop = track.box.top,
        boxRight = track.box.right,
        boxBottom = track.box.bottom
    )

    override fun onCleared() {
        super.onCleared()
        if (::orientationProviderInternal.isInitialized) orientationProviderInternal.stop()
        detector?.close()
        detector = null
    }

    // The model exposes this for diagnostic logging. We keep the helper here so callers do not
    // need to reach into the (cleared) detector field.
    @Suppress("unused")
    fun lastFrameBitmap(): Bitmap? = null
}
