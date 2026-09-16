package dev.kage.manager

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.kage.manager.ui.AppsFragment
import dev.kage.manager.ui.HomeFragment
import dev.kage.manager.ui.SettingsFragment
import dev.kage.manager.ui.TerminalFragment
import dev.kage.manager.ui.ToolsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var nav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nav = findViewById(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item: MenuItem -> select(item.itemId) }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_home
        }
    }

    private fun select(id: Int): Boolean {
        val fragment: Fragment = when (id) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_apps -> AppsFragment()
            R.id.nav_shell -> TerminalFragment()
            R.id.nav_tools -> ToolsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> return false
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
        return true
    }
}
