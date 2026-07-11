package gay.zharel.fateweaver.flight

import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

class FateRequest(val session: IHTTPSession) {
    fun query(name: String): String? {
        val list = session.parameters[name]
        return if (list.isNullOrEmpty()) null else list[0]
    }
}

class FateResponse {
    fun json(body: String): NanoHTTPD.Response {
        val resp = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body)
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    fun badRequest(msg: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", msg)
    }

    fun forbidden(msg: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", msg)
    }

    fun notFound(msg: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", msg)
    }

    fun download(file: File): NanoHTTPD.Response {
        if (!file.exists() || !file.isFile) {
            return notFound("File not found")
        }
        val fis = FileInputStream(file)
        val resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", fis)
        val encoded = try { URLEncoder.encode(file.name, "UTF-8") } catch (_: Exception) { file.name }
        resp.addHeader("Content-Disposition", "attachment; filename=\"$encoded\"")
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    fun redirect(url: String): NanoHTTPD.Response {
        val resp = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT, NanoHTTPD.MIME_HTML, "")
        resp.addHeader("Location", url)
        return resp
    }
}

class FateRouter(private val manager: WebHandlerManager) {
    fun get(path: String, handler: (FateRequest, FateResponse) -> NanoHTTPD.Response) {
        manager.register(path, WebHandler { session ->
            handler(FateRequest(session), FateResponse())
        })
    }
}

fun WebHandlerManager.route(block: FateRouter.() -> Unit) {
    FateRouter(this).apply(block)
}
