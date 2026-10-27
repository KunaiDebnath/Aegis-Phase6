package com.example.aegis

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class SOSRequest(
    val device_id: String,
    val idempotency_key: String,
    val trigger_source: String = "manual",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val location_accuracy_meters: Float? = null,
    val location_timestamp: String? = null
)

data class SOSResponse(
    val event_id: String,
    val status: String,
    val message: String,
    val trigger_source: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val location_accuracy_meters: Float? = null,
    val location_timestamp: String? = null
)

data class CallTokenRequest(
    val event_id: String,
    val device_id: String,
    val participant_name: String? = null
)

data class CallTokenResponse(
    val server_url: String,
    val token: String,
    val room_name: String,
    val identity: String,
    val expires_in_seconds: Int
)

data class CreateInvitationRequest(
    val emergency_event_id: String,
    val caller_device_id: String,
    val recipient_device_id: String,
    val caller_display_name: String? = null,
    val timeout_seconds: Int = 60
)

data class CreateInvitationResponse(
    val id: String,
    val emergency_event_id: String,
    val caller_device_id: String,
    val recipient_device_id: String,
    val caller_display_name: String?,
    val status: String,
    val created_at: String,
    val expires_at: String,
    val fcm_status: String? = null,
    val message: String? = null
)

data class InvitationDetailsResponse(
    val id: String,
    val emergency_event_id: String,
    val caller_device_id: String,
    val recipient_device_id: String,
    val caller_display_name: String?,
    val status: String,
    val created_at: String,
    val expires_at: String,
    val responded_at: String? = null
)

data class AnswerInvitationRequest(
    val device_id: String,
    val participant_name: String? = null
)

data class AnswerInvitationResponse(
    val invitation_id: String,
    val status: String,
    val emergency_event_id: String,
    val server_url: String,
    val token: String,
    val room_name: String,
    val identity: String,
    val expires_in_seconds: Int
)

data class DismissInvitationRequest(
    val device_id: String
)

data class DismissInvitationResponse(
    val invitation_id: String,
    val status: String,
    val message: String
)

data class CancelInvitationRequest(
    val device_id: String
)

data class CancelInvitationResponse(
    val invitation_id: String,
    val status: String,
    val message: String
)

data class PairContactRequest(
    val owner_device_id: String,
    val contact_device_id: String,
    val contact_name: String? = null,
    val contact_phone: String? = null
)

data class PairContactResponse(
    val id: String,
    val owner_device_id: String,
    val contact_device_id: String,
    val contact_name: String?,
    val contact_phone: String?,
    val message: String
)

data class RegisterFcmTokenRequest(
    val device_id: String,
    val fcm_token: String
)

data class ServerDiscoveryResponse(
    val service: String,
    val status: String,
    val server_name: String? = null,
    val lan_ip: String,
    val api_port: Int = 8000,
    val api_base_url: String,
    val livekit_url: String,
    val livekit_port: Int = 7880,
    val version: String? = null
)

interface AegisApi {

    @GET("health")
    suspend fun getHealthCheck(): Response<Map<String, Any?>>

    @GET("api/discovery")
    suspend fun getDiscoveryMetadata(): Response<ServerDiscoveryResponse>

    @POST("api/emergencies")
    suspend fun createEmergency(
        @Body request: SOSRequest
    ): Response<SOSResponse>

    @POST("api/calls/token")
    suspend fun getCallToken(
        @Body request: CallTokenRequest
    ): Response<CallTokenResponse>

    @POST("api/invitations")
    suspend fun createInvitation(
        @Body request: CreateInvitationRequest
    ): Response<CreateInvitationResponse>

    @GET("api/invitations/{id}")
    suspend fun getInvitation(
        @Path("id") id: String,
        @Query("device_id") deviceId: String
    ): Response<InvitationDetailsResponse>

    @GET("api/invitations/pending/{recipientDeviceId}")
    suspend fun getPendingInvitation(
        @Path("recipientDeviceId") recipientDeviceId: String
    ): Response<Map<String, Any?>>

    @POST("api/invitations/{id}/answer")
    suspend fun answerInvitation(
        @Path("id") id: String,
        @Body request: AnswerInvitationRequest
    ): Response<AnswerInvitationResponse>

    @POST("api/invitations/{id}/dismiss")
    suspend fun dismissInvitation(
        @Path("id") id: String,
        @Body request: DismissInvitationRequest
    ): Response<DismissInvitationResponse>

    @POST("api/invitations/{id}/cancel")
    suspend fun cancelInvitation(
        @Path("id") id: String,
        @Body request: CancelInvitationRequest
    ): Response<CancelInvitationResponse>

    @POST("api/contacts/pair")
    suspend fun pairContact(
        @Body request: PairContactRequest
    ): Response<PairContactResponse>

    @POST("api/devices/fcm-token")
    suspend fun registerFcmToken(
        @Body request: RegisterFcmTokenRequest
    ): Response<Map<String, String>>

    @GET("api/contacts/{deviceId}/emergency")
    suspend fun getEmergencyContact(
        @Path("deviceId") deviceId: String
    ): Response<EmergencyContactResponse>

    @PUT("api/contacts/{deviceId}/emergency")
    suspend fun saveEmergencyContact(
        @Path("deviceId") deviceId: String,
        @Body request: SaveEmergencyContactRequest
    ): Response<EmergencyContactResponse>
}

object AegisApiClient {

    // Default laptop Wi-Fi IPv4 address.
    const val DEFAULT_BASE_URL = "http://192.168.1.4:8000/"

    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    @Volatile
    private var apiInstance: AegisApi? = null

    var baseUrl: String
        get() = currentBaseUrl
        set(value) {
            val normalized = if (value.endsWith("/")) value else "$value/"
            if (normalized != currentBaseUrl) {
                currentBaseUrl = normalized
                apiInstance = null
            }
        }

    val api: AegisApi
        get() {
            return apiInstance ?: synchronized(this) {
                apiInstance ?: buildRetrofit(currentBaseUrl).also { apiInstance = it }
            }
        }

    fun buildRetrofit(url: String): AegisApi {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AegisApi::class.java)
    }
}
