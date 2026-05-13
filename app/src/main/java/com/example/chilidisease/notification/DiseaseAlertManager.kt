package com.example.chilidisease.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.chilidisease.MainActivity
import com.example.chilidisease.R
import com.example.chilidisease.detector.DetectionResult

/**
 * DiseaseAlertManager
 *
 * Mengelola notifikasi sistem saat penyakit cabai terdeteksi dengan
 * tingkat kepercayaan tinggi (≥ 80%).
 *
 * Fitur:
 *  - Notifikasi berbeda untuk setiap jenis penyakit
 *  - Cooldown 60 detik antar notifikasi (hindari spam)
 *  - Tap notifikasi membuka aplikasi kembali
 *  - Support Android 8.0+ (Notification Channel)
 */
class DiseaseAlertManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID   = "chili_disease_alerts"
        private const val CHANNEL_NAME = "Peringatan Penyakit Cabai"
        private const val CHANNEL_DESC = "Notifikasi saat penyakit cabai terdeteksi"

        private const val NOTIF_ID_HEALTHY     = 1001
        private const val NOTIF_ID_ANTHRACNOSE = 1002
        private const val NOTIF_ID_FUSARIUM    = 1003

        private const val CONFIDENCE_THRESHOLD = 0.80f
        private const val COOLDOWN_MS = 60_000L  // 60 detik
    }

    private val notifManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    // Timestamp notifikasi terakhir per kelas penyakit
    private val lastNotifTime = mutableMapOf<Int, Long>()

    init {
        createNotificationChannel()
    }

    // ================================================================
    // CHANNEL SETUP
    // ================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                enableLights(true)
                lightColor = 0xFFFF6D00.toInt()
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ================================================================
    // SEND ALERT
    // ================================================================

    /**
     * Kirim notifikasi jika:
     *  1. Confidence ≥ 80%
     *  2. Penyakit terdeteksi (bukan kelas sehat)
     *  3. Cooldown 60 detik sudah terlewati
     *
     * @param result Hasil deteksi TFLite
     */
    fun maybeAlert(result: DetectionResult) {
        // Hanya kirim untuk penyakit (bukan sehat)
        if (result.classIndex == 0) return

        // Filter confidence rendah
        if (result.confidence < CONFIDENCE_THRESHOLD) return

        // Cek cooldown
        val now = System.currentTimeMillis()
        val last = lastNotifTime[result.classIndex] ?: 0L
        if (now - last < COOLDOWN_MS) return

        lastNotifTime[result.classIndex] = now
        sendNotification(result)
    }

    private fun sendNotification(result: DetectionResult) {
        // Intent untuk buka app saat notifikasi ditap
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Konten notifikasi per kelas penyakit
        val (title, body, color) = when (result.classIndex) {
            1 -> Triple(
                "⚠️ Antraknosa (Patek) Terdeteksi!",
                "Akurasi ${result.confidencePercent}. Segera periksa buah cabai Anda.",
                0xFFFF6D00.toInt()
            )
            2 -> Triple(
                "🔴 Layu Fusarium Terdeteksi!",
                "Akurasi ${result.confidencePercent}. Segera isolasi tanaman yang terinfeksi.",
                0xFFD50000.toInt()
            )
            else -> Triple(
                "⚠️ Penyakit Terdeteksi",
                "Akurasi ${result.confidencePercent}. Kelas: ${result.label}",
                0xFF6200EA.toInt()
            )
        }

        val notifId = when (result.classIndex) {
            1 -> NOTIF_ID_ANTHRACNOSE
            2 -> NOTIF_ID_FUSARIUM
            else -> NOTIF_ID_ANTHRACNOSE
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(color)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()

        try {
            notifManager.notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission belum diberikan (Android 13+)
            // Akan ditangani di runtime permission check
        }
    }

    // ================================================================
    // PERMISSION CHECK (Android 13+)
    // ================================================================

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifManager.areNotificationsEnabled()
        } else {
            true
        }
    }

    fun cancelAll() = notifManager.cancelAll()
}
