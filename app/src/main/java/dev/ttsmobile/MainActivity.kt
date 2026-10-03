package dev.ttsmobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.ttsmobile.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pagerAdapter = SectionsAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 1

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = getString(
                if (pos == 0) R.string.tab_sintese else R.string.tab_backend
            )
        }.attach()

        // Evita que o ViewPager2 pre-renderize de forma agressiva a tela errada.
        binding.viewPager.setCurrentItem(0, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Nada a limpar aqui; os fragments param suas próprias corrotinas.
    }
}
