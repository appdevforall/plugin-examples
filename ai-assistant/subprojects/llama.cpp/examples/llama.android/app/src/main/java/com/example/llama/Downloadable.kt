package com.example.llama

import android.net.Uri
import java.io.File

data class Downloadable(val name: String, val source: Uri, val destination: File)
