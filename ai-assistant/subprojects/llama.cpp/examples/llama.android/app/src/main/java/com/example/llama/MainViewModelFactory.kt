package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            // 1. Create the single instance of LLamaAndroid
            val llamaAndroid = LLamaAndroid.instance()
            val engine = LlmInferenceEngine(llamaAndroid)

            // 2. Create the repository, which depends on LLamaAndroid
            val localLlmRepositoryImpl = LocalLlmRepositoryImpl(application, engine)

            // 3. Create the ViewModel, which now depends on the repository
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(localLlmRepositoryImpl) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
