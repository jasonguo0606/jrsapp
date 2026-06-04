package com.jrsapp.data.repository

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.jrsapp.data.model.DlnaDevice
import com.jrsapp.data.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class DlnaRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun discoverDevices(timeoutMillis: Long = DISCOVERY_TIMEOUT_MS): Result<List<DlnaDevice>> =
        withContext(Dispatchers.IO) {
            runCatching {
                acquireMulticastLock()
                discoverDevicesInternal(timeoutMillis)
            }.onFailure {
                Log.e(TAG, "discoverDevices failure", it)
            }.also {
                releaseMulticastLock()
            }
        }

    suspend fun startCasting(
        device: DlnaDevice,
        source: VideoSource,
        title: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sendAction(
                device = device,
                action = "SetAVTransportURI",
                body = """
                    <u:SetAVTransportURI xmlns:u="${device.serviceType}">
                      <InstanceID>0</InstanceID>
                      <CurrentURI>${xmlEscape(source.url)}</CurrentURI>
                      <CurrentURIMetaData>${xmlEscape(buildMetadata(title, source))}</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                """.trimIndent()
            )
            sendAction(
                device = device,
                action = "Play",
                body = """
                    <u:Play xmlns:u="${device.serviceType}">
                      <InstanceID>0</InstanceID>
                      <Speed>1</Speed>
                    </u:Play>
                """.trimIndent()
            )
            Unit
        }.onFailure {
            Log.e(TAG, "startCasting failure device=${device.friendlyName} source=${source.url}", it)
        }
    }

    suspend fun stopCasting(device: DlnaDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sendAction(
                device = device,
                action = "Stop",
                body = """
                    <u:Stop xmlns:u="${device.serviceType}">
                      <InstanceID>0</InstanceID>
                    </u:Stop>
                """.trimIndent()
            )
            Unit
        }.onFailure {
            Log.e(TAG, "stopCasting failure device=${device.friendlyName}", it)
        }
    }

    private fun discoverDevicesInternal(timeoutMillis: Long): List<DlnaDevice> {
        val discoveredLocations = linkedSetOf<String>()
        val searchPacket = buildSearchPacket()
        val targetAddress = InetAddress.getByName(SSDP_HOST)
        val endAt = System.currentTimeMillis() + timeoutMillis

        DatagramSocket().use { socket ->
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            repeat(SEARCH_REPEAT_COUNT) {
                socket.send(
                    DatagramPacket(
                        searchPacket,
                        searchPacket.size,
                        targetAddress,
                        SSDP_PORT
                    )
                )
            }

            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < endAt) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    val headers = parseHeaders(response)
                    val location = headers["location"].orEmpty()
                    if (location.isNotBlank()) {
                        discoveredLocations += location
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue polling until timeout.
                }
            }
        }

        return discoveredLocations.mapNotNull { location ->
            runCatching { fetchDevice(location) }
                .onFailure { Log.e(TAG, "fetchDevice failure location=$location", it) }
                .getOrNull()
        }.distinctBy { device ->
            device.udn.ifBlank { device.controlUrl }
        }
    }

    private fun fetchDevice(locationUrl: String): DlnaDevice {
        val request = Request.Builder()
            .url(locationUrl)
            .get()
            .build()
        val xml = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "设备描述拉取失败: ${response.code}" }
            response.body?.string().orEmpty()
        }

        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val document = documentBuilderFactory.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val deviceElements = document.getElementsByTagName("device")
        val renderer = (0 until deviceElements.length)
            .map { deviceElements.item(it) as Element }
            .firstOrNull { element ->
                element.firstText("deviceType").contains("MediaRenderer", ignoreCase = true)
            }
            ?: error("未找到 MediaRenderer 设备")

        val serviceElements = renderer.getElementsByTagName("service")
        val service = (0 until serviceElements.length)
            .map { serviceElements.item(it) as Element }
            .firstOrNull { element ->
                element.firstText("serviceType") == AV_TRANSPORT_SERVICE_TYPE
            }
            ?: error("设备不支持 AVTransport")

        val controlUrl = service.firstText("controlURL").ifBlank {
            error("设备控制地址为空")
        }

        return DlnaDevice(
            friendlyName = renderer.firstText("friendlyName").ifBlank { "未命名设备" },
            locationUrl = locationUrl,
            controlUrl = URI(locationUrl).resolve(controlUrl).toString(),
            serviceType = service.firstText("serviceType").ifBlank { AV_TRANSPORT_SERVICE_TYPE },
            udn = renderer.firstText("UDN"),
            manufacturer = renderer.firstText("manufacturer").takeIf { it.isNotBlank() },
            modelName = renderer.firstText("modelName").takeIf { it.isNotBlank() }
        )
    }

    private fun sendAction(
        device: DlnaDevice,
        action: String,
        body: String
    ) {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                $body
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val request = Request.Builder()
            .url(device.controlUrl)
            .post(envelope.toRequestBody(SOAP_MEDIA_TYPE))
            .header("Content-Type", """text/xml; charset="utf-8"""")
            .header("SOAPAction", "\"${device.serviceType}#$action\"")
            .build()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "DLNA 操作失败($action): ${response.code} ${response.body?.string().orEmpty()}"
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager?.createMulticastLock("jrsapp-dlna-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    private fun buildSearchPacket(): ByteArray =
        (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "\r\n"
            ).toByteArray()

    private fun parseHeaders(response: String): Map<String, String> =
        response.split("\r\n")
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim().lowercase(Locale.US) to
                        line.substring(separator + 1).trim()
                }
            }
            .toMap()

    private fun buildMetadata(title: String, source: VideoSource): String {
        val mimeType = when (source.type) {
            com.jrsapp.data.model.VideoSourceType.HLS -> "application/vnd.apple.mpegurl"
            com.jrsapp.data.model.VideoSourceType.MP4 -> "video/mp4"
            com.jrsapp.data.model.VideoSourceType.FLV -> "video/x-flv"
            com.jrsapp.data.model.VideoSourceType.UNKNOWN -> "video/*"
        }
        return """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>${xmlEscape(title)}</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:$mimeType:*">${xmlEscape(source.url)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun Element.firstText(tagName: String): String =
        getElementsByTagName(tagName)
            .item(0)
            ?.textContent
            .orEmpty()
            .trim()

    companion object {
        private const val TAG = "DlnaRepository"
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RECEIVE_TIMEOUT_MS = 500
        private const val SEARCH_REPEAT_COUNT = 2
        private const val DISCOVERY_TIMEOUT_MS = 3_500L
        private const val AV_TRANSPORT_SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
        private val SOAP_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
    }
}
