plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}


android {

    namespace = "com.example.walkie"

    compileSdk {
        version = release(37)
    }


    defaultConfig {

        applicationId = "com.example.walkie"

        minSdk = 27

        targetSdk = 37


        versionCode = 1

        versionName = "1.0"


        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"



        ndk {

            abiFilters += listOf(
                "arm64-v8a",
                "x86"
            )
        }


        externalNativeBuild {

            cmake {

                cppFlags += listOf(
                    "-std=c++17"
                )

            }

        }

    }



    // ===============================
    // CMake
    // ===============================

    externalNativeBuild {

        cmake {

            path =
                file(
                    "src/main/cpp/CMakeLists.txt"
                )

            version =
                "3.22.1"

        }

    }



    buildTypes {


        debug {

            packaging {

                jniLibs {

                    keepDebugSymbols +=
                        "**/*.so"

                }

            }

        }



        release {

            optimization {

                enable = false

            }


            packaging {

                jniLibs {

                    keepDebugSymbols +=
                        "**/*.so"

                }

            }

        }

    }



    // ===============================
    // 防止 NDK28 strip so异常
    // ===============================

    packagingOptions {

        jniLibs {

            useLegacyPackaging = true

        }

    }



    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11

    }



    buildFeatures {

        compose = true

    }

}




dependencies {


    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )


    implementation(
        libs.androidx.activity.compose
    )


    implementation(
        libs.androidx.compose.material3
    )


    implementation(
        libs.androidx.compose.ui
    )


    implementation(
        libs.androidx.compose.ui.graphics
    )


    implementation(
        libs.androidx.compose.ui.tooling.preview
    )


    implementation(
        libs.androidx.core.ktx
    )


    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )



    testImplementation(
        libs.junit
    )


    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )


    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )


    androidTestImplementation(
        libs.androidx.espresso.core
    )


    androidTestImplementation(
        libs.androidx.junit
    )


    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )


    debugImplementation(
        libs.androidx.compose.ui.tooling
    )

}