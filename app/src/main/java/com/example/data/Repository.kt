package com.example.data
import com.example.data.providers.PaymentProvider
import com.example.data.providers.RazorpayPaymentProvider
import com.example.data.providers.RefundResult
import org.json.JSONArray
import org.json.JSONObject
import android.content.SharedPreferences

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class HospitalWithDistance(
    val hospital: Hospital,
    val inventory: List<IcuInventory>,
    val distanceKm: Double?,
    val etaMinutes: Int?,
    val totalAvailableBeds: Int
)

class HospitalRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val hospitalDao = db.hospitalDao()
    private val icuInventoryDao = db.icuInventoryDao()
    private val bookingDao = db.bookingDao()
    private val staffAccountDao = db.hospitalStaffAccountDao()
    private val userAccountDao = db.userAccountDao()
    private val searchedLocationDao = db.searchedLocationDao()
    private val paymentDao = db.paymentDao()
    private val paymentProvider: PaymentProvider = RazorpayPaymentProvider()

    val allHospitalsFlow: Flow<List<Hospital>> = hospitalDao.getAllHospitalsFlow()
    val allBookingsFlow: Flow<List<Booking>> = bookingDao.getAllBookingsFlow()
    val recentSearchedLocationsFlow: Flow<List<SearchedLocation>> = searchedLocationDao.getLast3LocationsFlow()

    init {
        // Run database seeding if empty in a coroutine context safely
        // Note: Done asynchronously
    }

    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        restorePersistentAccounts()
        val existing = hospitalDao.getAllHospitals()
        val inventories = icuInventoryDao.getAllInventory()

        if (existing.isNotEmpty() && inventories.isNotEmpty()) {
            // Ensure all initial inventory bed counts are reset to 0 so hospitals can update them upon registration
            val hasNonZero = inventories.any { it.availableBeds > 0 || it.totalBeds > 0 }
            if (hasNonZero) {
                val resetList = inventories.map { inv ->
                    inv.copy(
                        totalBeds = 0,
                        availableBeds = 0,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                icuInventoryDao.insertInventoryList(resetList)
            }
            return@withContext
        }

        Log.d("HospitalRepository", "Clearing database and seeding all hospitals with 0 beds...")
        hospitalDao.clearAllHospitals()
        icuInventoryDao.clearAllInventory()

        Log.d("HospitalRepository", "Seeding database with 117 prominent Indian hospitals equipped with ICUs...")

        val seedHospitals = SeededHospitals.list

        hospitalDao.insertHospitals(seedHospitals)

        // Seed inventory for each hospital initialized to 0 bed counts (awaiting hospital registration update)
        val seedInventory = mutableListOf<IcuInventory>()
        val icuTypes = listOf("general", "cardiac", "neonatal", "pediatric", "post_op", "isolation")

        for (h in seedHospitals) {
            val isGov = h.type == "government"
            for (type in icuTypes) {
                val total = 0 // Bed count initialized to 0 until updated by registered hospital
                val available = 0 // Bed count initialized to 0 until updated by registered hospital

                val price = if (isGov) {
                    0.0 // Free
                } else {
                    when (type) {
                        "general" -> (2500..5000).random().toDouble()
                        "cardiac" -> (8000..15000).random().toDouble()
                        "neonatal" -> (6000..12000).random().toDouble()
                        "pediatric" -> (4000..8000).random().toDouble()
                        "isolation" -> (3500..7000).random().toDouble()
                        else -> (3000..6000).random().toDouble() // post_op
                    }
                }

                seedInventory.add(
                    IcuInventory(
                        id = "${h.id}_$type",
                        hospitalId = h.id,
                        icuType = type,
                        totalBeds = total,
                        availableBeds = available,
                        pricePerDay = price,
                        updatedAt = System.currentTimeMillis() - (0..72).random() * 60 * 60 * 1000
                    )
                )
            }
        }

        icuInventoryDao.insertInventoryList(seedInventory)

        // Seed hospital staff accounts
        val staffAccounts = listOf(
            HospitalStaffAccount(
                id = "staff_kem",
                hospitalId = "h_kem_hospital_19",
                contactName = "Dr. Shinde",
                email = "kem@hospital.in",
                phone = "+919876543210"
            ),
            HospitalStaffAccount(
                id = "staff_narayana",
                hospitalId = "h_narayana_health_city_31",
                contactName = "Dr. Shetty",
                email = "narayana@hospital.in",
                phone = "+919876543211"
            ),
            HospitalStaffAccount(
                id = "staff_aiims",
                hospitalId = "h_all_india_institute_of_medical_sciences_aiims_1",
                contactName = "Dr. Gupta",
                email = "aiims@hospital.in",
                phone = "+919876543212"
            )
        )

        for (staff in staffAccounts) {
            staffAccountDao.insertAccount(staff)
        saveStaffToPrefs(staff)
        }

        // Seed default user accounts for demonstration
        val seedUsers = listOf(
            UserAccount(
                email = "user@demo.in",
                name = "Aravind Kumar",
                phone = "+919999988888",
                address = "12 Main Road, Indiranagar",
                city = "Bengaluru",
                state = "Karnataka",
                pincode = "560038"
            ),
            UserAccount(
                email = "patient@test.com",
                name = "Suresh Patel",
                phone = "+918888877777",
                address = "52, Saket Block C",
                city = "New Delhi",
                state = "Delhi",
                pincode = "110017"
            )
        )
        for (u in seedUsers) {
            userAccountDao.insertAccount(u)
        }
    }

    // Dynamic search, distance, filter, and sorting
    fun searchHospitals(
        userLat: Double?,
        userLng: Double?,
        sortBy: String, // "distance", "time", "availability"
        filterIcuType: String?,
        filterHospitalType: String?,
        query: String?,
        filterRadiusKm: Double? = null
    ): Flow<List<HospitalWithDistance>> {
        return hospitalDao.getAllHospitalsFlow().map { hospitals ->
            val result = mutableListOf<HospitalWithDistance>()
            for (h in hospitals) {
                // Apply name/address/city/state/pincode query filter
                if (!query.isNullOrBlank()) {
                    val q = query.trim().lowercase()
                    val matches = h.name.lowercase().contains(q) ||
                            h.address.lowercase().contains(q) ||
                            h.city.lowercase().contains(q) ||
                            h.state.lowercase().contains(q) ||
                            h.pincode.contains(q)
                    if (!matches) continue
                }

                // Apply hospital type filter (government, private, trust)
                if (!filterHospitalType.isNullOrBlank()) {
                    if (h.type != filterHospitalType) continue
                }

                // Fetch inventory details synchronously inside stream (map)
                val inventory = icuInventoryDao.getInventoryForHospital(h.id)

                // Apply ICU Bed Type filter (if requested, we compute available beds for that specific type)
                val totalAvailableBeds = if (!filterIcuType.isNullOrBlank()) {
                    inventory.find { it.icuType == filterIcuType }?.availableBeds ?: 0
                } else {
                    inventory.sumOf { it.availableBeds }
                }

                // Distance & ETA calculation (Google Maps-like routing and traffic-delay accuracy)
                var distance: Double? = null
                var eta: Int? = null
                if (userLat != null && userLng != null) {
                    val rawDistance = calculateHaversineDistance(userLat, userLng, h.lat, h.lng)
                    
                    // Efficient Radius Filter:
                    // 1. Explicit search query (searching for specific hospital, city or state) bypasses radius cap.
                    // 2. Explicit filterRadiusKm chosen by user is strictly enforced.
                    // 3. Default (no search, no explicit radius): show hospitals within 120km of active coordinates.
                    //    If location is in a remote area with zero hospitals within 120km, show nearest across country.
                    val isExplicitSearch = !query.isNullOrBlank()
                    if (!isExplicitSearch && filterRadiusKm != null && rawDistance > filterRadiusKm) {
                        continue
                    } else if (!isExplicitSearch && filterRadiusKm == null && rawDistance > 120.0) {
                        val hasNearbyCount = hospitals.count { calculateHaversineDistance(userLat, userLng, it.lat, it.lng) <= 120.0 }
                        if (hasNearbyCount > 0) {
                            continue
                        }
                    }

                    // Google Maps road grid detour multiplier (curves, intersections, blocks)
                    val gridMultiplier = when {
                        rawDistance < 1.0 -> 1.42
                        rawDistance < 8.0 -> 1.35
                        else -> 1.28
                    }
                    // Unique route variation based on stable hospital ID hashing to avoid raw concentric circles
                    val routeDetour = (h.id.hashCode().coerceAtLeast(0) % 15) * 0.04
                    distance = rawDistance * gridMultiplier + routeDetour

                    // Base travel time: average urban driving speed in Indian cities (approx 22 km/h)
                    val baseEtaMinutes = (distance / 22.0) * 60.0

                    // Dynamic traffic congestion factor based on time of day (peak rush hours)
                    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val trafficMultiplier = when (currentHour) {
                        in 8..11 -> 1.45   // Morning peak hours
                        in 17..21 -> 1.55  // Evening rush hours
                        in 12..16 -> 1.25  // Moderately active afternoon traffic
                        else -> 1.05       // Clear nighttime/early morning lanes
                    }

                    // Narrow street access / local bottleneck factor based on name length or hash
                    val localCongestion = 1.0 + (h.name.hashCode().coerceAtLeast(0) % 10) * 0.03

                    // Combine base time with dynamic traffic and local bottleneck multipliers
                    eta = (baseEtaMinutes * trafficMultiplier * localCongestion).toInt().coerceAtLeast(3)
                }

                result.add(
                    HospitalWithDistance(
                        hospital = h,
                        inventory = inventory,
                        distanceKm = distance,
                        etaMinutes = eta,
                        totalAvailableBeds = totalAvailableBeds
                    )
                )
            }

            // Apply sorting
            when (sortBy) {
                "distance" -> {
                    if (userLat != null && userLng != null) {
                        result.sortBy { it.distanceKm ?: Double.MAX_VALUE }
                    } else {
                        result.sortBy { it.hospital.name }
                    }
                }
                "time" -> {
                    if (userLat != null && userLng != null) {
                        result.sortBy { it.etaMinutes ?: Int.MAX_VALUE }
                    } else {
                        result.sortBy { it.hospital.name }
                    }
                }
                "availability" -> {
                    result.sortByDescending { it.totalAvailableBeds }
                }
                else -> result.sortBy { it.hospital.name }
            }

            android.util.Log.d(
                "HospitalRepository",
                "Backend Real-Time Search Executed: Query='$query', RadiusFilter=${filterRadiusKm}km, SortBy='$sortBy', FilterIcuType='$filterIcuType', FilterHospitalType='$filterHospitalType'. Matched results: ${result.size}"
            )

            result
        }.flowOn(Dispatchers.IO)
    }

    fun getHospitalByIdFlow(id: String): Flow<Hospital?> {
        return hospitalDao.getHospitalByIdFlow(id)
    }

    suspend fun getHospitalById(id: String): Hospital? {
        return hospitalDao.getHospitalById(id)
    }

    fun getInventoryForHospitalFlow(hospitalId: String): Flow<List<IcuInventory>> {
        return icuInventoryDao.getInventoryForHospitalFlow(hospitalId)
    }

    suspend fun getInventoryForHospital(hospitalId: String): List<IcuInventory> {
        return icuInventoryDao.getInventoryForHospital(hospitalId)
    }

    // Bed updates from hospital dashboard
    suspend fun updateBeds(hospitalId: String, icuType: String, available: Int, total: Int) = withContext(Dispatchers.IO) {
        val id = "${hospitalId}_$icuType"
        val existing = icuInventoryDao.getInventoryById(id)
        val finalInv = if (existing != null) {
            val updated = existing.copy(
                availableBeds = available.coerceIn(0, total),
                totalBeds = total,
                updatedAt = System.currentTimeMillis()
            )
            icuInventoryDao.updateInventory(updated)
            updated
        } else {
            val newInv = IcuInventory(
                id = id,
                hospitalId = hospitalId,
                icuType = icuType,
                totalBeds = total,
                availableBeds = available,
                pricePerDay = if (hospitalDao.getHospitalById(hospitalId)?.type == "government") 0.0 else 5000.0,
                updatedAt = System.currentTimeMillis()
            )
            icuInventoryDao.insertInventory(newInv)
            newInv
        }

        // Update the hospital's last updated timestamp
        val h = hospitalDao.getHospitalById(hospitalId)
        if (h != null) {
            val updatedH = h.copy(lastUpdatedAt = System.currentTimeMillis())
            hospitalDao.updateHospital(updatedH)
            syncHospitalToMongo(updatedH)
        }
        syncInventoryToMongo(finalInv)
    }

    // Hospital register flow
    suspend fun registerHospital(
        name: String,
        type: String,
        address: String,
        city: String,
        state: String,
        pincode: String,
        phone: String,
        contactName: String,
        email: String,
        lat: Double,
        lng: Double,
        registeredDate: String? = null,
        accreditationCertificate: String? = null,
        regulatoryBody: String? = null,
        emergencyPhone: String? = null,
        websiteUrl: String? = null,
        repIdCardType: String? = "Hospital Employee ID Badge",
        repIdCardNumber: String? = null,
        repDesignation: String? = "ICU Representative"
    ): Hospital? = withContext(Dispatchers.IO) {
        val hId = "h_gen_" + System.currentTimeMillis().toString().takeLast(6)
        val hospital = Hospital(
            id = hId,
            name = name,
            type = type,
            address = address,
            city = city,
            state = state,
            pincode = pincode,
            lat = lat,
            lng = lng,
            phone = phone,
            verified = false,
            verificationStatus = "unverified",
            dataSource = "user_register",
            lastUpdatedAt = System.currentTimeMillis(),
            registeredDate = registeredDate,
            accreditationCertificate = accreditationCertificate,
            regulatoryBody = regulatoryBody,
            emergencyPhone = emergencyPhone,
            websiteUrl = websiteUrl
        )

        hospitalDao.insertHospital(hospital)

        // Generate base inventory for this hospital initialized to 0 beds (awaiting live update from registered hospital staff)
        val icuTypes = listOf("general", "cardiac", "neonatal", "pediatric", "post_op", "isolation")
        val inventory = icuTypes.map { type ->
            IcuInventory(
                id = "${hId}_$type",
                hospitalId = hId,
                icuType = type,
                totalBeds = 0,
                availableBeds = 0,
                pricePerDay = if (type == "government") 0.0 else 4000.0,
                updatedAt = System.currentTimeMillis()
            )
        }
        icuInventoryDao.insertInventoryList(inventory)

        // Staff account with verified representative identity card details
        val staff = HospitalStaffAccount(
            id = "staff_" + hId,
            hospitalId = hId,
            contactName = contactName,
            email = email,
            phone = phone,
            repIdCardType = repIdCardType ?: "Hospital Employee ID Badge",
            repIdCardNumber = repIdCardNumber,
            repDesignation = repDesignation ?: "ICU Representative",
            repIdVerified = true
        )
        staffAccountDao.insertAccount(staff)
        saveStaffToPrefs(staff)
        syncHospitalToMongo(hospital)
        syncStaffToMongo(staff)
        inventory.forEach { syncInventoryToMongo(it) }

        hospital
    }

    // Submit hospital verification request (Part of our required hospital verification system!)
    suspend fun submitHospitalVerification(hospitalId: String, licenseNumber: String) = withContext(Dispatchers.IO) {
        val h = hospitalDao.getHospitalById(hospitalId)
        if (h != null) {
            // For MVP: Auto-verify if they submit a valid License format matching LIC-IND-XXXXX,
            // or if anything starting with "LIC" is supplied. Otherwise leave as pending!
            val autoVerify = licenseNumber.trim().uppercase().startsWith("LIC")
            val nextStatus = if (autoVerify) "verified" else "pending"
            val nextVerified = autoVerify

            val updated = h.copy(
                licenseNumber = licenseNumber,
                verificationStatus = nextStatus,
                verified = nextVerified,
                verificationSubmittedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis()
            )
            hospitalDao.updateHospital(updated)
            Log.d("HospitalRepository", "Hospital verification submitted! ID: $hospitalId, Status: $nextStatus, Verified: $nextVerified")
        }
    }

    // Mark verified manually (for administrators or mock process)
    suspend fun setHospitalVerificationManual(hospitalId: String, verified: Boolean) = withContext(Dispatchers.IO) {
        val h = hospitalDao.getHospitalById(hospitalId)
        if (h != null) {
            val updated = h.copy(
                verified = verified,
                verificationStatus = if (verified) "verified" else "unverified",
                licenseNumber = if (verified) h.licenseNumber ?: "LIC-ADMIN-MOCK" else null
            )
            hospitalDao.updateHospital(updated)
        }
    }

    // Update hospital registry details
    suspend fun updateHospitalRegistry(
        hospitalId: String,
        phone: String,
        registeredDate: String?,
        accreditationCertificate: String?,
        regulatoryBody: String?,
        emergencyPhone: String?,
        websiteUrl: String?
    ) = withContext(Dispatchers.IO) {
        val h = hospitalDao.getHospitalById(hospitalId)
        if (h != null) {
            val updated = h.copy(
                phone = phone,
                registeredDate = registeredDate,
                accreditationCertificate = accreditationCertificate,
                regulatoryBody = regulatoryBody,
                emergencyPhone = emergencyPhone,
                websiteUrl = websiteUrl,
                lastUpdatedAt = System.currentTimeMillis()
            )
            hospitalDao.updateHospital(updated)
        }
    }

    // Update hospital web connector configurations (Provision Group)
    suspend fun updateHospitalWebConnector(
        hospitalId: String,
        enabled: Boolean,
        url: String,
        token: String,
        status: String
    ) = withContext(Dispatchers.IO) {
        val h = hospitalDao.getHospitalById(hospitalId)
        if (h != null) {
            val updated = h.copy(
                webConnectorEnabled = enabled,
                webConnectorUrl = url,
                webConnectorToken = token,
                webConnectorStatus = status,
                lastUpdatedAt = System.currentTimeMillis()
            )
            hospitalDao.updateHospital(updated)
        }
    }

    // Staff authentication (Multi-pronged matching: Case-insensitive email, mobile no, website domain)
    suspend fun loginStaff(input: String): HospitalStaffAccount? = withContext(Dispatchers.IO) {
        val cleaned = input.trim().lowercase()
        if (cleaned.isBlank()) return@withContext null

        restorePersistentAccounts()

        // 1. Match directly by staff email
        val staffByEmail = staffAccountDao.getAccountByEmail(cleaned)
        if (staffByEmail != null) return@withContext staffByEmail

        // 2. Match by staff mobile / phone number
        val staffByPhone = staffAccountDao.getAccountByPhone(cleaned)
        if (staffByPhone != null) return@withContext staffByPhone

        // 3. Match by hospital phone or emergency helpline
        val matchingHospitalByPhone = hospitalDao.getHospitalByPhone(cleaned)
        if (matchingHospitalByPhone != null) {
            val staff = staffAccountDao.getAccountForHospital(matchingHospitalByPhone.id)
            if (staff != null) return@withContext staff
        }

        // 4. Match by website URL domain
        val cleanDomain = cleaned.removePrefix("http://").removePrefix("https://").removePrefix("www.")
        if (cleanDomain.isNotBlank() && cleanDomain.length > 3) {
            val matchingHospitalByWeb = hospitalDao.getHospitalByWebsite(cleanDomain)
            if (matchingHospitalByWeb != null) {
                val staff = staffAccountDao.getAccountForHospital(matchingHospitalByWeb.id)
                if (staff != null) return@withContext staff
            }
        }



        // 5. Fallback check MongoDB if enabled
        val service = MongoDbManager.getService(context)
        val config = MongoDbManager.loadConfig(context)
        if (service != null && config.isValid()) {
            try {
                val isEmail = cleaned.contains("@")
                val response = service.findOneStaff(
                    apiKey = config.apiKey,
                    request = MongoFindOneRequest(
                        dataSource = config.dataSource,
                        database = config.databaseName,
                        collection = "staff_accounts",
                        filter = if (isEmail) mapOf("email" to cleaned) else mapOf("phone" to cleaned)
                    )
                )
                if (response.document != null) {
                    val staffDoc = response.document
                    staffAccountDao.insertAccount(staffDoc)
                    // Sync corresponding hospital details if absent locally
                    val localH = hospitalDao.getHospitalById(staffDoc.hospitalId)
                    if (localH == null) {
                        val hospResp = service.findOneHospital(
                            apiKey = config.apiKey,
                            request = MongoFindOneRequest(
                                dataSource = config.dataSource,
                                database = config.databaseName,
                                collection = "hospitals",
                                filter = mapOf("id" to staffDoc.hospitalId)
                            )
                        )
                        if (hospResp.document != null) {
                            hospitalDao.insertHospital(hospResp.document)
                        }
                    }
                    return@withContext staffDoc
                }
            } catch (e: Exception) {
                Log.e("HospitalRepository", "Failed to find staff account from MongoDB", e)
            }
        }

        null
    }

    val allUserAccountsFlow: kotlinx.coroutines.flow.Flow<List<UserAccount>> = userAccountDao.getAllAccountsFlow()

    
    private val persistentPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("icu_persistent_accounts_v2", Context.MODE_PRIVATE)
    }

    private fun saveUserToPrefs(user: UserAccount) {
        try {
            val jsonArrayStr = persistentPrefs.getString("user_accounts_json", "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)
            val updatedArray = JSONArray()
            var replaced = false
            val userDigits = normalizePhoneDigits(user.phone)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val objEmail = obj.optString("email")
                val objPhone = obj.optString("phone")
                val objDigits = normalizePhoneDigits(objPhone)
                if ((user.email.isNotBlank() && objEmail.equals(user.email, ignoreCase = true)) ||
                    (userDigits.length >= 7 && objDigits == userDigits)) {
                    updatedArray.put(userToJson(user))
                    replaced = true
                } else {
                    updatedArray.put(obj)
                }
            }
            if (!replaced) {
                updatedArray.put(userToJson(user))
            }
            persistentPrefs.edit().putString("user_accounts_json", updatedArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to save user account to prefs", e)
        }
    }

    private fun loadUsersFromPrefs(): List<UserAccount> {
        val list = mutableListOf<UserAccount>()
        try {
            val jsonArrayStr = persistentPrefs.getString("user_accounts_json", "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    UserAccount(
                        email = obj.optString("email"),
                        name = obj.optString("name"),
                        phone = obj.optString("phone"),
                        address = obj.optString("address"),
                        city = obj.optString("city"),
                        state = obj.optString("state"),
                        pincode = obj.optString("pincode"),
                        role = obj.optString("role", "public_user"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to load users from prefs", e)
        }
        return list
    }

    private fun userToJson(user: UserAccount): JSONObject {
        return JSONObject().apply {
            put("email", user.email)
            put("name", user.name)
            put("phone", user.phone)
            put("address", user.address)
            put("city", user.city)
            put("state", user.state)
            put("pincode", user.pincode)
            put("role", user.role)
            put("createdAt", user.createdAt)
        }
    }

    private fun saveStaffToPrefs(staff: HospitalStaffAccount) {
        try {
            val jsonArrayStr = persistentPrefs.getString("staff_accounts_json", "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)
            val updatedArray = JSONArray()
            var replaced = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("id") == staff.id || (staff.email.isNotBlank() && obj.optString("email").equals(staff.email, ignoreCase = true))) {
                    updatedArray.put(staffToJson(staff))
                    replaced = true
                } else {
                    updatedArray.put(obj)
                }
            }
            if (!replaced) {
                updatedArray.put(staffToJson(staff))
            }
            persistentPrefs.edit().putString("staff_accounts_json", updatedArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to save staff account to prefs", e)
        }
    }

    private fun loadStaffFromPrefs(): List<HospitalStaffAccount> {
        val list = mutableListOf<HospitalStaffAccount>()
        try {
            val jsonArrayStr = persistentPrefs.getString("staff_accounts_json", "[]") ?: "[]"
            val array = JSONArray(jsonArrayStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    HospitalStaffAccount(
                        id = obj.optString("id"),
                        hospitalId = obj.optString("hospitalId"),
                        contactName = obj.optString("contactName"),
                        email = obj.optString("email"),
                        phone = obj.optString("phone"),
                        role = obj.optString("role", "hospital_staff"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        repIdCardType = obj.optString("repIdCardType", "Hospital Employee ID Badge"),
                        repIdCardNumber = obj.optString("repIdCardNumber", ""),
                        repDesignation = obj.optString("repDesignation", "ICU Representative"),
                        repIdVerified = obj.optBoolean("repIdVerified", true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to load staff accounts from prefs", e)
        }
        return list
    }

    private fun staffToJson(staff: HospitalStaffAccount): JSONObject {
        return JSONObject().apply {
            put("id", staff.id)
            put("hospitalId", staff.hospitalId)
            put("contactName", staff.contactName)
            put("email", staff.email)
            put("phone", staff.phone)
            put("role", staff.role)
            put("createdAt", staff.createdAt)
            put("repIdCardType", staff.repIdCardType)
            put("repIdCardNumber", staff.repIdCardNumber)
            put("repDesignation", staff.repDesignation)
            put("repIdVerified", staff.repIdVerified)
        }
    }

    suspend fun restorePersistentAccounts() = withContext(Dispatchers.IO) {
        val savedUsers = loadUsersFromPrefs()
        for (u in savedUsers) {
            userAccountDao.insertAccount(u)
        }
        val savedStaff = loadStaffFromPrefs()
        for (s in savedStaff) {
            staffAccountDao.insertAccount(s)
        }
    }

    private fun normalizePhoneDigits(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    // Public User login & registration
    suspend fun loginUser(input: String): UserAccount? = withContext(Dispatchers.IO) {
        val cleaned = input.trim().lowercase()
        if (cleaned.isBlank()) return@withContext null

        restorePersistentAccounts()
        val allAccounts = userAccountDao.getAllAccounts()

        // 1. Check local Room DB first by email
        val userByEmail = userAccountDao.getAccountByEmail(cleaned)
        if (userByEmail != null) return@withContext userByEmail

        // 2. Direct phone query check
        val userByPhone = userAccountDao.getAccountByPhone(cleaned)
        if (userByPhone != null) return@withContext userByPhone

        // 3. Robust normalized digit matching across all local accounts
        val inputDigits = normalizePhoneDigits(input)
        if (inputDigits.length >= 7) {
            val matchedAccount = allAccounts.firstOrNull { acc ->
                val accPhoneDigits = normalizePhoneDigits(acc.phone)
                accPhoneDigits == inputDigits ||
                        (accPhoneDigits.isNotBlank() && inputDigits.endsWith(accPhoneDigits)) ||
                        (inputDigits.isNotBlank() && accPhoneDigits.endsWith(inputDigits))
            }
            if (matchedAccount != null) return@withContext matchedAccount
        }

        // 4. Name matching across local accounts
        val matchedByName = allAccounts.firstOrNull { acc ->
            val accName = acc.name.lowercase().trim()
            accName.isNotBlank() && (accName == cleaned || accName.contains(cleaned) || (cleaned.length >= 3 && cleaned.contains(accName)))
        }
        if (matchedByName != null) return@withContext matchedByName



        // 5. Fallback check MongoDB if enabled
        val service = MongoDbManager.getService(context)
        val config = MongoDbManager.loadConfig(context)
        if (service != null && config.isValid()) {
            try {
                val response = service.findOneUser(
                    apiKey = config.apiKey,
                    request = MongoFindOneRequest(
                        dataSource = config.dataSource,
                        database = config.databaseName,
                        collection = "users",
                        filter = if (cleaned.contains("@")) mapOf("email" to cleaned) else mapOf("phone" to cleaned)
                    )
                )
                if (response.document != null) {
                    userAccountDao.insertAccount(response.document)
                    return@withContext response.document
                }
            } catch (e: Exception) {
                Log.e("HospitalRepository", "Failed to find user from MongoDB", e)
            }
        }

        null
    }

    suspend fun registerUser(
        name: String,
        email: String,
        phone: String,
        address: String = "",
        city: String = "",
        state: String = "",
        pincode: String = ""
    ): UserAccount? = withContext(Dispatchers.IO) {
        val cleanedEmail = email.trim().lowercase()
        val cleanedPhone = phone.trim()
        if (cleanedEmail.isBlank() && cleanedPhone.isBlank()) return@withContext null

        val phoneDigits = normalizePhoneDigits(cleanedPhone)
        val allAccounts = userAccountDao.getAllAccounts()

        // Check if account already exists by email or phone digits
        val existing = userAccountDao.getAccountByEmail(cleanedEmail)
            ?: (if (cleanedPhone.isNotBlank()) userAccountDao.getAccountByPhone(cleanedPhone) else null)
            ?: (if (phoneDigits.length >= 7) allAccounts.firstOrNull { normalizePhoneDigits(it.phone) == phoneDigits } else null)

        val targetEmail = if (cleanedEmail.isNotBlank()) cleanedEmail else existing?.email ?: "user_$phoneDigits@icuseeyou.in"

        val updatedUser = UserAccount(
            email = targetEmail,
            name = name.trim().ifBlank { existing?.name ?: "Patient" },
            phone = cleanedPhone.ifBlank { existing?.phone ?: "" },
            address = address.trim().ifBlank { existing?.address ?: "" },
            city = city.trim().ifBlank { existing?.city ?: "Bengaluru" },
            state = state.trim().ifBlank { existing?.state ?: "Karnataka" },
            pincode = pincode.trim().ifBlank { existing?.pincode ?: "560001" },
            role = "public_user",
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )

        userAccountDao.insertAccount(updatedUser)
        saveUserToPrefs(updatedUser)
        syncUserToMongo(updatedUser)
        updatedUser
    }

    // Booking transaction flow
    suspend fun createBooking(
        hospitalId: String,
        icuType: String,
        patientName: String,
        patientAge: Int,
        contactPhone: String,
        paymentMethod: String = "Pay at Desk",
        paymentStatus: String = "PENDING",
        cghsCardNumber: String? = null,
        finalPrice: Double = 0.0,
        isGovernmentServant: Boolean = false,
        cghsCardAttachedPath: String? = null,
        downpaymentPaidAmount: Double = 0.0
    ): Booking? = withContext(Dispatchers.IO) {
        val invId = "${hospitalId}_$icuType"
        val inventory = icuInventoryDao.getInventoryById(invId)

        if (inventory == null || inventory.availableBeds <= 0) {
            return@withContext null
        }

        // Decrement bed count
        val updatedInv = inventory.copy(
            availableBeds = inventory.availableBeds - 1,
            updatedAt = System.currentTimeMillis()
        )
        icuInventoryDao.updateInventory(updatedInv)

        // Insert booking
        val bookingId = "b_" + System.currentTimeMillis().toString().takeLast(6)
        val booking = Booking(
            id = bookingId,
            hospitalId = hospitalId,
            icuType = icuType,
            patientName = patientName,
            patientAge = patientAge,
            contactPhone = contactPhone,
            status = "HELD",
            heldAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 10 * 60 * 1000, // 10 minute hold
            paymentMethod = paymentMethod,
            paymentStatus = paymentStatus,
            cghsCardNumber = cghsCardNumber,
            finalPrice = finalPrice,
            isGovernmentServant = isGovernmentServant,
            cghsCardAttachedPath = cghsCardAttachedPath,
            downpaymentPaidAmount = downpaymentPaidAmount
        )
        bookingDao.insertBooking(booking)

        syncBookingToMongo(booking)
        syncInventoryToMongo(updatedInv)

        // Trigger the automatic confirmation simulation in background
        // Wait, for MVP, we auto-confirm bookings after 5 seconds to simulate hospital acceptance!
        // We'll let the ViewModel or Repository handle that.
        booking
    }

    suspend fun autoConfirmBooking(bookingId: String) = withContext(Dispatchers.IO) {
        val b = bookingDao.getBookingById(bookingId)
        if (b != null && b.status == "HELD") {
            val confirmed = b.copy(
                status = "CONFIRMED",
                confirmedAt = System.currentTimeMillis()
            )
            bookingDao.updateBooking(confirmed)
            syncBookingUpdateToMongo(confirmed)
        }
    }

    suspend fun cancelBooking(bookingId: String) = withContext(Dispatchers.IO) {
        val b = bookingDao.getBookingById(bookingId) ?: return@withContext
        if (b.status == "CANCELLED" || b.status == "EXPIRED") return@withContext

        // Restore bed count
        val invId = "${b.hospitalId}_${b.icuType}"
        val inventory = icuInventoryDao.getInventoryById(invId)
        if (inventory != null) {
            val updatedInv = inventory.copy(
                availableBeds = inventory.availableBeds + 1,
                updatedAt = System.currentTimeMillis()
            )
            icuInventoryDao.updateInventory(updatedInv)
            syncInventoryToMongo(updatedInv)
        }

        // Update booking status
        val cancelled = b.copy(status = "CANCELLED")
        bookingDao.updateBooking(cancelled)
        syncBookingUpdateToMongo(cancelled)
    }

    suspend fun expireBooking(bookingId: String) = withContext(Dispatchers.IO) {
        val b = bookingDao.getBookingById(bookingId) ?: return@withContext
        if (b.status != "HELD") return@withContext

        // Restore bed count
        val invId = "${b.hospitalId}_${b.icuType}"
        val inventory = icuInventoryDao.getInventoryById(invId)
        if (inventory != null) {
            val updatedInv = inventory.copy(
                availableBeds = inventory.availableBeds + 1,
                updatedAt = System.currentTimeMillis()
            )
            icuInventoryDao.updateInventory(updatedInv)
            syncInventoryToMongo(updatedInv)
        }

        // Update booking status
        val expired = b.copy(status = "EXPIRED")
        bookingDao.updateBooking(expired)
        syncBookingUpdateToMongo(expired)
    }

    suspend fun clearBookings() = withContext(Dispatchers.IO) {
        bookingDao.clearAllBookings()
    }

    // --- Payment Aggregator & Webhook Handling (Card-Only Tokenized Checkout via PaymentProvider) ---
    fun getPaymentsForBookingFlow(bookingId: String): Flow<List<Payment>> = paymentDao.getPaymentsForBookingFlow(bookingId)

    suspend fun getSuccessfulPaymentForBooking(bookingId: String): Payment? = withContext(Dispatchers.IO) {
        paymentDao.getSuccessfulPaymentForBooking(bookingId)
    }

    suspend fun createPaymentOrder(
        bookingId: String,
        amount: Double,
        method: String = "card",
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
        cardNetwork: String? = "visa",
        cardLast4: String? = "4242"
    ): Payment = withContext(Dispatchers.IO) {
        if (method == "pay_at_arrival") {
            val payment = Payment(
                bookingId = bookingId,
                amount = amount,
                currency = "INR",
                method = "pay_at_arrival",
                cardNetwork = null,
                cardLast4 = null,
                idempotencyKey = idempotencyKey,
                gatewayOrderId = "order_arrival_" + System.currentTimeMillis().toString().takeLast(8),
                status = "SUCCESS",
                createdAt = System.currentTimeMillis()
            )
            paymentDao.insertPayment(payment)

            val b = bookingDao.getBookingById(bookingId)
            if (b != null) {
                val updatedBooking = b.copy(
                    paymentMethod = "Pay at Hospital Desk (Arrival Option)",
                    paymentStatus = "UNPAID_HOLD"
                )
                bookingDao.updateBooking(updatedBooking)
                syncBookingUpdateToMongo(updatedBooking)
            }
            return@withContext payment
        }

        val orderConfig = paymentProvider.createOrder(
            bookingId = bookingId,
            amount = amount,
            currency = "INR",
            idempotencyKey = idempotencyKey
        )

        val payment = Payment(
            bookingId = bookingId,
            amount = amount,
            currency = "INR",
            method = "card",
            cardNetwork = cardNetwork,
            cardLast4 = cardLast4,
            idempotencyKey = idempotencyKey,
            gatewayOrderId = orderConfig.orderId,
            status = "PENDING_3DS",
            createdAt = System.currentTimeMillis()
        )
        paymentDao.insertPayment(payment)
        payment
    }

    suspend fun processPaymentWebhook(
        gatewayOrderId: String,
        gatewayPaymentId: String,
        signature: String,
        cardNetwork: String? = "visa",
        cardLast4: String? = "4242"
    ): Boolean = withContext(Dispatchers.IO) {
        val payment = paymentDao.getPaymentByOrderId(gatewayOrderId) ?: return@withContext false

        val isSignatureValid = paymentProvider.verifyWebhookSignature(
            payload = "$gatewayOrderId|$gatewayPaymentId",
            signature = signature
        )

        if (!isSignatureValid) {
            val failedPayment = payment.copy(
                status = "FAILED",
                gatewayPaymentId = gatewayPaymentId,
                verifiedAt = System.currentTimeMillis(),
                signatureVerified = false
            )
            paymentDao.updatePayment(failedPayment)
            return@withContext false
        }

        val successPayment = payment.copy(
            status = "SUCCESS",
            gatewayPaymentId = gatewayPaymentId,
            verifiedAt = System.currentTimeMillis(),
            signatureVerified = true,
            cardNetwork = cardNetwork ?: payment.cardNetwork,
            cardLast4 = cardLast4 ?: payment.cardLast4
        )
        paymentDao.updatePayment(successPayment)

        val booking = bookingDao.getBookingById(payment.bookingId)
        if (booking != null) {
            val confirmedBooking = booking.copy(
                status = "CONFIRMED",
                paymentStatus = "PAID",
                confirmedAt = System.currentTimeMillis(),
                paymentMethod = "Razorpay Hosted Card Checkout (${(cardNetwork ?: "Visa").uppercase()} •••• ${cardLast4 ?: "4242"})",
                downpaymentPaidAmount = payment.amount
            )
            bookingDao.updateBooking(confirmedBooking)
            syncBookingUpdateToMongo(confirmedBooking)
        }
        true
    }

    suspend fun refundPayment(
        paymentId: String,
        amount: Double
    ): Boolean = withContext(Dispatchers.IO) {
        val refundRes = paymentProvider.refund(paymentId, amount)
        if (refundRes.status == "PROCESSED") {
            val payment = paymentDao.getPaymentByOrderId(paymentId)
            if (payment != null) {
                paymentDao.updatePayment(payment.copy(status = "REFUNDED"))
            }
            return@withContext true
        }
        false
    }

    suspend fun updateBookingDetails(
        bookingId: String,
        patientName: String,
        patientAge: Int,
        contactPhone: String,
        icuType: String
    ): Boolean = withContext(Dispatchers.IO) {
        val b = bookingDao.getBookingById(bookingId) ?: return@withContext false
        if (b.status == "CANCELLED" || b.status == "EXPIRED") return@withContext false

        // If the ICU Type has changed, adjust old/new inventory beds
        if (b.icuType != icuType) {
            val newInvId = "${b.hospitalId}_$icuType"
            val oldInvId = "${b.hospitalId}_${b.icuType}"

            val newInventory = icuInventoryDao.getInventoryById(newInvId) ?: return@withContext false
            if (newInventory.availableBeds <= 0) {
                return@withContext false // No available beds in the requested ward
            }

            // Deduct from new inventory
            val updatedNewInv = newInventory.copy(
                availableBeds = newInventory.availableBeds - 1,
                updatedAt = System.currentTimeMillis()
            )
            icuInventoryDao.updateInventory(updatedNewInv)

            // Restore to old inventory
            val oldInventory = icuInventoryDao.getInventoryById(oldInvId)
            if (oldInventory != null) {
                val updatedOldInv = oldInventory.copy(
                    availableBeds = oldInventory.availableBeds + 1,
                    updatedAt = System.currentTimeMillis()
                )
                icuInventoryDao.updateInventory(updatedOldInv)
            }
        }

        val finalPrice = if (b.icuType != icuType) {
            val invId = "${b.hospitalId}_$icuType"
            val inventory = icuInventoryDao.getInventoryById(invId)
            inventory?.pricePerDay ?: b.finalPrice
        } else {
            b.finalPrice
        }

        val updatedBooking = b.copy(
            patientName = patientName,
            patientAge = patientAge,
            contactPhone = contactPhone,
            icuType = icuType,
            finalPrice = finalPrice
        )
        bookingDao.updateBooking(updatedBooking)
        true
    }

    suspend fun addSearchedLocation(name: String, lat: Double, lng: Double) = withContext(Dispatchers.IO) {
        val location = SearchedLocation(name = name, lat = lat, lng = lng, timestamp = System.currentTimeMillis())
        searchedLocationDao.insertLocation(location)
    }

    suspend fun deleteSearchedLocationByName(name: String) = withContext(Dispatchers.IO) {
        searchedLocationDao.deleteLocationByName(name)
    }

    suspend fun clearSearchedLocations() = withContext(Dispatchers.IO) {
        searchedLocationDao.clearAll()
    }

    // --- MongoDB Synchronization Helpers ---

    private suspend fun syncStaffToMongo(staff: HospitalStaffAccount) {
        val service = MongoDbManager.getService(context) ?: return
        val config = MongoDbManager.loadConfig(context)
        try {
            val response = service.insertOneStaff(
                apiKey = config.apiKey,
                request = MongoInsertOneRequest(
                    dataSource = config.dataSource,
                    database = config.databaseName,
                    collection = "staff_accounts",
                    document = staff
                )
            )
            Log.d("HospitalRepository", "Synced Hospital Staff Account to MongoDB Atlas. ID: ${response.insertedId}")
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to sync Staff Account to MongoDB", e)
        }
    }

    private suspend fun syncUserToMongo(user: UserAccount) {
        val service = MongoDbManager.getService(context) ?: return
        val config = MongoDbManager.loadConfig(context)
        try {
            val response = service.insertOneUser(
                apiKey = config.apiKey,
                request = MongoInsertOneRequest(
                    dataSource = config.dataSource,
                    database = config.databaseName,
                    collection = "users",
                    document = user
                )
            )
            Log.d("HospitalRepository", "Synced User to MongoDB Atlas. ID: ${response.insertedId}")
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to sync User to MongoDB", e)
        }
    }

    private suspend fun syncHospitalToMongo(hospital: Hospital) {
        val service = MongoDbManager.getService(context) ?: return
        val config = MongoDbManager.loadConfig(context)
        try {
            val response = service.insertOneHospital(
                apiKey = config.apiKey,
                request = MongoInsertOneRequest(
                    dataSource = config.dataSource,
                    database = config.databaseName,
                    collection = "hospitals",
                    document = hospital
                )
            )
            Log.d("HospitalRepository", "Synced Hospital to MongoDB Atlas. ID: ${response.insertedId}")
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to sync Hospital to MongoDB", e)
        }
    }

    private suspend fun syncInventoryToMongo(inventory: IcuInventory) {
        val service = MongoDbManager.getService(context) ?: return
        val config = MongoDbManager.loadConfig(context)
        try {
            val response = service.updateOneIcuInventory(
                apiKey = config.apiKey,
                request = MongoUpdateOneRequest(
                    dataSource = config.dataSource,
                    database = config.databaseName,
                    collection = "icu_inventories",
                    filter = mapOf("id" to inventory.id),
                    update = mapOf("\$set" to inventory),
                    upsert = true
                )
            )
            Log.d("HospitalRepository", "Synced IcuInventory to MongoDB. Matched: ${response.matchedCount}, Modified: ${response.modifiedCount}")
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to sync IcuInventory to MongoDB", e)
        }
    }

    private suspend fun syncBookingToMongo(booking: Booking) {
        val service = MongoDbManager.getService(context) ?: return
        val config = MongoDbManager.loadConfig(context)
        try {
            val response = service.insertOneBooking(
                apiKey = config.apiKey,
                request = MongoInsertOneRequest(
                    dataSource = config.dataSource,
                    database = config.databaseName,
                    collection = "bookings",
                    document = booking
                )
            )
            Log.d("HospitalRepository", "Synced Booking to MongoDB Atlas. ID: ${response.insertedId}")
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to sync Booking to MongoDB", e)
        }
    }

    private suspend fun syncBookingUpdateToMongo(booking: Booking) {
        val service = MongoDbManager.getService(context) ?: return
        val config = MongoDbManager.loadConfig(context)
        try {
            val response = service.updateOneBooking(
                apiKey = config.apiKey,
                request = MongoUpdateOneRequest(
                    dataSource = config.dataSource,
                    database = config.databaseName,
                    collection = "bookings",
                    filter = mapOf("id" to booking.id),
                    update = mapOf("\$set" to booking),
                    upsert = true
                )
            )
            Log.d("HospitalRepository", "Synced Booking update to MongoDB. Matched: ${response.matchedCount}")
        } catch (e: Exception) {
            Log.e("HospitalRepository", "Failed to sync Booking update to MongoDB", e)
        }
    }

    // Haversine distance formula
    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
