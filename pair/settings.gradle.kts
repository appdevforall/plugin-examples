pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(files("../libs/plugin-api.jar"))
        classpath(files("../libs/gradle-plugin.jar"))
        classpath("com.android.tools.build:gradle:8.8.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.0")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pair"
