package com.adobe.marketing.mobile

import com.adobe.marketing.mobile.services.HttpConnecting
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.Log
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.util.StringUtils
import com.adobe.marketing.mobile.util.SuspendableNetworkServices
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.Arrays
import java.util.Scanner
import java.util.concurrent.CountDownLatch

/**
 * Network service for requests to the Adobe Edge Network.
 */
internal class EdgeNetworkServiceV2() {
    /**
     * Edge Request Type.
     *
     *  * INTERACT - makes request and expects a response
     *  * CONSENT - makes request to the consent endpoint, expects a response
     *
     */
    enum class RequestType(@JvmField val type: String) {
        INTERACT("interact"),
        CONSENT("privacy/set-consent")
    }
    /**
     * Make a request to the Adobe Edge Network. On successful request, response content is sent to
     * the given [ResponseCallback.onResponse] handler. If streaming is enabled on
     * request, [ResponseCallback.onResponse] is called multiple times. On error, the
     * given [ResponseCallback.onError] is called with an error message.  The
     * `propertyId` is required.
     *
     * @param url              url to the Adobe Edge Network
     * @param jsonRequest      the request body as a JSON formatted string
     * @param requestHeaders   the HTTP headers to attach to the request
     * @param responseCallback optional callback to receive the Adobe Edge Network response; the
     * [ResponseCallback.onComplete] callback is invoked when no retry
     * is required, otherwise it is the caller's responsibility to do any
     * necessary cleanup
     * @return [Retry] status indicating if the request failed due to a recoverable error and
     * should be retried
     */
    suspend fun doRequest(
        url: String,
        jsonRequest: String,
        requestHeaders: Map<String, String>?,
        responseCallback: EdgeNetworkService.ResponseCallback
    ): RetryResult {
        if (StringUtils.isNullOrEmpty(url)) {
            Log.error(EdgeConstants.LOG_TAG, LOG_SOURCE, "Could not send request to a null url")
            responseCallback?.onComplete()
            return RetryResult(EdgeNetworkService.Retry.NO)
        }
        val connection = doConnect(url, jsonRequest, requestHeaders)
        if (connection == null) {
            val retryResult = RetryResult(EdgeNetworkService.Retry.YES)
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Network request returned null connection. Will retry request in %d seconds.",
                retryResult.retryIntervalSeconds
            )
            return retryResult
        }
        var retryResult = RetryResult(EdgeNetworkService.Retry.NO)
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Interact connection to Experience Edge successful. Response message: " +
                        connection.responseMessage
            )
            val konductorConfig = KonductorConfig.fromJsonRequest(jsonRequest)
            val shouldStreamResponse = konductorConfig != null && konductorConfig.isStreamingEnabled
            handleContent(
                connection.inputStream,
                if (shouldStreamResponse) konductorConfig!!.recordSeparator else null,
                if (shouldStreamResponse) konductorConfig!!.lineFeed else null,
                responseCallback
            )
        } else if (connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
            // Successful collect requests do not return content
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Interact connection to Experience Edge successful. Response message: " +
                        connection.responseMessage
            )
        } else if (recoverableNetworkErrorCodes.contains(connection.responseCode)) {
            retryResult = RetryResult(EdgeNetworkService.Retry.YES, computeRetryInterval(connection))
            if (connection.responseCode == -1) {
                Log.debug(
                    EdgeConstants.LOG_TAG,
                    LOG_SOURCE,
                    "Connection to Experience Edge failed. Failed to read message/error code from NetworkService. Will retry request in %d seconds.",
                    retryResult.retryIntervalSeconds
                )
            } else {
                Log.debug(
                    EdgeConstants.LOG_TAG,
                    LOG_SOURCE,
                    "Connection to Experience Edge returned recoverable error code (%d). Response message: %s. Will retry request in %d seconds.",
                    connection.responseCode,
                    connection.responseMessage,
                    retryResult.retryIntervalSeconds
                )
            }
        } else if (connection.responseCode == 207) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Interact connection to Experience Edge successful but encountered non-fatal errors/warnings. Response message: %s",
                connection.responseMessage
            )
            val konductorConfig = KonductorConfig.fromJsonRequest(jsonRequest)
            val shouldStreamResponse = konductorConfig != null && konductorConfig.isStreamingEnabled
            handleContent(
                connection.inputStream,
                if (shouldStreamResponse) konductorConfig!!.recordSeparator else null,
                if (shouldStreamResponse) konductorConfig!!.lineFeed else null,
                responseCallback
            )
        } else {
            Log.warning(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Connection to Experience Edge returned unrecoverable error code (%d). Response message: %s",
                connection.responseCode,
                connection.responseMessage
            )
            handleError(connection.errorStream, responseCallback)
        }
        connection.close()
        if (retryResult.shouldRetry == EdgeNetworkService.Retry.NO && responseCallback != null) {
            responseCallback.onComplete()
        }
        return retryResult
    }

    /**
     * Computes the retry interval for the given network connection
     *
     * @param connection the network connection that needs to be retried
     * @return the retry interval in seconds
     */
    private fun computeRetryInterval(connection: HttpConnecting): Int {
        val header = connection.getResponsePropertyValue(
            EdgeConstants.NetworkKeys.HEADER_KEY_RETRY_AFTER
        )
        if (header != null && header.matches("\\d+".toRegex())) {
            try {
                return header.toInt()
            } catch (e: NumberFormatException) {
                Log.debug(
                    EdgeConstants.LOG_TAG,
                    LOG_SOURCE,
                    "Failed to parse Retry-After header with value of '%s' to an int with error: %s",
                    header,
                    e.localizedMessage
                )
            }
        }
        return EdgeConstants.Defaults.RETRY_INTERVAL_SECONDS
    }

    /**
     * Make a network request to the Adobe Edge Network and return the connection object.
     *
     * @param url            URL to the Adobe Edge Network. Must contain the required config ID as a
     * query parameter
     * @param jsonRequest    the request body as a JSON formatted string
     * @param requestHeaders HTTP headers to be included with the request
     * @return [HttpConnecting] object once the connection was initiated or null if an error
     * occurred
     */
    private suspend fun doConnect(
        url: String,
        jsonRequest: String,
        requestHeaders: Map<String, String>?
    ): HttpConnecting? {
        val headers = defaultHeaders
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            headers.putAll(requestHeaders)
        }
        Log.trace(EdgeConstants.LOG_TAG, LOG_SOURCE, "HTTP Headers: $headers")
        val networkRequest = NetworkRequest(
            url,
            HttpMethod.POST,
            jsonRequest.toByteArray(),
            headers,
            EdgeConstants.NetworkKeys.DEFAULT_CONNECT_TIMEOUT_SECONDS,
            EdgeConstants.NetworkKeys.DEFAULT_READ_TIMEOUT_SECONDS
        )
        val countDownLatch = CountDownLatch(1)
        val httpConnecting = arrayOfNulls<HttpConnecting>(1)
        return SuspendableNetworkServices.connect(networkRequest)
    }

    /**
     * Attempt the read the response from the connection's [InputStream] and return the content
     * in the [ResponseCallback]. This method should be used for handling 2xx server response.
     *
     *
     * In the eventuality of an error, this method returns false and an error message is logged.
     *
     * @param inputStream       the content to process
     * @param recordSeparator   the record separator
     * @param lineFeedDelimiter line feed delimiter
     * @param responseCallback  `ResponseCallback` used for returning the response content
     */
    private fun handleContent(
        inputStream: InputStream?,
        recordSeparator: String?,
        lineFeedDelimiter: String?,
        responseCallback: EdgeNetworkService.ResponseCallback?
    ) {
        if (responseCallback == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Callback is null, processing of response content aborted."
            )
            return
        }
        if (inputStream == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Network response contains no data, InputStream is null."
            )
            return
        }
        if (recordSeparator != null && lineFeedDelimiter != null) {
            handleStreamingResponse(
                inputStream,
                recordSeparator,
                lineFeedDelimiter,
                responseCallback
            )
        } else {
            handleNonStreamingResponse(inputStream, responseCallback)
        }
    }

    /**
     * Attempt to read the streamed response from the [InputStream] and return the content via
     * the [ResponseCallback].
     *
     * @param inputStream       the content to process
     * @param recordSeparator   the record separator
     * @param lineFeedDelimiter line feed delimiter
     * @param responseCallback  `ResponseCallback` used for returning the streamed response
     * content
     */
    fun handleStreamingResponse(
        inputStream: InputStream?,
        recordSeparator: String?,
        lineFeedDelimiter: String?,
        responseCallback: EdgeNetworkService.ResponseCallback?
    ) {
        if (inputStream == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Network response contains no data, InputStream is null."
            )
            return
        }
        if (recordSeparator == null) {
            Log.debug(
                EdgeConstants.LOG_TAG, LOG_SOURCE,
                "record separator is null, processing of response content aborted."
            )
            return
        }
        if (lineFeedDelimiter == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "line feed is null, processing of response content aborted."
            )
            return
        }
        if (responseCallback == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Callback is null, processing of response content aborted."
            )
            return
        }
        val scanner = Scanner(inputStream, "UTF-8")
        scanner.useDelimiter(lineFeedDelimiter)
        val trimLength = recordSeparator.length
        while (scanner.hasNext()) {
            val jsonResult = scanner.next()
            if (jsonResult.length - trimLength < 0) {
                Log.debug(
                    EdgeConstants.LOG_TAG,
                    LOG_SOURCE,
                    "Unexpected network response chunk is shorter than record separator '%s'. Ignoring response '%s'.",
                    recordSeparator,
                    jsonResult
                )
                continue
            }
            responseCallback.onResponse(jsonResult.substring(trimLength))
        }
    }

    /**
     * Attempt to read the response from the connection's [InputStream] and returns it in the
     * [ResponseCallback].
     *
     * @param inputStream      to read the response content from
     * @param responseCallback invoked by calling `onResponse` with the parsed response content
     */
    fun handleNonStreamingResponse(
        inputStream: InputStream?,
        responseCallback: EdgeNetworkService.ResponseCallback?
    ) {
        if (responseCallback == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Callback is null, processing of response content aborted."
            )
            return
        }
        if (inputStream == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Network response contains no data, InputStream is null."
            )
            return
        }
        responseCallback.onResponse(readInputStream(inputStream))
    }

    /**
     * Attempt to read the error response from the connection's error [InputStream] and returns
     * it in the [ResponseCallback.onError].
     *
     * @param inputStream      to read the connection error from
     * @param responseCallback invoked by calling `onError` with the parsed error information
     */
    private fun handleError(inputStream: InputStream?, responseCallback: EdgeNetworkService.ResponseCallback?) {
        if (responseCallback == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Callback is null, processing of error content aborted."
            )
            return
        }
        if (inputStream == null) {
            Log.debug(
                EdgeConstants.LOG_TAG, LOG_SOURCE,
                "Network response contains no data, error InputStream is null."
            )
            responseCallback.onError(composeGenericErrorAsJson(null))
            return
        }

        /*
     * Assuming single JSON response and not a stream; the generic errors are be sent as one chunk
     */
        var responseStr = readInputStream(inputStream)

        /*
     * The generic errors can be provided by Edge Network or JAG. JAG doesn't guarantee that the
     * response has JSON format, it can also be plain text, need to handle both situations.
     */try {
            if (responseStr != null) {
                JSONObject(responseStr) // test if valid JSON
            } else {
                responseStr = composeGenericErrorAsJson(null)
            }
        } catch (e: JSONException) {
            responseStr = composeGenericErrorAsJson(responseStr)
            Log.warning(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Network response has Content-Type application/json, but cannot be parsed as JSON, returning generic error"
            )
        }
        responseCallback.onError(responseStr)
    }

    private val defaultHeaders: MutableMap<String, String>
        private get() {
            val defaultHeaders: MutableMap<String, String> = HashMap()
            defaultHeaders[EdgeConstants.NetworkKeys.HEADER_KEY_ACCEPT] =
                EdgeConstants.NetworkKeys.HEADER_VALUE_APPLICATION_JSON
            defaultHeaders[EdgeConstants.NetworkKeys.HEADER_KEY_CONTENT_TYPE] =
                EdgeConstants.NetworkKeys.HEADER_VALUE_APPLICATION_JSON
            return defaultHeaders
        }

    /**
     * Composes a generic error (string with JSON format), containing generic namespace and the
     * provided error message, after removing the leading and trailing spaces.
     *
     * @param plainTextErrorMessage error message to be formatted; if null/empty is provided, a
     * default error message is returned.
     * @return the JSON formatted error
     */
    private fun composeGenericErrorAsJson(plainTextErrorMessage: String?): String {
        // trim the error message or set the default generic message if no value is provided
        var errorMessage =
            if (StringUtils.isNullOrEmpty(plainTextErrorMessage)) DEFAULT_GENERIC_ERROR_MESSAGE else plainTextErrorMessage!!.trim { it <= ' ' }
        errorMessage = if (errorMessage.isEmpty()) DEFAULT_GENERIC_ERROR_MESSAGE else errorMessage
        val json = JSONObject()
        try {
            json.put(EdgeJson.Response.Error.TITLE, errorMessage)
            json.put(EdgeJson.Response.Error.TYPE, DEFAULT_NAMESPACE)
        } catch (e: JSONException) {
            Log.debug(
                EdgeConstants.LOG_TAG, LOG_SOURCE,
                "Failed to create the generic error json " + e.localizedMessage
            )
        }
        return json.toString()
    }

    /**
     * Reads all the contents of an input stream out into a [String]
     *
     * @param inputStream the input stream to be read from
     * @return the contents of the input stream as a string
     */
    private fun readInputStream(inputStream: InputStream?): String? {
        if (inputStream == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Network response contains no data, InputStream is null."
            )
            return null
        }
        val reader = BufferedReader(InputStreamReader(inputStream))
        return try {
            val newLine = System.getProperty("line.separator")
            val stringBuilder = StringBuilder()
            var line: String?
            var newLineFlag = false
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(if (newLineFlag) newLine else "").append(line)
                newLineFlag = true
            }
            stringBuilder.toString()
        } catch (e: IOException) {
            Log.warning(
                EdgeConstants.LOG_TAG, LOG_SOURCE,
                "Exception reading network error response: " + e.localizedMessage
            )
            composeGenericErrorAsJson(e.message)
        }
    }

    companion object {
        private const val LOG_SOURCE = "EdgeNetworkService"
        private const val DEFAULT_NAMESPACE = "global"
        private const val DEFAULT_GENERIC_ERROR_MESSAGE =
            "Request to Edge Network failed with an unknown exception"
        val recoverableNetworkErrorCodes: List<Int> = ArrayList(
            Arrays.asList(
                -1,  // returned for SocketTimeoutException
                429,  // too many requests - The user has sent too many requests in a given amount of time ("rate limiting").
                HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                HttpURLConnection.HTTP_BAD_GATEWAY,
                HttpURLConnection.HTTP_UNAVAILABLE,
                HttpURLConnection.HTTP_GATEWAY_TIMEOUT
            )
        )
        /**
         * @param edgeEndpoint the [EdgeEndpoint] containing the base Experience Edge endpoint
         * @param configId     required globally unique identifier
         * @param requestId    optional request ID. If one is not given, the Adobe Edge Network generates
         * one in the response
         * @return the computed URL
         */
        fun buildUrl(
            edgeEndpoint: EdgeEndpoint, configId: String?,
            requestId: String?
        ): String {
            val url = StringBuilder(edgeEndpoint.endpoint)
            url.append("?").append(EdgeConstants.NetworkKeys.REQUEST_PARAMETER_KEY_CONFIG_ID)
                .append("=")
                .append(configId)
            if (requestId != null && !requestId.isEmpty()) {
                url
                    .append("&")
                    .append(EdgeConstants.NetworkKeys.REQUEST_PARAMETER_KEY_REQUEST_ID)
                    .append("=")
                    .append(requestId)
            }
            return url.toString()
        }
    }
}
