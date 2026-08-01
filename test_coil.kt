import coil.request.ImageRequest
import android.content.Context
fun test(context: Context, file: java.io.File) {
    ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey(file.absolutePath + file.lastModified())
        .diskCacheKey(file.absolutePath + file.lastModified())
        .build()
}
