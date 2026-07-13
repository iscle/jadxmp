package com.jadxmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform