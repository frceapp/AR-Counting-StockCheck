package com.warehouse.stockchecker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.warehouse.stockchecker.databinding.ActivityMainBinding

/**
 * App shell: hosts a single nav graph with three destinations (Capture, Sessions, Settings)
 * and a Material 3 bottom navigation bar. Each fragment owns its own state; this activity only
 * wires them up and remembers the current tab.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHost) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHostFragment.navController)
    }
}
