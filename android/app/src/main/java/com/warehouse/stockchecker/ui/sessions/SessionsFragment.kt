package com.warehouse.stockchecker.ui.sessions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.warehouse.stockchecker.StockCheckApp
import com.warehouse.stockchecker.R
import com.warehouse.stockchecker.databinding.FragmentSessionsBinding
import com.warehouse.stockchecker.session.SessionIndexEntry
import com.warehouse.stockchecker.session.SessionRepository
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists the saved sessions from [SessionRepository]'s index. Each row opens the JSON file
 * (Q+: MediaStore Downloads; pre-Q: FileProvider); the trash icon removes the entry from the
 * in-app index. Removing from the index does NOT delete the underlying file.
 */
class SessionsFragment : Fragment() {

    private var _binding: FragmentSessionsBinding? = null
    private val binding get() = _binding!!

    private val adapter by lazy {
        SessionsAdapter(
            onOpen = ::openSession,
            onDelete = ::confirmDelete
        )
    }

    private val sessionRepository: SessionRepository by lazy {
        (requireActivity().application as StockCheckApp).sessionRepository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sessionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.sessionsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val view = _binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { sessionRepository.index() }
            view.sessionsCount.text = getString(R.string.sessions_count, entries.size)
            if (entries.isEmpty()) {
                view.emptyState.visibility = View.VISIBLE
                view.sessionsList.visibility = View.GONE
            } else {
                view.emptyState.visibility = View.GONE
                view.sessionsList.visibility = View.VISIBLE
            }
            adapter.submitList(entries)
        }
    }

    private fun openSession(entry: SessionIndexEntry) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                sessionRepository.uriFor(entry, preQFile = null)
            }
            launchJsonIntent(uri, entry, isShare = false)
        }
    }

    private fun launchJsonIntent(uri: Uri, entry: SessionIndexEntry, isShare: Boolean) {
        val intent = if (isShare) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.session_share_subject, entry.displayName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/json")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            startActivity(Intent.createChooser(intent, entry.displayName))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.session_no_app_to_open, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(entry: SessionIndexEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(entry.displayName)
            .setMessage(R.string.session_delete_confirm)
            .setNegativeButton(R.string.session_dialog_cancel, null)
            .setPositiveButton(R.string.session_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { sessionRepository.delete(entry) }
                    refresh()
                }
            }
            .show()
    }
}
