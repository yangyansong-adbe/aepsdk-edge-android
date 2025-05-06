package com.adobe.marketing.mobile.edge.testapp.kotlin

import android.net.NetworkCapabilities
import android.os.Build
import com.adobe.marketing.mobile.services.DeviceInforming
import com.adobe.marketing.mobile.services.HttpConnecting
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.Log
import com.adobe.marketing.mobile.services.NetworkCallback
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.Networking
import com.adobe.marketing.mobile.services.ServiceProvider
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownServiceException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection


internal class CustomNetworkService : Networking {
    private val executorService: ExecutorService = ThreadPoolExecutor(
        THREAD_POOL_CORE_SIZE,
        THREAD_POOL_MAXIMUM_SIZE,
        THREAD_POOL_KEEP_ALIVE_TIME.toLong(),
        TimeUnit.SECONDS,
        SynchronousQueue()
    )


    override fun connectAsync(
        request: NetworkRequest,
        callback: NetworkCallback
    ) {

        if (!this.isInternetAvailable()) {
            Log.trace(TAG, TAG, "The Android device is offline.")
            callback.call(null)
            return
        }
        try {
            executorService.submit {
                val connection = doConnection(request)
                callback.call(connection)
            }
        } catch (e: Exception) {
            // to catch RejectedExecutionException when the thread pool is saturated
            Log.warning(
                TAG,
                TAG,
                java.lang.String.format(
                    "Failed to send request for (%s) [%s]",
                    request.url,
                    (e.localizedMessage ?: e.message)
                )
            )

            callback.call(null)
        }
    }

    /**
     * Performs the actual connection to the specified `url`.
     *
     *
     * It sets the default connection headers if none were provided through the `requestProperty` parameter. You can override the default user agent and language headers if
     * they are present in `requestProperty`
     *
     *
     * This method will return null, if failed to establish connection to the resource.
     *
     * @param request [NetworkRequest] used for connection
     * @return [HttpConnecting] instance, representing a connection attempt
     */
    private fun doConnection(request: NetworkRequest): HttpConnecting? {
        var connection: HttpConnecting? = null

        if (request.url == null || !request.url.contains("https")) {
            Log.warning(
                TAG,
                TAG,
                java.lang.String.format(
                    "Invalid URL (%s), only HTTPS protocol is supported",
                    request.url
                )
            )
            return null
        }

        val headers =
            this.defaultHeaders

        if (request.headers != null) {
            headers.putAll(request.headers)
        }

        try {
            val serverUrl = URL(request.url)
            val protocol = serverUrl.protocol

            /*
             * Only https is supported as of now.
             * No special handling for https is supported for now.
             */
            if (protocol != null && "https".equals(protocol, ignoreCase = true)) {
                try {
                    val httpConnectionHandler: HttpConnectionHandler =
                        HttpConnectionHandler(serverUrl)

                    if (httpConnectionHandler.setCommand(request.method)) {
                        httpConnectionHandler.setRequestProperty(headers)
                        httpConnectionHandler.setConnectTimeout(
                            request.connectTimeout * SEC_TO_MS_MULTIPLIER
                        )
                        // MMMM
                        if (this.isSlowNetwork()) {

                            val readTimeoutForSlowNetwork = 10
                            httpConnectionHandler.setReadTimeout(
                                readTimeoutForSlowNetwork * SEC_TO_MS_MULTIPLIER
                            )
                        } else {
                            httpConnectionHandler.setReadTimeout(
                                request.readTimeout * SEC_TO_MS_MULTIPLIER
                            )
                        }

                        connection = httpConnectionHandler.connect(request.body)
                    }
                } catch (e: IOException) {
                    Log.warning(
                        TAG,
                        TAG,
                        java.lang.String.format(
                            "Could not create a connection to URL (%s) [%s]",
                            request.url,
                            (e.localizedMessage ?: e.message)
                        )
                    )
                } catch (e: SecurityException) {
                    Log.warning(
                        TAG,
                        TAG,
                        java.lang.String.format(
                            "Could not create a connection to URL (%s) [%s]",
                            request.url,
                            (e.localizedMessage ?: e.message)
                        )
                    )
                }
            }
        } catch (e: MalformedURLException) {
            Log.warning(
                TAG,
                TAG,
                java.lang.String.format(
                    "Could not connect, invalid URL (%s) [%s]!!", request.getUrl(), e
                )
            )
        }

        return connection
    }

