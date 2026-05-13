package com.example.chilidisease

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.chilidisease.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Sembunyikan action bar
        supportActionBar?.hide()

        // Animasi elemen UI
        playEntryAnimations()

        // Navigasi ke MainActivity setelah delay
        lifecycleScope.launch {
            binding.tvSplashStatus.text = "Memuat model ML..."
            delay(900)
            binding.tvSplashStatus.text = "Menginisialisasi kamera..."
            delay(700)
            binding.tvSplashStatus.text = "Siap!"
            delay(400)
            goToMain()
        }
    }

    private fun playEntryAnimations() {
        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        fadeIn.duration = 600

        binding.ivSplashLogo.startAnimation(fadeIn)
        binding.tvSplashTitle.startAnimation(fadeIn)
        binding.tvSplashSubtitle.startAnimation(fadeIn)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
