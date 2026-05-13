package com.example.chilidisease

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chilidisease.databinding.ActivityHistoryBinding
import com.example.chilidisease.history.DetectionHistoryManager
import com.example.chilidisease.history.HistoryExporter

/**
 * DetectionHistoryActivity
 *
 * Menampilkan riwayat deteksi penyakit beserta statistik ringkasan.
 * Mendukung hapus per entri, hapus semua, dan ekspor CSV.
 */
class DetectionHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyManager: DetectionHistoryManager
    private lateinit var historyExporter: HistoryExporter
    private lateinit var adapter: HistoryAdapter

    // ================================================================
    // LIFECYCLE
    // ================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar dengan tombol back
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.history_title)
        }

        historyManager  = DetectionHistoryManager(this)
        historyExporter = HistoryExporter(this)

        setupRecyclerView()
        setupButtons()
        loadData()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ================================================================
    // RECYCLERVIEW
    // ================================================================

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onDelete = { entry ->
                historyManager.delete(entry.id)
                loadData()
                Toast.makeText(this, "Entri dihapus", Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
    }

    // ================================================================
    // BUTTONS
    // ================================================================

    private fun setupButtons() {
        // Hapus semua
        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_clear_confirm_title))
                .setMessage(getString(R.string.history_clear_confirm_message))
                .setPositiveButton(getString(R.string.history_clear_yes)) { _, _ ->
                    historyManager.clearAll()
                    loadData()
                }
                .setNegativeButton(getString(R.string.dialog_close), null)
                .show()
        }
    }

    // ================================================================
    // LOAD DATA
    // ================================================================

    private fun loadData() {
        val entries = historyManager.loadAll()
        val stats   = historyManager.getStats()

        // ── Update RecyclerView ──
        adapter.submitList(entries)
        binding.tvEmpty.visibility       = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerHistory.visibility = if (entries.isEmpty()) View.GONE  else View.VISIBLE

        // ── Update stats card ──
        if (stats.totalDetections == 0) {
            binding.cardStats.visibility = View.GONE
            return
        }
        binding.cardStats.visibility = View.VISIBLE
        binding.tvStatTotal.text       = stats.totalDetections.toString()
        binding.tvStatHealthy.text     = stats.healthyCount.toString()
        binding.tvStatAnthracnose.text = stats.anthracnoseCount.toString()
        binding.tvStatFusarium.text    = stats.fusariumCount.toString()
        binding.tvStatAvgConf.text     = "%.1f%%".format(stats.averageConfidence * 100)
        binding.progressHealth.progress = stats.healthPercentage.toInt()
    }

    // ================================================================
    // ADAPTER
    // ================================================================

    class HistoryAdapter(
        private val onDelete: (DetectionHistoryManager.HistoryEntry) -> Unit
    ) : ListAdapter<DetectionHistoryManager.HistoryEntry, HistoryAdapter.VH>(DIFF) {

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<DetectionHistoryManager.HistoryEntry>() {
                override fun areItemsTheSame(
                    a: DetectionHistoryManager.HistoryEntry,
                    b: DetectionHistoryManager.HistoryEntry
                ) = a.id == b.id
                override fun areContentsTheSame(
                    a: DetectionHistoryManager.HistoryEntry,
                    b: DetectionHistoryManager.HistoryEntry
                ) = a == b
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val accent: View      = v.findViewById(R.id.viewHistoryAccent)
            val label:  TextView  = v.findViewById(R.id.tvHistoryLabel)
            val conf:   TextView  = v.findViewById(R.id.tvHistoryConfidence)
            val date:   TextView  = v.findViewById(R.id.tvHistoryDate)
            val delete: ImageView = v.findViewById(R.id.ivHistoryDelete)
            // ivHistoryIcon tidak diisi teks — gunakan sebagai dekorasi warna
            val icon:   ImageView = v.findViewById(R.id.ivHistoryIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

        override fun onBindViewHolder(vh: VH, pos: Int) {
            val e   = getItem(pos)
            val ctx = vh.itemView.context

            vh.label.text = e.label
            vh.conf.text  = e.confidencePercent
            vh.date.text  = e.dateString

            val colorRes = when (e.classIndex) {
                0    -> R.color.color_healthy
                1    -> R.color.color_anthracnose
                2    -> R.color.color_fusarium
                else -> R.color.color_unknown
            }
            val color = ContextCompat.getColor(ctx, colorRes)
            vh.accent.setBackgroundColor(color)

            // Ikon emoji sebagai background tint
            val emoji = when (e.classIndex) { 0 -> "🟢"; 1 -> "🟠"; else -> "🔴" }
            vh.icon.contentDescription = emoji

            vh.delete.setOnClickListener { onDelete(e) }
        }
    }
}