    private val defaultHeaders: MutableMap<String?, String?>
        /**
         * Creates a `Map<String, String>` with the default headers: default user agent and active
         * language.
         *
         *
         * This method is used to retrieve the default headers to be appended to any network
         * connection made by the SDK.
         *
         * @return `Map<String, String>` containing the default user agent and active language if
         * `#DeviceInforming` is not null or an empty Map otherwise
         * @see DeviceInforming.getDefaultUserAgent
         * @see DeviceInforming.getLocaleString
         */
        get() {
            val defaultHeaders: MutableMap<String?, String?> = HashMap()
            val deviceInfoService =
                ServiceProvider.getInstance().deviceInfoService

            if (deviceInfoService == null) {
                return defaultHeaders
            }

            val userAgent = deviceInfoService.defaultUserAgent

            if (!isNullOrEmpty(userAgent)) {
                defaultHeaders.put(REQUEST_HEADER_KEY_USER_AGENT, userAgent)
            }

            val locale = deviceInfoService.localeString

            if (!isNullOrEmpty(locale)) {
                defaultHeaders.put(REQUEST_HEADER_KEY_LANGUAGE, locale)
            }

            return defaultHeaders
        }

    private fun isNullOrEmpty(str: String?): Boolean {
        return str == null || str.trim { it <= ' ' }.isEmpty()
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager =
            ServiceProvider.getInstance().appContextService.connectivityManager
        if (connectivityManager == null) {
            Log.debug(
                TAG,
                TAG,
                "ConnectivityManager instance is null. Unable to the check the network"
                        + " condition."
            )
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // The getActiveNetwork() API was introduced in API version 23.
            val network = connectivityManager.activeNetwork ?: return false
            val activeCapabilities =
                connectivityManager.getNetworkCapabilities(network) ?: return false
            return activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            return networkInfo.isConnected
        }
    }

    private fun isSlowNetwork(): Boolean {
        val connectivityManager =
            ServiceProvider.getInstance().appContextService.connectivityManager
        if (connectivityManager == null) {
            Log.debug(
                TAG,
                TAG,
                "ConnectivityManager instance is null. Unable to the check the network"
                        + " condition."
            )
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // The getActiveNetwork() API was introduced in API version 23.
            val network = connectivityManager.activeNetwork ?: return false
            val activeCapabilities =
                connectivityManager.getNetworkCapabilities(network) ?: return false
            return activeCapabilities.linkUpstreamBandwidthKbps < 1000
        } else {
            return false
        }
    }

    companion object {
        private val TAG: String = CustomNetworkService::class.java.getSimpleName()
        private const val REQUEST_HEADER_KEY_USER_AGENT = "User-Agent"
        private const val REQUEST_HEADER_KEY_LANGUAGE = "Accept-Language"
        private const val THREAD_POOL_CORE_SIZE = 0
        private const val THREAD_POOL_MAXIMUM_SIZE = 32
        private const val THREAD_POOL_KEEP_ALIVE_TIME = 60
        private const val SEC_TO_MS_MULTIPLIER = 1000
    }
}

private class HttpConnectionHandler(url: URL) {
    private val httpsUrlConnection: HttpsURLConnection = url.openConnection() as HttpsURLConnection
    private var command: Command = Command.GET // Default

    /** Commands supported by this `HttpConnectionHandler`.  */
    private enum class Command
    /**
     * Constructor which initializes the [.doOutputSetting].
     *
     *
     * Set the `doOutputSetting` flag to true if you intend to write data to the URL
     * connection, otherwise set it to false.
     *
     * @param isDoOutput `boolean` indicating whether this command writes to the
     * connection output stream
     */(
        /**
         * Returns the setting specifying whether this command will need to write data to the URL
         * connection.
         *
         * @return `boolean` indicating whether this command writes to the connection output
         * stream
         */
        val isDoOutput: Boolean
    ) {
        GET(false),
        POST(true)
    }

