import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Push needs a Firebase project: official builds carry app/google-services.json, a build without it runs with push off.
if (file("google-services.json").exists()) apply(plugin = libs.plugins.google.services.get().pluginId)

val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.opensolr.mail"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opensolr.mail"
        minSdk = 26
        targetSdk = 36
        versionCode = 52
        versionName = "1.11.0"
    }

    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_BUILD", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_BUILD", "true")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (!signingProperties.isEmpty) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (!signingProperties.isEmpty) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val solrConfigAssets = layout.buildDirectory.dir("generated/solrConfigAssets")

val solrConfigZip = tasks.register<Zip>("solrConfigZip") {
    from(rootProject.file("solr/conf"))
    archiveFileName.set("opensolr-mail-conf.zip")
    destinationDirectory.set(solrConfigAssets)
}

android.sourceSets.getByName("main").assets.srcDir(solrConfigAssets)

tasks.configureEach {
    if (name != solrConfigZip.name && (name.contains("Assets") || name.contains("Lint", ignoreCase = true))) {
        dependsOn(solrConfigZip)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.webkit)
    implementation(libs.jsoup)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.fragment)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
