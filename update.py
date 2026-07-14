import re

with open("android/app/src/main/java/com/safescan/domain/ImageProcessor.kt", "r") as f:
    content = f.read()

pattern = r"            // Apply Filter\n            outMat = Mat\(\)\n            when \(state\.filter\) \{.*?\n                FilterType\.COLOR -> \{\n                    Imgproc\.cvtColor\(src, outMat, Imgproc\.COLOR_BGR2RGBA\)\n                \}\n            \}\n\n            val resultBitmap = Bitmap\.createBitmap\(outMat\.cols\(\), outMat\.rows\(\), Bitmap\.Config\.ARGB_8888\)"

replacement = """            // Apply Filter
            outMat = ImageFilterEngine.applyFilter(src, state.filter)

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)"""

new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open("android/app/src/main/java/com/safescan/domain/ImageProcessor.kt", "w") as f:
    f.write(new_content)
