package com.example.data

import android.content.Context
import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// --- MongoDB Atlas Data API Models ---

@JsonClass(generateAdapter = true)
data class MongoInsertOneRequest<T>(
    val dataSource: String,
    val database: String,
    val collection: String,
    val document: T
)

@JsonClass(generateAdapter = true)
data class MongoFindOneRequest(
    val dataSource: String,
    val database: String,
    val collection: String,
    val filter: Map<String, String>
)

@JsonClass(generateAdapter = true)
data class MongoFindRequest(
    val dataSource: String,
    val database: String,
    val collection: String,
    val filter: Map<String, String>? = null,
    val limit: Int? = null
)

@JsonClass(generateAdapter = true)
data class MongoUpdateOneRequest<T>(
    val dataSource: String,
    val database: String,
    val collection: String,
    val filter: Map<String, String>,
    val update: Map<String, T>,
    val upsert: Boolean = true
)

@JsonClass(generateAdapter = true)
data class MongoDeleteOneRequest(
    val dataSource: String,
    val database: String,
    val collection: String,
    val filter: Map<String, String>
)

// Response wrappers
@JsonClass(generateAdapter = true)
data class MongoInsertOneResponse(
    val insertedId: String?
)

@JsonClass(generateAdapter = true)
data class MongoFindOneResponse<T>(
    val document: T?
)

@JsonClass(generateAdapter = true)
data class MongoFindResponse<T>(
    val documents: List<T> = emptyList()
)

@JsonClass(generateAdapter = true)
data class MongoUpdateOneResponse(
    val matchedCount: Int,
    val modifiedCount: Int,
    val upsertedId: String?
)

@JsonClass(generateAdapter = true)
data class MongoDeleteOneResponse(
    val deletedCount: Int
)

// --- Retrofit API Service ---

interface MongoDbApiService {
    @POST("action/insertOne")
    suspend fun insertOneUser(
        @Header("api-key") apiKey: String,
        @Body request: MongoInsertOneRequest<UserAccount>
    ): MongoInsertOneResponse

    @POST("action/insertOne")
    suspend fun insertOneStaff(
        @Header("api-key") apiKey: String,
        @Body request: MongoInsertOneRequest<HospitalStaffAccount>
    ): MongoInsertOneResponse

    @POST("action/insertOne")
    suspend fun insertOneBooking(
        @Header("api-key") apiKey: String,
        @Body request: MongoInsertOneRequest<Booking>
    ): MongoInsertOneResponse

    @POST("action/insertOne")
    suspend fun insertOneHospital(
        @Header("api-key") apiKey: String,
        @Body request: MongoInsertOneRequest<Hospital>
    ): MongoInsertOneResponse

    @POST("action/findOne")
    suspend fun findOneUser(
        @Header("api-key") apiKey: String,
        @Body request: MongoFindOneRequest
    ): MongoFindOneResponse<UserAccount>

    @POST("action/findOne")
    suspend fun findOneStaff(
        @Header("api-key") apiKey: String,
        @Body request: MongoFindOneRequest
    ): MongoFindOneResponse<HospitalStaffAccount>

    @POST("action/findOne")
    suspend fun findOneHospital(
        @Header("api-key") apiKey: String,
        @Body request: MongoFindOneRequest
    ): MongoFindOneResponse<Hospital>

    @POST("action/find")
    suspend fun findBookings(
        @Header("api-key") apiKey: String,
        @Body request: MongoFindRequest
    ): MongoFindResponse<Booking>

    @POST("action/find")
    suspend fun findHospitals(
        @Header("api-key") apiKey: String,
        @Body request: MongoFindRequest
    ): MongoFindResponse<Hospital>

    @POST("action/updateOne")
    suspend fun updateOneIcuInventory(
        @Header("api-key") apiKey: String,
        @Body request: MongoUpdateOneRequest<IcuInventory>
    ): MongoUpdateOneResponse

    @POST("action/updateOne")
    suspend fun updateOneHospital(
        @Header("api-key") apiKey: String,
        @Body request: MongoUpdateOneRequest<Hospital>
    ): MongoUpdateOneResponse

    @POST("action/updateOne")
    suspend fun updateOneBooking(
        @Header("api-key") apiKey: String,
        @Body request: MongoUpdateOneRequest<Booking>
    ): MongoUpdateOneResponse
}

// --- Configuration Storage Helper ---

data class MongoDbConfig(
    val enabled: Boolean = false,
    val appId: String = "",
    val apiKey: String = "",
    val databaseName: String = "ICUBedFinder",
    val dataSource: String = "Cluster0"
) {
    fun isValid(): Boolean {
        return appId.isNotBlank() && apiKey.isNotBlank()
    }
}

object MongoDbManager {
    private const val PREFS_NAME = "mongodb_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_APP_ID = "app_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_DB_NAME = "database_name"
    private const val KEY_DATA_SOURCE = "data_source"

    private var cachedConfig: MongoDbConfig? = null
    private var cachedService: MongoDbApiService? = null
    private var cachedBaseUrl: String? = null

    fun loadConfig(context: Context): MongoDbConfig {
        if (cachedConfig != null) return cachedConfig!!
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val config = MongoDbConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            appId = prefs.getString(KEY_APP_ID, "") ?: "",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            databaseName = prefs.getString(KEY_DB_NAME, "ICUBedFinder") ?: "ICUBedFinder",
            dataSource = prefs.getString(KEY_DATA_SOURCE, "Cluster0") ?: "Cluster0"
        )
        cachedConfig = config
        return config
    }

    fun saveConfig(context: Context, config: MongoDbConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_APP_ID, config.appId.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_DB_NAME, config.databaseName.trim())
            .putString(KEY_DATA_SOURCE, config.dataSource.trim())
            .apply()
        
        cachedConfig = config
        // Invalidate Retrofit cache if credentials changed
        cachedService = null
        cachedBaseUrl = null
        Log.d("MongoDbManager", "MongoDB Configuration Saved: Enabled=${config.enabled}, Database=${config.databaseName}")
    }

    fun getService(context: Context): MongoDbApiService? {
        val config = loadConfig(context)
        if (!config.enabled || !config.isValid()) {
            return null
        }

        val url = "https://data.mongodb-api.com/app/${config.appId}/endpoint/data/v1/"
        if (cachedService != null && cachedBaseUrl == url) {
            return cachedService
        }

        try {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val service = Retrofit.Builder()
                .baseUrl(url)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(MongoDbApiService::class.java)

            cachedService = service
            cachedBaseUrl = url
            return service
        } catch (e: Exception) {
            Log.e("MongoDbManager", "Failed to construct MongoDb API client", e)
            return null
        }
    }
}
