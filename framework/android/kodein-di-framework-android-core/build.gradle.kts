plugins {
    id("org.kodein.library.android")
}

kodeinLib {
    dependencies {
        api(project(":kodein-di-core") target "jvm")
    }
}
