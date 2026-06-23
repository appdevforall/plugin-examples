// Top-level build file for AI Assistant Plugins
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    base
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
