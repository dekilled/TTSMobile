package dev.ttsmobile

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class SectionsAdapter(activity: Fragment) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2
    override fun createFragment(position: Int): Fragment =
        if (position == 0) SinteseFragment() else BackendFragment()
}
