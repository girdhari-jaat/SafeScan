package com.safescan.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.safescan.R

class MinimalFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_minimal, container, false)
        val btnGoToScanner = view.findViewById<Button>(R.id.btnGoToScanner)
        btnGoToScanner.setOnClickListener {
            findNavController().navigate(R.id.scannerFragment)
        }
        return view
    }
}
