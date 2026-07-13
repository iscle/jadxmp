package com.jadxmp.api

// js is single-threaded: run one class at a time (cooperative scheduling only).
actual fun defaultParallelism(): Int = 1
