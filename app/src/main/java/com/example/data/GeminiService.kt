package com.example.data

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Gemini API Moshi Models ---

@JsonClass(generateAdapter = true)
data class GeminiPart(val text: String)

@JsonClass(generateAdapter = true)
data class GeminiContent(val role: String, val parts: List<GeminiPart>)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(val candidates: List<GeminiCandidate>?)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(val content: GeminiContent?)

// --- Retrofit Interface ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

// --- API Service Client ---

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

// --- Assistant Logic ---

object GeminiAssistant {

    // Local quick-answers library for zero-latency responses grounded in app facts
    fun findQuickAnswer(message: String): String? {
        val msg = message.trim().lowercase()
        return when {
            msg.contains("emergency") || msg.contains("108") || msg.contains("ambulance") -> {
                "🚨 IN A MEDICAL EMERGENCY, DIAL 108 IMMEDIATELY! This is the national ambulance service in India. While 'I See You' helps you secure an ICU bed in real-time, calling 108 is your fastest option for immediate on-site medical transit."
            }
            msg.contains("sole purpose") || msg.contains("purpose of this app") || msg.contains("what is this app") || msg.contains("about this app") || msg.contains("what is i see you") -> {
                "🏥 SOLE PURPOSE OF 'I SEE YOU':\n'I See You' is India's real-time ICU Bed Availability, Hospital Discovery, Emergency Routing, and Bed Reservation network. Its sole purpose is to eliminate delays during critical health emergencies by giving families, paramedics, and doctors transparent, live access to ICU bed inventory across all 28 states and union territories in India."
            }
            msg.contains("all features") || msg.contains("what can this app do") || msg.contains("features of this app") || msg.contains("list features") || msg.contains("app features") -> {
                "✨ KEY FEATURES OF 'I SEE YOU':\n" +
                "1. Real-time ICU Bed Inventory: Live counts for General ICU, CCU, NICU, PICU, Ventilator, and Trauma beds.\n" +
                "2. 10-Minute Instant Bed Hold: Reserve beds with patient details & get an instant confirmation pass.\n" +
                "3. Interactive GIS Map: Google Maps vector view, Metallic Satellite terrain, and Tactical Emergency Radar modes.\n" +
                "4. Live GPS & Auto-Centering: Google Play Services positioning auto-centers nearby hospitals instantly.\n" +
                "5. India-Wide Search & Radius Filters: Search by City, Pincode, or Radius (5 km - 200 km).\n" +
                "6. Government vs Private Cost Filters: Transparency on 100% Free Govt ICU beds vs Private daily rates.\n" +
                "7. Hospital Staff Dashboard: Allows hospital staff to update live bed counts and manage bookings.\n" +
                "8. Authenticated Verification Badges: Green badges for health-department certified hospitals.\n" +
                "9. Live GPS Driving Navigation: Driving distance, traffic ETAs, and direct Google Maps navigation launcher.\n" +
                "10. DPDP Act Privacy: Minimum data collection protecting patient data under Indian law."
            }
            msg.contains("how do i book") || msg.contains("how to book") || msg.contains("booking process") || msg.contains("how can i book") -> {
                "🏥 Booking an ICU Bed is simple:\n\n1. Select 'Book an ICU Bed' or tap any hospital on the map/list.\n2. View its real-time bed inventory across ICU categories.\n3. Supply patient details (Name, Age, Oxygen required, Contact number).\n4. Click 'Confirm Booking'. Your bed is held in HELD state with a 10-minute countdown while the hospital confirms allocation!"
            }
            msg.contains("hospital staff") || msg.contains("staff dashboard") || msg.contains("update bed") || msg.contains("manage bed") -> {
                "👨‍⚕️ HOSPITAL STAFF & ADMIN DASHBOARD:\nHospital administrators and nurses can:\n1. Log in via 'Staff Portal' on the main drawer or settings.\n2. Update live ICU bed counts (occupied vs available) in real time.\n3. Submit medical license credentials to trigger green Verification Badges.\n4. View incoming patient reservations, accept/decline holds, and track arrival status."
            }
            msg.contains("radar") || msg.contains("satellite") || msg.contains("map mode") || msg.contains("map view") -> {
                "🗺️ MAP VISUALIZATION MODES:\n'I See You' provides 3 map views:\n1. Google Maps: Clean vector view with live traffic overlays.\n2. Satellite: High-contrast metallic satellite terrain view.\n3. Radar: Tactical emergency sweep showing distance rings and bearing vectors relative to your coordinates."
            }
            msg.contains("is this app free") || msg.contains("how much does it cost") || msg.contains("payment") || msg.contains("charge") -> {
                "💳 Booking a bed through 'I See You' is 100% free! ICU beds in all government hospitals are provided 100% free of charge. Private hospital daily rates shown are indicative rates paid directly to the hospital upon admission."
            }
            msg.contains("is my data safe") || msg.contains("privacy") || msg.contains("dpdp") || msg.contains("security") -> {
                "🛡️ Your privacy is highly protected. Adhering to India's Digital Personal Data Protection (DPDP) Act, 'I See You' only collects minimum required data (patient name, age, phone) to hold the reservation. We never sell or share patient data."
            }
            msg.contains("verification") || msg.contains("verified badge") || msg.contains("verified") -> {
                "✅ Verified hospitals display a green verification badge. This indicates their medical license and bed counts have been authenticated by health administration. Hospital staff can submit their license on their Staff Dashboard to receive instant verification!"
            }
            else -> null
        }
    }

