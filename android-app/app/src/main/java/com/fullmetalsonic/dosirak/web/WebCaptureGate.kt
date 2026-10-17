package com.fullmetalsonic.dosirak.web

/** Independent from site/app business state: accepted values are still untrusted log data. */
internal class WebCaptureGate {
    private var token: String? = null
    private var document: String? = null
    private var ready = false
    private var sequence = 0

    fun activate(token: String, document: String) {
        this.token = token
        this.document = document
        ready = false
        sequence = 0
    }

    fun invalidate() {
        token = null
        document = null
        ready = false
        sequence = 0
    }

    fun accept(text: String?, origin: String, mainFrame: Boolean, page: String?,
        enabled: Boolean, captureCurrent: Boolean): WebCaptureMessage? {
        if (!enabled || !captureCurrent) { invalidate(); return null }
        if (!mainFrame || !WebCaptureDecoder.trustedOrigin(origin) ||
            document != page || !WebCaptureDecoder.trustedPage(page)) return null
        val message = WebCaptureDecoder.decode(text) ?: return null
        if (message.token != token) return null
        if (message.type == WebCaptureMessage.Type.READY) {
            if (sequence == 0) ready = true
            return null
        }
        if (!ready || message.sequence <= sequence) return null
        sequence = message.sequence
        return message
    }
}
