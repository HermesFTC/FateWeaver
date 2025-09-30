package gay.zharel.hermes.fateweaver

import android.content.Context
import android.content.res.AssetManager
import com.qualcomm.robotcore.util.RobotLog
import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler
import org.firstinspires.ftc.robotserver.internal.webserver.MimeTypesUtil
import java.io.File
import java.io.FileInputStream
import java.io.IOException

private fun newStaticAssetHandler(assetManager: AssetManager, file: String): WebHandler {
    return WebHandler { session: IHTTPSession ->
        if (session.method == NanoHTTPD.Method.GET) {
            val mimeType = MimeTypesUtil.determineMimeType(file)
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,
                mimeType, assetManager.open(file))
        } else {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT, "")
        }
    }
}

private fun registerAssetsUnderPath(webHandlerManager: WebHandlerManager,
                                    assetManager: AssetManager, path: String) {
    try {
        val list = assetManager.list("web/$path") ?: return
        if (list.isNotEmpty()) {
            for (file in list) {
                registerAssetsUnderPath(webHandlerManager, assetManager, "$path/$file")
            }
        } else {
            webHandlerManager.register("/$path", newStaticAssetHandler(assetManager, "web/$path"))
        }
    } catch (e: IOException) {
        RobotLog.setGlobalErrorMsg(RuntimeException(e),
            "unable to register tuning web routes")
    }
}

object LogFiles {
    @WebHandlerRegistrar
    @JvmStatic
    fun registerRoutes(context: Context, manager: WebHandlerManager) {
        manager.register("fate/logs") {
            val htmlContent = createHTML().html {
                head {
                    title("Logs")
                }
                body {
                    ul {
                        val fs = LOG_ROOT.listFiles()!!
                        fs.sortByDescending { it.lastModified() }
                        for (f in fs) {
                            li {
                                a(href = "fate/logs/download?file=${f.name}") {
                                    attributes["download"] = f.name
                                    +f.name
                                }
                                +" (${f.length()} bytes)"
                            }
                        }
                    }
                }
            }
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                NanoHTTPD.MIME_HTML, "<!doctype html>$htmlContent")
        }

        manager.register("fate/logs/download") { session: IHTTPSession ->
            val pairs = session.queryParameterString.split("&".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size != 1) {
                return@register NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT, "expected one query parameter, got " + pairs.size)
            }
            val parts = pairs[0].split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts[0] != "file") {
                return@register NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT, "expected file query parameter, got " + parts[0])
            }
            val f = File(LOG_ROOT, parts[1])
            if (!f.exists()) {
                return@register NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT, "file $f doesn't exist")
            }
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK,
                "application/json", FileInputStream(f))
        }
    }
}