    /**
     * Sets the command to be used for this connection attempt.
     *
     *
     * The command should be set before [.connect] method is called.
     *
     * @param command [HttpMethod] representing the command to be used
     * @return `boolean` indicating whether the command was successfully set
     * @see HttpsURLConnection.setRequestMethod
     */
    fun setCommand(command: HttpMethod?): Boolean {
        if (command == null) {
            return false
        }

        try {
            val requestedCommand = Command.valueOf(command.name)

            // Set the HTTP method for this request. Supported methods - GET/POST.
            httpsUrlConnection.setRequestMethod(requestedCommand.name)

            // Set doOutput flag for this URLConnection. A true value indicates intention to write
            // data to URL connection, read otherwise.
            httpsUrlConnection.setDoOutput(requestedCommand.isDoOutput)
            httpsUrlConnection.setUseCaches(false)
            this.command = requestedCommand
            return true
        } catch (e: ProtocolException) {
            Log.warning(
                TAG,
                TAG,
                String.format("%s is not a valid HTTP command (%s)!", command, e)
            )
        } catch (e: IllegalStateException) {
            Log.warning(
                TAG,
                TAG,
                String.format("Cannot set command after connect (%s)!", e)
            )
        } catch (e: IllegalArgumentException) {
            Log.warning(
                TAG,
                TAG,
                String.format("%s command is not supported (%s)!", command, e)
            )
        } catch (e: java.lang.Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Failed to set http command (%s)!", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Failed to set http command (%s)!", e)
            )
        }

        return false
    }

    /**
     * Sets the header fields specified by the `requestProperty` for the connection.
     *
     *
     * This method should be called before [.connect] is called.
     *
     * @param requestProperty `Map<String, String>` containing the header fields and their
     * values
     * @see HttpsURLConnection.setRequestProperty
     */
    fun setRequestProperty(requestProperty: MutableMap<String?, String?>?) {
        if (requestProperty == null || requestProperty.isEmpty()) {
            return
        }

        val entries = requestProperty.entries

        for (entry in entries) {
            try {
                httpsUrlConnection.setRequestProperty(entry.key, entry.value)
            } catch (e: IllegalStateException) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Cannot set header field after connect (%s)!", e)
                )
                return
            } catch (e: java.lang.Exception) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Failed to set request property (%s)!", e)
                )
            } catch (e: Error) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Failed to set request property (%s)!", e)
                )
            }
        }
    }

    /**
     * Sets the connect timeout value for this connection.
     *
     * @param connectTimeout `int` indicating connect timeout value in milliseconds
     * @see HttpURLConnection.setConnectTimeout
     */
    fun setConnectTimeout(connectTimeout: Int) {
        try {
            httpsUrlConnection.setConnectTimeout(connectTimeout)
        } catch (e: IllegalArgumentException) {
            Log.warning(
                TAG,
                TAG,
                String.format("$connectTimeout is not valid timeout value (%s)", e)
            )
        } catch (e: java.lang.Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Failed to set connection timeout (%s)!", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Failed to set connection timeout (%s)!", e)
            )
        }
    }

    /**
     * Sets the timeout that will be used to wait for a read to finish after a successful connect.
     *
     * @param readTimeout `int` indicating read timeout value in milliseconds
     * @see HttpURLConnection.setReadTimeout
     */
    fun setReadTimeout(readTimeout: Int) {
        try {
            httpsUrlConnection.setReadTimeout(readTimeout)
        } catch (e: IllegalArgumentException) {
            Log.warning(
                TAG,
                TAG,
                String.format("$readTimeout is not valid timeout value (%s)", e)
            )
        } catch (e: java.lang.Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Failed to set read timeout (%s)!", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Failed to set read timeout (%s)!", e)
            )
        }
    }

    /**
     * Performs the actual connection to the resource referenced by this `httpUrlConnection`.
     *
     *
     * If the `command` set for this connection is [Command.POST], then the `payload` will be sent to the server, otherwise ignored.
     *
     * @param payload `byte` array representing the payload to be sent to the server
     * @return [HttpConnecting] instance, representing a connection attempt
     * @see HttpURLConnection.connect
     */
    fun connect(payload: ByteArray?): HttpConnecting {
        Log.debug(
            TAG,
            TAG,
            String.format(
                "Connecting to URL %s (%s)",
                (if (httpsUrlConnection.getURL() == null)
                    ""
                else
                    httpsUrlConnection.getURL().toString()),
                command.toString()
            )
        )

        // If the command to be used is POST, set the length before connection
        if (command == Command.POST && payload != null) {
            httpsUrlConnection.setFixedLengthStreamingMode(payload.size)
        }

        // Try to connect
        try {
            httpsUrlConnection.connect()

            // if the command is POST, send the data to the URL.
            if (command == Command.POST && payload != null) {
                // Consume the payload
                val os: OutputStream = BufferedOutputStream(httpsUrlConnection.getOutputStream())
                os.write(payload)
                os.flush()
                os.close()
            }
        } catch (e: SocketTimeoutException) {
            Log.warning(
                TAG,
                TAG,
                String.format("Connection failure, socket timeout (%s)", e)
            )
        } catch (e: IOException) {
            Log.warning(
                TAG,
                TAG,
                String.format(
                    "Connection failure (%s)",
                    (e.localizedMessage ?: e.message)
                )
            )
        } catch (e: java.lang.Exception) {
            Log.warning(TAG, TAG, String.format("Connection failure (%s)", e))
        } catch (e: Error) {
            Log.warning(TAG, TAG, String.format("Connection failure (%s)", e))
        }

        // Create a connection object here
        // Even if there might be an IOException, let the user query for response code etc.
        return HttpConnection(httpsUrlConnection)
    }

    companion object {
        private val TAG: String = HttpConnectionHandler::class.java.getSimpleName()
    }
}

