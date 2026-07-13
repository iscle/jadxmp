package com.jadxmp.api

actual fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
