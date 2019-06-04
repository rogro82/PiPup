package nl.rogro82.pipup

import fi.iki.elonen.NanoHTTPD

class WebServer(port: Int, private val handler: Handler): NanoHTTPD(port) {
    interface Handler {
        fun handleHttpRequest(session: IHTTPSession?): Response
    }

    override fun serve(session: IHTTPSession?): Response {
        return handler.handleHttpRequest(session)
    }
}