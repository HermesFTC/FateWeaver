package gay.zharel.fateweaver.flight

import android.content.Context
import android.content.res.AssetManager
import com.qualcomm.robotcore.util.RobotLog
import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler
import org.firstinspires.ftc.robotserver.internal.webserver.MimeTypesUtil
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder

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

object FateDashboard {
    @WebHandlerRegistrar
    @JvmStatic
    fun registerRoutes(context: Context, manager: WebHandlerManager) {
        val assetManager = context.assets
        registerAssetsUnderPath(manager, assetManager, "fate")

        manager.route {
            get("/fate") { _, res ->
                res.redirect("/fate/index.html")
            }

            get("/fate/logs/list") { _, res ->
                val files = LOG_ROOT.listFiles()?.filter { it.isFile && it.name.endsWith(EXT) } ?: emptyList()
                val sorted = files.sortedByDescending { it.lastModified() }
                val jsonItems = sorted.map { f ->
                    val name = f.name
                    val size = f.length()
                    val lastMod = f.lastModified()
                    "{\"name\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"size\":$size,\"lastModified\":$lastMod}"
                }
                res.json("[${jsonItems.joinToString(",")}]")
            }

            get("/fate/live/status") { _, res ->
                res.json(
                    "{\"enabled\":${LiveSettings.enabled}," +
                        "\"running\":${FlightProjector.isRunning}," +
                        "\"port\":${FlightProjector.port}," +
                        "\"clients\":${FlightProjector.clientCount}}"
                )
            }

            // Turning this off closes the server and unbinds the port. The choice persists across
            // robot restarts. See FlightProjector for when to turn it off.
            get("/fate/live/enable") { req, res ->
                val on = req.query("on") ?: return@get res.badRequest("Missing 'on' parameter")
                if (on != "true" && on != "false") {
                    return@get res.badRequest("'on' must be true or false")
                }

                FlightProjector.setEnabled(on.toBoolean())
                res.json(
                    "{\"enabled\":${LiveSettings.enabled},\"running\":${FlightProjector.isRunning}}"
                )
            }

            get("/fate/logs/download") { req, res ->
                val fileName = req.query("file") ?: return@get res.badRequest("Missing 'file' parameter")
                
                // Reject path traversal and anything that is not a log file
                if (fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(EXT)) {
                    return@get res.forbidden("Invalid file name")
                }
                
                res.download(java.io.File(LOG_ROOT, fileName))
            }
        }
    }
}
