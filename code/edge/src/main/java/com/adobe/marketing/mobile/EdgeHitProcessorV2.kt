package com.adobe.marketing.mobile

import com.adobe.marketing.mobile.EdgeNetworkService.ResponseCallback
import com.adobe.marketing.mobile.edge.Datastream
import com.adobe.marketing.mobile.edge.SDKConfig
import com.adobe.marketing.mobile.services.DataEntity
import com.adobe.marketing.mobile.services.HitProcessingV2
import com.adobe.marketing.mobile.services.Log
import com.adobe.marketing.mobile.services.NamedCollection
import com.adobe.marketing.mobile.util.DataReader
import com.adobe.marketing.mobile.util.MapUtils
import com.adobe.marketing.mobile.util.StringUtils
import com.adobe.marketing.mobile.util.UrlUtils
import org.json.JSONException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Handles the processing of [EdgeDataEntity]s, sending network requests.
 */
internal class EdgeHitProcessorV2(
    private val networkResponseHandler: NetworkResponseHandler,
    namedCollection: NamedCollection,
    callback: EdgeSharedStateCallback?,
    stateCallback: EdgeStateCallback?
) : HitProcessingV2 {
    private val namedCollection: NamedCollection
    private val sharedStateCallback: EdgeSharedStateCallback?
    private val stateCallback: EdgeStateCallback?
    private val entityRetryIntervalMapping = ConcurrentHashMap<String, Int>()

    init {
        this.namedCollection = namedCollection
        sharedStateCallback = callback
        this.stateCallback = stateCallback
    }

    override fun retryInterval(dataEntity: DataEntity): Int {
        val retryInterval = entityRetryIntervalMapping[dataEntity.uniqueIdentifier]
        return retryInterval ?: EdgeConstants.Defaults.RETRY_INTERVAL_SECONDS
    }

    /**
     * Send network requests out with the data encapsulated in [DataEntity].
     * If configuration is null, the processing is paused.
     *
     * @param dataEntity the `DataEntity` to be processed at this time; should not be null
     * @param processingResult the `HitProcessingResult` callback to be invoked with the result of the processing. Returns:
     * true when the `entity` was processed and it can be removed from the queue,
     * false when the processing failed and the `entity` should be retried at a later point.
     */
    override suspend fun processHit(dataEntity: DataEntity): Boolean {
        val entity = EdgeDataEntity.fromDataEntity(dataEntity)
        if (entity == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Unable to deserialize DataEntity to EdgeDataEntity. Dropping the hit."
            )
            return true
        }

        // Add in Identity Map at request (global) level
        val request = RequestBuilder(namedCollection)
        request.addXdmPayload(entity.identityMap)

        // Enable response streaming for all events
        request.enableResponseStreaming(
            EdgeConstants.Defaults.REQUEST_CONFIG_RECORD_SEPARATOR,
            EdgeConstants.Defaults.REQUEST_CONFIG_LINE_FEED
        )
        var hitCompleteResult = true
        if (EventUtils.isExperienceEvent(entity.event)) {
            hitCompleteResult =
                processExperienceEventHit(dataEntity.uniqueIdentifier, entity, request)
        } else if (EventUtils.isUpdateConsentEvent(entity.event)) {
            hitCompleteResult =
                processUpdateConsentEventHit(dataEntity.uniqueIdentifier, entity, request)
        } else if (EventUtils.isResetComplete(entity.event)) {
            // clear state store
            val payloadManager = StoreResponsePayloadManager(namedCollection)
            payloadManager.deleteAllStorePayloads()
            hitCompleteResult = true // Request complete, don't retry hit
        }
        return hitCompleteResult
    }

    /**
     * Sends a network call to Experience Edge Network with the provided information in [EdgeHit].
     * Two response handlers are registered for this network request, for response content and eventual request errors.
     * @param entityId the unique id of the entity being processed
     * @param edgeHit the Edge request to be sent; should not be null
     * @param requestHeaders the headers for the network requests
     * @return true if sending the hit is complete, false if sending the hit should be retried at a later time
     */
    suspend fun sendNetworkRequest(
        entityId: String?,
        edgeHit: EdgeHit?,
        requestHeaders: Map<String, String>?
    ): Boolean {
        if (edgeHit == null || edgeHit.payload == null || edgeHit.payload.length() == 0) {
            Log.warning(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Request body was null/empty, dropping this request"
            )
            return true
        }
        val responseCallback: ResponseCallback = object : ResponseCallback {
            override fun onResponse(jsonResponse: String) {
                networkResponseHandler.processResponseOnSuccess(jsonResponse, edgeHit.requestId)
            }

            override fun onError(jsonError: String) {
                networkResponseHandler.processResponseOnError(jsonError, edgeHit.requestId)
            }

            override fun onComplete() {
                networkResponseHandler.processResponseOnComplete(edgeHit.requestId)
            }
        }
        val url = EdgeNetworkServiceV2.buildUrl(
            edgeHit.edgeEndpoint,
            edgeHit.datastreamId,
            edgeHit.requestId
        )
        if (!isValidUrl(url)) {
            Log.warning(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Unable to send network request for entity (%s) as URL is malformed or scheme is not 'https', '%s'.",
                entityId,
                url
            )
            return true
        }
        try {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Sending network request with id (%s) to URL '%s' with body:\n%s",
                edgeHit.requestId,
                url,
                edgeHit.payload.toString(2)
            )
        } catch (e: JSONException) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Sending network request with id (%s) to URL '%s'\nError parsing JSON request: %s",
                edgeHit.requestId,
                url,
                e.localizedMessage
            )
        }
        val retryResult = EdgeNetworkServiceV2().doRequest(
            url,
            edgeHit.payload.toString(),
            requestHeaders,
            responseCallback
        )
        return if (retryResult == null || retryResult.shouldRetry == EdgeNetworkService.Retry.NO) {
            if (entityId != null) {
                entityRetryIntervalMapping.remove(entityId)
            }
            true // Hit sent successfully
        } else {
            if (entityId != null &&
                retryResult.retryIntervalSeconds != EdgeConstants.Defaults.RETRY_INTERVAL_SECONDS
            ) {
                entityRetryIntervalMapping[entityId] = retryResult.retryIntervalSeconds
            }
            false // Hit failed to send, retry after interval
        }
    }

    /**
     * Validates a given URL.
     * Checks that a URL is valid by ensuring:
     *
     *  * The URL is not null or empty.
     *  * The URL is parsable by the [java.net.URL] class.
     *  * The URL scheme is "HTTPS".
     *
     * @param url the URL string to validate
     * @return true if the URL is valid, false otherwise.
     */
    private fun isValidUrl(url: String): Boolean {
        if (!UrlUtils.isValidUrl(url)) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Request invalid, URL is malformed, '%s'.",
                url
            )
            return false
        }
        if (!url.startsWith("https")) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Request invalid, URL scheme must be 'https', '%s'.",
                url
            )
            return false
        }
        return true
    }

    /**
     * Processes configuration overrides for the event. Returns datastream Id value to be used
     * for the current event based on the overrides provided for the event.
     *
     * @param eventConfigMap a [Map] containing configuration overrides.
     * @param request a [RequestBuilder] instance for the current event.
     * @param datastreamId the default datastream ID from the SDK configuration.
     * @return the datastream ID to be used for the current event.
     */
    private fun processEventConfigOverrides(
        eventConfigMap: Map<String, Any?>,
        request: RequestBuilder,
        datastreamId: String
    ): String {
        // Check if datastream ID override is present
        val datastreamIdOverride = DataReader.optString(
            eventConfigMap,
            EdgeConstants.EventDataKeys.Config.DATASTREAM_ID_OVERRIDE,
            null
        )
        if (!StringUtils.isNullOrEmpty(datastreamIdOverride)) {
            // Attach original datastream ID to the outgoing request
            request.addSdkConfig(SDKConfig(Datastream(datastreamId)))
        }

        // Check if datastream config override is present
        val datastreamConfigOverride = DataReader.optTypedMap(
            Any::class.java,
            eventConfigMap,
            EdgeConstants.EventDataKeys.Config.DATASTREAM_CONFIG_OVERRIDE,
            null
        )
        if (!MapUtils.isNullOrEmpty(datastreamConfigOverride)) {
            // Attach datastream config override to the outgoing request metadata
            request.addConfigOverrides(datastreamConfigOverride)
        }
        return if (StringUtils.isNullOrEmpty(datastreamIdOverride)) datastreamId else datastreamIdOverride
    }

    /**
     * Process and send an ExperienceEvent network request.
     *
     * @param entityId the [DataEntity] unique identifier
     * @param entity the [EdgeDataEntity] which encapsulates the request data
     * @param request a [RequestBuilder] instance
     * @return true if the request processing is complete for this hit or false if processing is
     * not complete and this hit must be retired.
     */
    private suspend fun processExperienceEventHit(
        entityId: String,
        entity: EdgeDataEntity,
        request: RequestBuilder
    ): Boolean {
        if (stateCallback != null) {
            // Add Implementation Details to request (global) level
            request.addXdmPayload(stateCallback.implementationDetails)
        }
        val edgeConfig = entity.configuration
        var datastreamId = DataReader.optString(
            edgeConfig,
            EdgeConstants.SharedState.Configuration.EDGE_CONFIG_ID,
            null
        )

        // Get config map containing overrides from the event
//        val eventConfigMap = EventUtils.getConfig(entity.event)
//        datastreamId = processEventConfigOverrides(eventConfigMap, request, datastreamId)
//        if (StringUtils.isNullOrEmpty(datastreamId)) {
//            // The Edge configuration ID value should get validated when creating the Hit,
//            // so we shouldn't get here in production.
//            Log.debug(
//                EdgeConstants.LOG_TAG,
//                LOG_SOURCE,
//                "Cannot process Experience Event hit as the Edge Network configuration ID is null or empty, dropping current event (%s).",
//                entity.event.uniqueIdentifier
//            )
//            return true // Request complete, don't retry hit
//        }
        val listOfEvents: MutableList<Event> = ArrayList()
        listOfEvents.add(entity.event)
        val requestPayload = request.getPayloadWithExperienceEvents(listOfEvents)
        if (requestPayload == null) {
            Log.warning(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Failed to build the request payload, dropping current event (%s).",
                entity.event.uniqueIdentifier
            )
            return true // Request complete, don't retry hit
        }
        val requestProperties = getRequestProperties(entity.event)
        val edgeEndpoint = getEdgeEndpoint(
            EdgeNetworkService.RequestType.INTERACT,
            edgeConfig,
            requestProperties
        )
        val edgeHit = EdgeHit(datastreamId, requestPayload, edgeEndpoint)

        // NOTE: the order of these events need to be maintained as they were sent in the network request
        // otherwise the response callback cannot be matched
        networkResponseHandler.addWaitingEvents(edgeHit.requestId, listOfEvents)
        val requestHeaders = requestHeaders
        return sendNetworkRequest(entityId, edgeHit, requestHeaders)
    }

    /**
     * Process and send an Update Consent network request.
     *
     * @param entityId the [DataEntity] unique identifier
     * @param entity the [EdgeDataEntity] which encapsulates the request data
     * @param request a [RequestBuilder] instance
     * @return true if the request processing is complete for this hit or false if processing is
     * not complete and this hit must be retired.
     */
    private suspend fun processUpdateConsentEventHit(
        entityId: String,
        entity: EdgeDataEntity,
        request: RequestBuilder
    ): Boolean {
        // Build and send the consent network request to Experience Edge
        val consentPayload = request.getConsentPayload(entity.event)
        if (consentPayload == null) {
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Failed to build the consent payload, dropping current event (%s).",
                entity.event.uniqueIdentifier
            )
            return true // Request complete, don't retry hit
        }
        val edgeConfig = entity.configuration
        val datastreamId = DataReader.optString(
            edgeConfig,
            EdgeConstants.SharedState.Configuration.EDGE_CONFIG_ID,
            null
        )
        if (StringUtils.isNullOrEmpty(datastreamId)) {
            // The Edge configuration ID value should get validated when creating the Hit,
            // so we shouldn't get here in production.
            Log.debug(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Cannot process Update Consent hit as the Edge Network configuration ID is null or empty, dropping current event (%s).",
                entity.event.uniqueIdentifier
            )
            return true // Request complete, don't retry hit
        }
        val edgeEndpoint = getEdgeEndpoint(EdgeNetworkService.RequestType.CONSENT, edgeConfig, null)
        val edgeHit = EdgeHit(datastreamId, consentPayload, edgeEndpoint)
        networkResponseHandler.addWaitingEvent(edgeHit.requestId, entity.event)
        val requestHeaders = requestHeaders
        return sendNetworkRequest(entityId, edgeHit, requestHeaders)
    }

    /**
     * Creates a new instance of [EdgeEndpoint] using the values provided in `edgeConfiguration`.
     * @param edgeConfiguration the current Edge configuration
     * @return a new `EdgeEndpoint` instance
     */
    private fun getEdgeEndpoint(
        requestType: EdgeNetworkService.RequestType,
        edgeConfiguration: Map<String, Any?>,
        requestProperties: Map<String, Any?>?
    ): EdgeEndpoint {
        // Use null fallback value, which defaults to Prod environment when building EdgeEndpoint
        val requestEnvironment = DataReader.optString(
            edgeConfiguration,
            EdgeConstants.SharedState.Configuration.EDGE_REQUEST_ENVIRONMENT,
            null
        )
        // Use null fallback value, which defaults to default request domain when building EdgeEndpoint
        val requestDomain = DataReader.optString(
            edgeConfiguration,
            EdgeConstants.SharedState.Configuration.EDGE_DOMAIN,
            null
        )
        val locationHint = stateCallback?.locationHint

        // Use null fallback value for request without custom path value
        val customPath =
            DataReader.optString(requestProperties, EdgeConstants.EventDataKeys.Request.PATH, null)
        return EdgeEndpoint(
            requestType,
            requestEnvironment,
            requestDomain,
            customPath,
            locationHint
        )
    }

    /**
     * Extracts all the custom request properties to overwrite the default values
     * @param event current event for which the request properties are to be extracted
     * @return the map of extracted request properties and their custom values
     */
    private fun getRequestProperties(event: Event): Map<String, Any?> {
        val requestProperties: MutableMap<String, Any?> = HashMap()
        val overwritePath = getCustomRequestPath(event)
        if (!StringUtils.isNullOrEmpty(overwritePath)) {
            Log.trace(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Got custom path:(%s) for event:(%s), which will overwrite the default interaction request path.",
                overwritePath,
                event.uniqueIdentifier
            )
            requestProperties[EdgeConstants.EventDataKeys.Request.PATH] = overwritePath
        }
        return requestProperties
    }

    /**
     * Extracts network request path property to overwrite the default endpoint path value
     * @param event current event for which the request path property is to be extracted
     * @return the custom path string
     */
    private fun getCustomRequestPath(event: Event): String? {
        val requestData = DataReader.optTypedMap(
            Any::class.java,
            event.eventData,
            EdgeConstants.EventDataKeys.Request.KEY,
            null
        )
        val path = DataReader.optString(requestData, EdgeConstants.EventDataKeys.Request.PATH, null)
        if (StringUtils.isNullOrEmpty(path)) {
            return null
        }
        if (!isValidPath(path)) {
            Log.error(
                EdgeConstants.LOG_TAG,
                LOG_SOURCE,
                "Dropping the overwrite path value: (%s), since it contains invalid characters or is empty or null.",
                path
            )
            return null
        }
        return path
    }

    /**
     * Validates a given path does not contain invalid characters.
     * A 'path'  may only contain alphanumeric characters, forward slash, period, hyphen, underscore, or tilde, but may not contain a double forward slash.
     * @param path the path to validate
     * @return true if 'path' passes validation, false if 'path' contains invalid characters.
     */
    private fun isValidPath(path: String): Boolean {
        if (path.contains("//")) {
            return false
        }
        val matcher = pattern.matcher(path)
        return matcher.find()
    }

    private val requestHeaders: Map<String, String>
        /**
         * Computes the request headers, including the `Assurance` integration identifier when it is enabled
         * @return the network request headers or empty if none should be attached to the request
         */
        private get() {
            val requestHeaders: MutableMap<String, String> = HashMap()
            if (sharedStateCallback == null) {
                Log.debug(
                    EdgeConstants.LOG_TAG,
                    LOG_SOURCE,
                    "Unexpected null sharedStateCallback, unable to fetch Assurance shared state."
                )
                return requestHeaders
            }

            // get latest Assurance shared state
            val assuranceStateResult = sharedStateCallback.getSharedState(
                EdgeConstants.SharedState.ASSURANCE,
                null
            )
            if (assuranceStateResult == null || assuranceStateResult.status != SharedStateStatus.SET) {
                return requestHeaders
            }
            val assuranceIntegrationId = DataReader.optString(
                assuranceStateResult.value,
                EdgeConstants.SharedState.Assurance.INTEGRATION_ID,
                null
            )
            if (!StringUtils.isNullOrEmpty(assuranceIntegrationId)) {
                requestHeaders[EdgeConstants.NetworkKeys.HEADER_KEY_AEP_VALIDATION_TOKEN] =
                    assuranceIntegrationId
            }
            return requestHeaders
        }

    companion object {
        private const val LOG_SOURCE = "EdgeHitProcessor"
        private const val VALID_PATH_REGEX_PATTERN = "^\\/[/.a-zA-Z0-9-~_]+$"
        private val pattern = Pattern.compile(VALID_PATH_REGEX_PATTERN)
    }
}
