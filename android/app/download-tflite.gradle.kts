import java.io.File
import java.net.URL

// Task to download TFLite segmentation model
tasks.register("downloadTFLiteModel") {
    val modelUrl = "https://github.com/pynicolas/fairscan-segmentation-model/releases/download/v1.2.0/fairscan-segmentation-model.tflite"
    val assetsDir = file("src/main/assets")
    val modelFile = file("src/main/assets/fairscan-segmentation-model.tflite")

    outputs.file(modelFile)

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }
        if (!modelFile.exists()) {
            println("Downloading TFLite model from $modelUrl...")
            try {
                URL(modelUrl).openStream().use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("TFLite model downloaded successfully: ${modelFile.name}")
            } catch (e: Exception) {
                println("WARNING: Failed to download TFLite model: ${e.message}")
                println("The build will continue, but please make sure the file exists or is downloaded when network is available.")
            }
        } else {
            println("TFLite model already exists at ${modelFile.absolutePath}, skipping download.")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadTFLiteModel")
}
