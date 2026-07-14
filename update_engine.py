import re

with open("android/app/src/main/java/com/safescan/domain/ImageFilterEngine.kt", "r") as f:
    content = f.read()

# Fix the import
content = content.replace("import com.safescan.domain.model.FilterType", "import com.safescan.data.FilterType")

# Replace PHOTO and AUTO with PAPER
pattern = r"            FilterType\.PHOTO -> \{.*?                \}\n            \}\n            FilterType\.AUTO -> \{.*?                blurred\.release\(\)\n            \}"

replacement = """            FilterType.PAPER -> {
                val cleanColor = removeShadowsColor(src)
                
                val hsv = Mat()
                Imgproc.cvtColor(cleanColor, hsv, Imgproc.COLOR_BGR2HSV)
                val hsvChannels = ArrayList<Mat>()
                Core.split(hsv, hsvChannels)
                
                // Increase saturation slightly, and contrast on V channel
                hsvChannels[1].convertTo(hsvChannels[1], -1, 1.2, 0.0) 
                hsvChannels[2].convertTo(hsvChannels[2], -1, 1.2, -10.0)

                Core.merge(hsvChannels, hsv)
                
                val enhanced = Mat()
                Imgproc.cvtColor(hsv, enhanced, Imgproc.COLOR_HSV2BGR)
                
                // Apply Unsharp Masking for crisp text
                val blurred = Mat()
                Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 2.5)
                Core.addWeighted(enhanced, 1.5, blurred, -0.5, 0.0, outMat)
                
                Imgproc.cvtColor(outMat, outMat, Imgproc.COLOR_BGR2RGBA)
                
                cleanColor.release()
                hsv.release()
                for (ch in hsvChannels) {
                    ch.release()
                }
                enhanced.release()
                blurred.release()
            }"""

new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open("android/app/src/main/java/com/safescan/domain/ImageFilterEngine.kt", "w") as f:
    f.write(new_content)