    // Server-side / Client LLM chat function grounded in live context
    suspend fun getChatResponse(
        message: String,
        history: List<GeminiContent>,
        locationName: String,
        visibleHospitals: List<HospitalWithDistance>,
        activeBookingText: String
    ): String {
        // 1. First, check quick answers to respond instantly if matched
        val quick = findQuickAnswer(message)
        if (quick != null) {
            return quick
        }

        // 2. Prepare context details for Grounding
        val hospitalContext = if (visibleHospitals.isEmpty()) {
            "No hospitals are currently loaded on screen."
        } else {
            visibleHospitals.take(5).joinToString("\n") { h ->
                "- ${h.hospital.name} in ${h.hospital.city} (${h.hospital.type}): ${h.totalAvailableBeds} available ICU beds. " +
                        "Distance: ${String.format("%.1f", h.distanceKm ?: 0.0)} km, ETA: ${h.etaMinutes ?: "N/A"} mins. " +
                        "Verified status: ${h.hospital.verificationStatus}. Phone: ${h.hospital.phone}"
            }
        }

        val groundingContext = """
            You are "I See You", a warm, empathetic, and knowledgeable AI Medical Emergency Assistant for India's real-time ICU Bed Availability & Hospital Network.

            APP SOLE PURPOSE:
            "I See You" exists to save lives during critical health emergencies across India by eliminating delays in finding available Intensive Care Unit (ICU) beds. The platform gives patients, families, and emergency responders real-time visibility into bed inventory, verified hospital credentials, transparent costs, turn-by-turn GPS routing, and instant 10-minute bed reservation holds across all 28 states and union territories.

            ALL APP FEATURES & CAPABILITIES YOU MUST KNOW:
            1. Real-time ICU Inventory: Live bed counts split by General ICU, Cardiac (CCU/ICCU), Neonatal (NICU), Pediatric (PICU), Ventilator/Oxygen, and Emergency Trauma beds.
            2. 10-Min Instant Bed Hold: Users can input patient details (Name, Age, Oxygen required, Contact) to hold an ICU bed for 10 minutes while traveling to the hospital.
            3. Interactive Map & GIS Visualizer: Switch between Google Vector Maps, Satellite Terrain, and Tactical Emergency Radar modes with live traffic overlays and auto-centering via Google Play Services GPS.
            4. India-Wide Search & Radius Filters: Autocomplete city search, 6-digit pincode lookup, and distance radius adjustment (5 km to 200 km).
            5. Government vs Private Transparency: Clear distinction between 100% Free Government/Public Hospital ICU beds and Private Hospital daily rates, with Ayushman Bharat (PM-JAY) insurance badges.
            6. Authenticated Verification Badges: Green verification shield indicating medical licenses authenticated by health administration.
            7. Hospital Staff & Admin Portal: Self-service staff dashboard for hospital nurses and admins to update live bed availability, manage incoming patient reservations, and submit licensing documents.
            8. Live GPS Navigation & Ambulance Route Tracking: Driving distance, traffic ETAs, simulated ambulance route tracking, and direct one-tap launcher to external Google Maps navigation.
            9. Patient Booking Receipts & History: Offline storage powered by Room database with instant printable/viewable booking reference passes (ICU-2026-XXXX).
            10. Emergency Hotlines: Instant dialing to 108 National Ambulance Service, 1091 Women Helpline, Blood Banks, and direct hospital emergency desks.
            11. DPDP Act Privacy: Minimum data collection protecting patient privacy under Indian law.

            CURRENT LIVE USER CONTEXT:
            - User's current resolved location: $locationName
            - Active booking status in this session: $activeBookingText
            - Top hospitals visible on user's screen right now:
            $hospitalContext

            CRITICAL RULES:
            1. ACT AS THE GUIDE FOR "I SEE YOU" AND ANSWER QUESTIONS ABOUT ITS SOLE PURPOSE, FEATURES, MAP, BOOKINGS, HOSPITALS, OR NAVIGATION.
            2. SAFETY BOUNDARY: NEVER GIVE MEDICAL DIAGNOSES, DIAGNOSTIC QUESTIONING, OR TREATMENT ADVICE. For severe medical symptoms, ALWAYS direct the user to consult a physician and call 108 immediately.
            3. TONE: Warm, reassuring, highly scannable, natural human tone. Keep answers under 3 short paragraphs or 4-5 bullet points.
            4. COMFORT: Reassure the user, as they may be under high stress during a medical emergency.
        """.trimIndent()

        // Combine history
        val fullContents = mutableListOf<GeminiContent>()
        // Add last 6 messages from history to keep context window compact & fast
        fullContents.addAll(history.takeLast(6))
        // Add current user message
        fullContents.add(GeminiContent(role = "user", parts = listOf(GeminiPart(message))))

        // Retrieve API key from BuildConfig safely
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return "Hello! I am 'I See You' assistant. I am currently running in offline demo mode. I can help answer common questions like 'How do I book a bed?', 'What is the sole purpose of this app?', 'What are all the features?', 'Are there verified hospitals near me?', 'What is the emergency number?' or guide you through the screens."
        }

        return try {
            val request = GeminiRequest(
                contents = fullContents,
                systemInstruction = GeminiContent(role = "system", parts = listOf(GeminiPart(groundingContext)))
            )
            val response = GeminiClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: "I apologize, I didn't receive a clear answer. Please try again or dial 108 if this is an immediate medical emergency."
        } catch (e: Exception) {
            Log.e("GeminiAssistant", "Error making Gemini API call", e)
            "My apologies. I had trouble connecting to my service. Please try again. If this is an urgent situation, call 108 or contact the hospital directly."
        }
    }
}
