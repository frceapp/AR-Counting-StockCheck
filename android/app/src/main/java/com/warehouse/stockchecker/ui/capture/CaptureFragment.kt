package com.warehouse.stockchecker.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.warehouse.stockchecker.StockCheckApp
import com.warehouse.stockchecker.R
import com.warehouse.stockchecker.camera.DetectionAnalyzer
import com.warehouse.stockchecker.databinding.FragmentCaptureBinding
import com.warehouse.stockchecker.session.SessionRepository
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera screen.
 *
 * Responsibilities:
 *  * ask for CAMERA permission and show a rationale when it's missing,
 *  * bind the CameraX preview + analysis pipeline,
 *  * render the latest world-anchored boxes onto [com.warehouse.stockchecker.ui.DetectionOverlayView],
 *  * arm a Capture burst on shutter tap,
 *  * export the current anchors as a session JSON via [SessionRepository].
 */
class CaptureFragment : Fragment() {

    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CaptureViewModel by viewModels()

    /** Single-thread executor; matches the non-thread-safe detector + tracker. */
    private lateinit var analysisExecutor: ExecutorService

    private val anchorAdapter = AnchorAdapter()
    private val sessionRepository: SessionRepository by lazy {
        (requireActivity().application as StockCheckApp).sessionRepository
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showPermissionPrompt(false)
            startDetection()
        } else {
            binding.permissionMessage.setText(R.string.camera_permission_denied)
            showPermissionPrompt(true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.anchorsList.layoutManager = LinearLayoutManager(requireContext())
        binding.anchorsList.adapter = anchorAdapter
        binding.anchorsList.itemAnimator = null

        binding.shutterButton.setOnClickListener { onShutterTapped() }
        binding.saveFab.setOnClickListener { promptSaveSession() }
        binding.clearFab.setOnClickListener { onClearTapped() }
        binding.grantButton.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        if (hasCameraPermission()) {
            showPermissionPrompt(false)
            startDetection()
        } else {
            showPermissionPrompt(true)
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
    }

    override fun onResume() {
        super.onResume()
        // Resume the orientation sensor so world-anchored boxes re-project as the camera moves.
        viewModel.startOrientation()
    }

    override fun onPause() {
        super.onPause()
        // Sensor stays on while the screen is in front of the user, off when it isn't.
        viewModel.stopOrientation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release camera; the executor is shut down so the analyzer cannot race a stale closure.
        ProcessCameraProvider.getInstance(requireContext()).get().unbindAll()
        analysisExecutor.shutdown()
        analysisExecutor = Executors.newSingleThreadExecutor()
        _binding = null
    }

    // -- camera plumbing --

    private fun startDetection() {
        viewModel.ensureLoaded(requireContext()) { analyzer ->
            bindCameraUseCases(analyzer)
        }
    }

    private fun bindCameraUseCases(analyzer: DetectionAnalyzer) {
        val view = _binding ?: return
        lifecycleScope.launch {
            val provider = withContext(Dispatchers.IO) {
                runCatching {
                    ProcessCameraProvider.getInstance(requireContext()).get()
                }
            }.getOrElse { return@launch }

            // Same aspect ratio for preview + analysis so overlay boxes line up.
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setSurfaceProvider(view.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (_: Throwable) {
                // Camera failure is non-fatal: the rest of the UI still works, just without a
                // live preview. The status pill already reflects model readiness.
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionPrompt(visible: Boolean) {
        val view = _binding ?: return
        view.permissionGroup.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // -- input handling --

    private fun onShutterTapped() {
        val state = viewModel.state.value ?: return
        when (state.modelStatus) {
            CaptureViewModel.ModelStatus.Loading -> return
            is CaptureViewModel.ModelStatus.Failed -> return
            CaptureViewModel.ModelStatus.Ready -> {
                viewModel.startCapture()
                animateShutterPulse()
            }
        }
    }

    private fun onClearTapped() {
        viewModel.clearAnchors()
        anchorAdapter.submitList(emptyList())
    }

    private fun promptSaveSession() {
        val anchors = viewModel.snapshot()
        if (anchors.isEmpty()) {
            Snackbar.make(binding.root, R.string.session_save_need_anchors, Snackbar.LENGTH_SHORT).show()
            return
        }
        val frameWidth = viewModel.state.value?.frameWidth ?: 0
        val frameHeight = viewModel.state.value?.frameHeight ?: 0
        val inferenceMs = viewModel.state.value?.inferenceTimeMs ?: 0L

        val container = FrameLayout(requireContext()).apply {
            val pad = resources.getDimensionPixelSize(R.dimen.dialog_field_padding)
            setPadding(pad, pad, pad, 0)
        }
        val input = EditText(requireContext()).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.session_dialog_hint)
            setSingleLine()
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.session_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.session_dialog_save) { _, _ ->
                performSave(input.text?.toString(), anchors, frameWidth, frameHeight, inferenceMs)
            }
            .setNegativeButton(R.string.session_dialog_cancel, null)
            .show()
    }

    private fun performSave(
        name: String?,
        anchors: List<com.warehouse.stockchecker.session.WorldAnchorSnapshot>,
        frameWidth: Int,
        frameHeight: Int,
        inferenceMs: Long
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sessionRepository.save(
                        anchors = anchors,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        inferenceTimeMs = inferenceMs,
                        customName = name
                    )
                }
            }
            result.fold(
                onSuccess = { saveResult ->
                    when (saveResult) {
                        is SessionRepository.SaveResult.Saved -> {
                            val msg = getString(R.string.session_saved, saveResult.entry.displayName)
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                        }
                        SessionRepository.SaveResult.NoAnchors -> {
                            Snackbar.make(
                                binding.root,
                                R.string.session_save_need_anchors,
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onFailure = {
                    Snackbar.make(binding.root, R.string.session_save_failed, Snackbar.LENGTH_SHORT).show()
                }
            )
        }
    }

    // -- rendering --

    private fun render(state: CaptureViewModel.State) {
        val view = _binding ?: return

        // Status pill: model + capture state + anchor count
        binding.statsText.text = when (state.modelStatus) {
            CaptureViewModel.ModelStatus.Loading -> getString(R.string.stats_placeholder)
            is CaptureViewModel.ModelStatus.Failed -> getString(R.string.model_load_error)
            CaptureViewModel.ModelStatus.Ready -> when {
                state.capturing -> getString(R.string.stats_scanning)
                state.anchors.isEmpty() -> getString(R.string.stats_ready)
                else -> getString(
                    R.string.stats_format,
                    state.anchors.size,
                    state.inferenceTimeMs
                )
            }
        }

        // Shutter enabled only when the model is ready and not already scanning.
        val shutterEnabled =
            state.modelStatus is CaptureViewModel.ModelStatus.Ready && !state.capturing
        binding.shutterButton.isEnabled = shutterEnabled
        binding.shutterButton.background = ContextCompat.getDrawable(
            requireContext(),
            if (shutterEnabled) R.drawable.bg_shutter else R.drawable.bg_shutter_disabled
        )
        binding.shutterLabel.text = getString(
            if (state.capturing) R.string.capture_running else R.string.capture
        )

        // Save / clear FABs only make sense once something exists.
        binding.saveFab.isEnabled = state.anchors.isNotEmpty()
        binding.clearFab.isEnabled = state.anchors.isNotEmpty()
        binding.saveFab.alpha = if (state.anchors.isNotEmpty()) 1f else 0.5f
        binding.clearFab.alpha = if (state.anchors.isNotEmpty()) 1f else 0.5f

        // Anchored-parts list
        anchorAdapter.submitList(state.anchors)
        binding.anchorsEmpty.visibility = if (state.anchors.isEmpty()) View.VISIBLE else View.GONE
        binding.anchorsList.visibility = if (state.anchors.isEmpty()) View.GONE else View.VISIBLE

        // Overlay
        view.overlayView.setTracks(state.anchors, state.frameWidth, state.frameHeight)
    }

    private fun animateShutterPulse() {
        val view = binding.shutterButton
        view.animate()
            .scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }
}
