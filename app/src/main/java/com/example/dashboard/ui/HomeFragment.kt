package com.example.dashboard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.dashboard.R
import com.example.dashboard.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardDrive.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_dashboard)
        }

        binding.cardMaintenance.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_maintenance)
        }

        binding.cardProfile.setOnClickListener {
            val dialog = ProfileDialogFragment()
            dialog.show(parentFragmentManager, "ProfileDialog")
        }

        binding.cardProfile.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.scaleX = 0.97f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.scaleX = 1f
            }
            false
        }

        animateHomeCards()
    }

    private fun animateHomeCards() {
        val cards = listOf(binding.cardDrive, binding.cardMaintenance)

        cards.forEachIndexed { index, card ->
            card.apply {
                alpha = 0f
                translationY = 60f

                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((index * 120).toLong())
                    .setDuration(400)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}