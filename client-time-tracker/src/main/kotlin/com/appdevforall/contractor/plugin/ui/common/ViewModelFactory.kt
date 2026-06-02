package com.appdevforall.contractor.plugin.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Build a [ViewModelProvider.Factory] from a creator lambda. Replaces the boilerplate
 * `class Factory : ViewModelProvider.Factory { override fun <T : ViewModel> create(...) }` that
 * each MVI ViewModel would otherwise repeat.
 *
 * Usage:
 * ```
 * companion object {
 *     fun factory(dep: Dep) = viewModelFactory { MyViewModel(dep) }
 * }
 * ```
 *
 * Then in the Fragment:
 * ```
 * private val vm: MyViewModel by viewModels { MyViewModel.factory(dep) }
 * ```
 */
inline fun viewModelFactory(crossinline create: () -> ViewModel): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