internal class HttpConnection
/**
 * Constructor
 *
 * @param httpUrlConnection [HttpURLConnection] instance, supports HTTP specific features
 */(private val httpUrlConnection: HttpURLConnection) : HttpConnecting {
    /**
     * Returns an input stream to read the application server response from this open connection, if
     * available.
     *
     *
     * This method invokes [HttpURLConnection.getInputStream] and returns null if `getInputSream()` throws an exception.
     *
     * @return [InputStream] connection response input stream
     */
    override fun getInputStream(): InputStream? {
        try {
            return httpUrlConnection.getInputStream()
        } catch (e: UnknownServiceException) {
            Log.warning(
                TAG,
                TAG,
                String.format(
                    "Could not get the input stream, protocol does not support input. (%s)",
                    e
                )
            )
        } catch (e: Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get the input stream. (%s)", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get the input stream. (%s)", e)
            )
        }

        return null
    }

    /**
     * Returns an input stream from the connection to read the application server error response, if
     * available.
     *
     * @return [InputStream] connection response error stream
     */
    override fun getErrorStream(): InputStream? {
        try {
            return httpUrlConnection.errorStream
        } catch (e: Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get the input stream. (%s)", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get the input stream. (%s)", e)
            )
        }

        return null
    }

    /**
     * Returns the connection attempt response code for this connection request.
     *
     *
     * This method invokes [HttpURLConnection.getResponseCode] and returns -1 if `getResponseCode()` throws an exception or the response is not valid HTTP.
     *
     * @return `int` indicating connection status code
     */
    override fun getResponseCode(): Int {
        try {
            return httpUrlConnection.getResponseCode()
        } catch (e: Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get response code. (%s)", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get response code. (%s)", e)
            )
        }

        return -1
    }

    /**
     * Returns the connection attempt response message for this connection request, if available.
     *
     *
     * This method invokes [HttpURLConnection.getResponseMessage] and returns null if
     * `getResponseMessage()` throws an exception or the result is not valid HTTP.
     *
     * @return [String] containing connection response message
     */
    override fun getResponseMessage(): String? {
        try {
            return httpUrlConnection.getResponseMessage()
        } catch (e: Exception) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get the response message. (%s)", e)
            )
        } catch (e: Error) {
            Log.warning(
                TAG,
                TAG,
                String.format("Could not get the response message. (%s)", e)
            )
        }

        return null
    }

    /**
     * Returns the value of the header field specified by the `responsePropertyKey` that might
     * have been set when a connection was made to the resource pointed to by the URL.
     *
     *
     * This is protocol specific. For example, HTTP urls could have properties like
     * "last-modified", or "ETag" set.
     *
     * @param responsePropertyKey [String] containing response property key
     * @return `String` corresponding to the response property value for the key specified, or
     * null, if the key does not exist
     */
    override fun getResponsePropertyValue(responsePropertyKey: String?): String? {
        return httpUrlConnection.getHeaderField(responsePropertyKey)
    }

    /**
     * Closes this open connection.
     *
     *
     * Invokes [HttpURLConnection.disconnect] method to release the resources for this
     * connection.
     */
    override fun close() {
        val inputStream = this.getInputStream()

        val errorStream = this.getErrorStream()
        if (inputStream != null) {
            try {
                inputStream.close()
            } catch (e: Exception) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Could not close the input stream. (%s)", e)
                )
            } catch (e: Error) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Could not close the input stream. (%s)", e)
                )
            }
        }
        if (errorStream != null) {
            try {
                errorStream.close()
            } catch (e: Exception) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Could not close the error stream. (%s)", e)
                )
            } catch (e: Error) {
                Log.warning(
                    TAG,
                    TAG,
                    String.format("Could not close the error stream. (%s)", e)
                )
            }
        }

        httpUrlConnection.disconnect()
    }

    companion object {
        private val TAG: String = HttpConnection::class.java.getSimpleName()
    }
}


