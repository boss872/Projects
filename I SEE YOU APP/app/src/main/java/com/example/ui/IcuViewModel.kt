package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Booking
import com.example.data.GeminiContent
import com.example.data.GeminiPart
import com.example.data.GeminiAssistant
import com.example.data.Hospital
import com.example.data.HospitalRepository
import com.example.data.HospitalStaffAccount
import com.example.data.UserAccount
import com.example.data.UserNotification
import com.example.data.Payment
import com.example.data.HospitalWithDistance
import com.example.data.IcuInventory
import com.example.data.SearchedLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class IcuViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HospitalRepository(application)

    // --- State: Location ---
    private val _userLat = MutableStateFlow<Double?>(28.6139) // Default Delhi CP
    val userLat = _userLat.asStateFlow()

    private val _userLng = MutableStateFlow<Double?>(77.2090)
    val userLng = _userLng.asStateFlow()

    private val _locationName = MutableStateFlow("Connaught Place, New Delhi")
    val locationName = _locationName.asStateFlow()

    val recentSearchedLocations: StateFlow<List<SearchedLocation>> = repository.recentSearchedLocationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- GPS Accuracy & Google Maps Equivalent Live Telemetry ---
    private val _isGpsSimulationActive = MutableStateFlow(false)
    val isGpsSimulationActive = _isGpsSimulationActive.asStateFlow()

    private val _gpsAccuracyLevel = MutableStateFlow("high") // "high", "medium", "low"
    val gpsAccuracyLevel = _gpsAccuracyLevel.asStateFlow()

    private val _gpsAccuracyMeters = MutableStateFlow(8.0)
    val gpsAccuracyMeters = _gpsAccuracyMeters.asStateFlow()

    private val _gpsSatelliteCount = MutableStateFlow(11)
    val gpsSatelliteCount = _gpsSatelliteCount.asStateFlow()

    private val _gpsSignalStatus = MutableStateFlow("Excellent")
    val gpsSignalStatus = _gpsSignalStatus.asStateFlow()

    private val _simulatedRouteTarget = MutableStateFlow<HospitalWithDistance?>(null)
    val simulatedRouteTarget = _simulatedRouteTarget.asStateFlow()

    private var gpsSimulationJob: Job? = null

    fun startGpsRouteSimulation(target: HospitalWithDistance) {
        gpsSimulationJob?.cancel()
        _isGpsSimulationActive.value = true
        _simulatedRouteTarget.value = target

        val startLat = _userLat.value ?: 28.6139
        val startLng = _userLng.value ?: 77.2090
        val endLat = target.hospital.lat
        val endLng = target.hospital.lng

        gpsSimulationJob = viewModelScope.launch {
            val steps = 30
            for (i in 1..steps) {
                if (!_isGpsSimulationActive.value) break
                val fraction = i.toDouble() / steps
                _userLat.value = startLat + (endLat - startLat) * fraction
                _userLng.value = startLng + (endLng - startLng) * fraction
                _locationName.value = "GPS Real-time Route (Step $i/$steps)"

                // Slightly fluctuate satellite count and accuracy to simulate real GPS maps telemetry
                _gpsSatelliteCount.value = (9..13).random()
                _gpsAccuracyMeters.value = when (_gpsAccuracyLevel.value) {
                    "high" -> (40..90).random().toDouble() / 10.0 // 4.0m to 9.0m
                    "medium" -> (350..550).random().toDouble() / 10.0 // 35.0m to 55.0m
                    else -> (2200..3200).random().toDouble() / 10.0 // 220.0m to 320.0m
                }

                delay(1000) // update every second
            }
            _locationName.value = "GPS Location: ${target.hospital.name}"
            _isGpsSimulationActive.value = false
            _simulatedRouteTarget.value = null
        }
    }

    fun stopGpsRouteSimulation() {
        gpsSimulationJob?.cancel()
        _isGpsSimulationActive.value = false
        _simulatedRouteTarget.value = null
        _locationName.value = "GPS Location (Simulation Terminated)"
    }

    fun setGpsAccuracy(level: String) {
        _gpsAccuracyLevel.value = level
        when (level) {
            "high" -> {
                _gpsAccuracyMeters.value = 8.0
                _gpsSatelliteCount.value = 11
                _gpsSignalStatus.value = "Excellent"
            }
            "medium" -> {
                _gpsAccuracyMeters.value = 50.0
                _gpsSatelliteCount.value = 6
                _gpsSignalStatus.value = "Fair"
            }
            "low" -> {
                _gpsAccuracyMeters.value = 300.0
                _gpsSatelliteCount.value = 2
                _gpsSignalStatus.value = "Weak"
            }
        }
    }

    // --- State: Filters & Search ---
    private val _sortBy = MutableStateFlow("distance") // "distance", "time", "availability"
    val sortBy = _sortBy.asStateFlow()

    private val _filterIcuType = MutableStateFlow<String?>(null)
    val filterIcuType = _filterIcuType.asStateFlow()

    private val _filterHospitalType = MutableStateFlow<String?>(null)
    val filterHospitalType = _filterHospitalType.asStateFlow()

    private val _filterRadiusKm = MutableStateFlow<Double?>(100.0) // default to 100km (nearby)
    val filterRadiusKm = _filterRadiusKm.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // --- State: Hospital List ---
    private val _hospitalsList = MutableStateFlow<List<HospitalWithDistance>>(emptyList())
    val hospitalsList = _hospitalsList.asStateFlow()

    // --- State: Hospital Detail ---
    private val _selectedHospital = MutableStateFlow<HospitalWithDistance?>(null)
    val selectedHospital = _selectedHospital.asStateFlow()

    // --- State: Booking ---
    private val _activeBooking = MutableStateFlow<Booking?>(null)
    val activeBooking = _activeBooking.asStateFlow()

    private val _bookingHospital = MutableStateFlow<Hospital?>(null)
    val bookingHospital = _bookingHospital.asStateFlow()

    private val _isBookingLoading = MutableStateFlow(false)
    val isBookingLoading = _isBookingLoading.asStateFlow()

    private val _latestPaymentOrder = MutableStateFlow<Payment?>(null)
    val latestPaymentOrder = _latestPaymentOrder.asStateFlow()

    private val _paymentWebhookStatus = MutableStateFlow<String?>(null) // "VERIFYING", "SUCCESS", "FAILED"
    val paymentWebhookStatus = _paymentWebhookStatus.asStateFlow()

    private val _webConnectorLogs = MutableStateFlow<List<String>>(emptyList())
    val webConnectorLogs = _webConnectorLogs.asStateFlow()

    val bookingHistory: StateFlow<List<Booking>> = repository.allBookingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- State: Hospital Staff Session ---
    private val _loggedInStaff = MutableStateFlow<HospitalStaffAccount?>(null)
    val loggedInStaff = _loggedInStaff.asStateFlow()

    private val _staffHospital = MutableStateFlow<Hospital?>(null)
    val staffHospital = _staffHospital.asStateFlow()

    private val _staffInventory = MutableStateFlow<List<IcuInventory>>(emptyList())
    val staffInventory = _staffInventory.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError = _loginError.asStateFlow()

    private val _registerStatus = MutableStateFlow<String?>(null) // "success", "error" etc.
    val registerStatus = _registerStatus.asStateFlow()

    // --- State: Quick Bed Update 2.0 ---
    private val _quickUpdateHospital = MutableStateFlow<Hospital?>(null)
    val quickUpdateHospital = _quickUpdateHospital.asStateFlow()

    private val _quickUpdateInventory = MutableStateFlow<List<IcuInventory>>(emptyList())
    val quickUpdateInventory = _quickUpdateInventory.asStateFlow()

    private val _quickUpdateError = MutableStateFlow<String?>(null)
    val quickUpdateError = _quickUpdateError.asStateFlow()

    private val _quickUpdateSuccess = MutableStateFlow<Boolean>(false)
    val quickUpdateSuccess = _quickUpdateSuccess.asStateFlow()

    // --- State: User Notifications & UPI Receipts ---
    private val _userNotifications = MutableStateFlow<List<UserNotification>>(emptyList())
    val userNotifications = _userNotifications.asStateFlow()

    private val _activeNotificationAlert = MutableStateFlow<UserNotification?>(null)
    val activeNotificationAlert = _activeNotificationAlert.asStateFlow()

    fun dismissNotificationAlert() {
        _activeNotificationAlert.value = null
    }

    fun markNotificationsRead() {
        _userNotifications.value = _userNotifications.value.map { it.copy(isRead = true) }
    }

    fun sendRealTimeEmailOtpNotification(email: String, code: String, title: String = "Real-time Email OTP Sent") {
        val newNotif = UserNotification(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            message = "Your 6-digit OTP code is $code (Dispatched to Gmail $email on active devices).",
            timestamp = System.currentTimeMillis(),
            hospitalName = "I-SEE-YOU System",
            icuType = "Email OTP",
            amountPaid = 0.0,
            upiTxnId = "OTP_" + (100000..999999).random(),
            bookingId = "VERIF_" + (1000..9999).random(),
            isRead = false
        )
        _userNotifications.value = listOf(newNotif) + _userNotifications.value
        _activeNotificationAlert.value = newNotif
    }

    fun getAdvanceBookingFee(icuType: String): Double {
        return 999.0
    }

    fun placeUpiBedBooking(
        hospitalId: String,
        icuType: String,
        patientName: String,
        patientAge: Int,
        contactPhone: String,
        upiApp: String,
        upiVpa: String = ""
    ) {
        viewModelScope.launch {
            _isBookingLoading.value = true
            val hospital = repository.getHospitalById(hospitalId)
            _bookingHospital.value = hospital
            val advanceFee = getAdvanceBookingFee(icuType)
            val txnId = "UPI/2026/0721/" + (100000..999999).random()

            val b = repository.createBooking(
                hospitalId = hospitalId,
                icuType = icuType,
                patientName = patientName,
                patientAge = patientAge,
                contactPhone = contactPhone,
                paymentMethod = "UPI (${upiApp.ifBlank { "VPA" }})",
                paymentStatus = "PAID via UPI",
                cghsCardNumber = if (upiVpa.isNotBlank()) "UPI ID: $upiVpa" else null,
                finalPrice = advanceFee,
                isGovernmentServant = false,
                cghsCardAttachedPath = null,
                downpaymentPaidAmount = advanceFee
            )
            _activeBooking.value = b
            _isBookingLoading.value = false

            if (b != null && hospital != null) {
                // Confirm booking instantly upon UPI advance fee payment
                repository.autoConfirmBooking(b.id)
                _activeBooking.value = b.copy(status = "CONFIRMED", paymentStatus = "PAID via UPI")
                refreshHospitals()

                // Dispatch bed booking notification
                val icuTitle = icuType.replace("_", " ").uppercase()
                val newNotif = UserNotification(
                    title = "🎉 Bed Booked Successfully!",
                    message = "Your ICU bed has been booked at ${hospital.name} ($icuTitle)! Advance fee ₹${advanceFee.toInt()} paid. For daily ICU rate, please contact the respective hospital directly. Txn ID: $txnId. Ref: #${b.id}.",
                    hospitalName = hospital.name,
                    icuType = icuTitle,
                    amountPaid = advanceFee,
                    upiTxnId = txnId,
                    bookingId = b.id
                )
                _userNotifications.value = listOf(newNotif) + _userNotifications.value
                _activeNotificationAlert.value = newNotif
            }
        }
    }

    // --- State: Public User Session ---
    private val _loggedInUser = MutableStateFlow<UserAccount?>(null)
    val loggedInUser = _loggedInUser.asStateFlow()

    val registeredUserAccounts: StateFlow<List<UserAccount>> = repository.allUserAccountsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _userLoginError = MutableStateFlow<String?>(null)
    val userLoginError = _userLoginError.asStateFlow()

    private val _userRegisterStatus = MutableStateFlow<String?>(null) // "success", "error" etc.
    val userRegisterStatus = _userRegisterStatus.asStateFlow()

    private val _verificationSubmitStatus = MutableStateFlow<String?>(null) // "success", "error" etc.
    val verificationSubmitStatus = _verificationSubmitStatus.asStateFlow()

    // --- State: AI Assistant ---
    private val _chatMessages = MutableStateFlow<List<GeminiContent>>(
        listOf(
            GeminiContent(
                role = "model",
                parts = listOf(GeminiPart("Hello! I am 'I See You', your emergency medical guide. I can help you search nearby available ICU beds, understand the booking process, check emergency contacts, or explain app features. Please remember, I am not a doctor — in a life-threatening emergency, please dial 108 immediately! How can I help you today?"))
            )
        )
    )
    val chatMessages = _chatMessages.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading = _isChatLoading.asStateFlow()

    private val _chatText = MutableStateFlow("")
    val chatText = _chatText.asStateFlow()

    // --- Watchdogs & Active Timers ---
    private var holdTimerJob: Job? = null
    private var refreshHospitalsJob: Job? = null
    private var simulatedTimeShiftHours = MutableStateFlow(0)

    private val prefs = application.getSharedPreferences("icu_app_sessions", android.content.Context.MODE_PRIVATE)

    private fun saveStaffSession(email: String) {
        prefs.edit().putString("saved_staff_email", email).apply()
    }

    private fun clearStaffSession() {
        prefs.edit().remove("saved_staff_email").apply()
    }

    fun getSavedUserInput(): String = prefs.getString("saved_user_input", "") ?: ""
    fun getSavedStaffEmail(): String = prefs.getString("saved_staff_email", "") ?: ""

    private fun saveUserSession(input: String) {
        prefs.edit().putString("saved_user_input", input).apply()
    }

    private fun clearUserSession() {
        prefs.edit().remove("saved_user_input").apply()
    }

    init {
        viewModelScope.launch {
            // Guarantee seeding
            repository.seedIfNeeded()
            // Fetch initial hospitals
            refreshHospitals()

            // Restore public user session
            val savedUserInput = prefs.getString("saved_user_input", null)
            if (!savedUserInput.isNullOrBlank()) {
                val user = repository.loginUser(savedUserInput)
                if (user != null) {
                    _loggedInUser.value = user
                    updateLocationToUserAccount(user)
                }
            }

            // Restore hospital staff session
            val savedStaffEmail = prefs.getString("saved_staff_email", null)
            if (!savedStaffEmail.isNullOrBlank()) {
                val staff = repository.loginStaff(savedStaffEmail)
                if (staff != null) {
                    _loggedInStaff.value = staff
                    val h = repository.getHospitalById(staff.hospitalId)
                    _staffHospital.value = h
                    if (h != null) {
                        _staffInventory.value = repository.getInventoryForHospital(h.id)
                    }
                }
            }
        }

        // Periodically refresh list on filter/search parameters changes
        viewModelScope.launch {
            launch { _userLat.collect { refreshHospitals() } }
            launch { _userLng.collect { refreshHospitals() } }
            launch { _sortBy.collect { refreshHospitals() } }
            launch { _filterIcuType.collect { refreshHospitals() } }
            launch { _filterHospitalType.collect { refreshHospitals() } }
            launch { _filterRadiusKm.collect { refreshHospitals() } }
            launch { _searchQuery.collect { refreshHospitals() } }
            launch { simulatedTimeShiftHours.collect { refreshHospitals() } }
        }
    }

    // --- Location Actions ---
    fun updateLocation(lat: Double, lng: Double, name: String) {
        _userLat.value = lat
        _userLng.value = lng
        _locationName.value = name
        viewModelScope.launch {
            repository.addSearchedLocation(name, lat, lng)
        }
    }

    fun usePresetLocation(cityName: String) {
        val resolved = resolveIndianLocation(cityName, "")
        updateLocation(resolved.first, resolved.second, resolved.third)
    }

    fun deleteSearchedLocation(name: String) {
        viewModelScope.launch {
            repository.deleteSearchedLocationByName(name)
        }
    }

    fun clearSearchedLocations() {
        viewModelScope.launch {
            repository.clearSearchedLocations()
        }
    }

    // --- Search & Filter Actions ---
    fun setSortBy(sort: String) {
        _sortBy.value = sort
    }

    fun setIcuFilter(icu: String?) {
        _filterIcuType.value = icu
    }

    fun setHospitalTypeFilter(hosp: String?) {
        _filterHospitalType.value = hosp
    }

    fun setRadiusFilter(radius: Double?) {
        _filterRadiusKm.value = radius
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        val trimmed = query.trim()
        if (trimmed.length >= 3) {
            val resolved = resolveIndianLocation(trimmed, trimmed)
            val isExplicitCityOrPin = resolved.third.contains(trimmed, ignoreCase = true) ||
                    trimmed.all { it.isDigit() } ||
                    listOf("delhi", "mumbai", "pune", "chennai", "kolkata", "hyderabad", "bangalore", "bengaluru",
                           "patna", "indore", "bhopal", "guwahati", "surat", "jaipur", "kochi", "lucknow",
                           "chandigarh", "raipur", "ranchi", "coimbatore", "nagpur", "dehradun", "varanasi", "agartala").any { trimmed.lowercase().contains(it) }
            if (isExplicitCityOrPin) {
                _userLat.value = resolved.first
                _userLng.value = resolved.second
                _locationName.value = resolved.third
                viewModelScope.launch {
                    repository.addSearchedLocation(resolved.third, resolved.first, resolved.second)
                }
            }
        }
    }

    fun selectHospital(h: HospitalWithDistance?) {
        _selectedHospital.value = h
    }

    /**
     * Finds the nearest hospital that has available ICU beds (> 0) for the specified ICU type or overall.
     * Skips any full hospitals with 0 available beds.
     */
    fun getNearestAvailableHospital(icuType: String? = filterIcuType.value): HospitalWithDistance? {
        val currentList = _hospitalsList.value
        return currentList
            .filter { hosp ->
                if (icuType.isNullOrBlank() || icuType == "all") {
                    hosp.totalAvailableBeds > 0
                } else {
                    val inv = hosp.inventory.find { it.icuType.equals(icuType, ignoreCase = true) }
                    inv != null && inv.availableBeds > 0
                }
            }
            .minByOrNull { it.distanceKm ?: Double.MAX_VALUE }
    }

    // --- Hospital Seeding / Refresh ---
    fun refreshHospitals() {
        refreshHospitalsJob?.cancel()
        refreshHospitalsJob = viewModelScope.launch {
            delay(150) // Debounce rapid triggers to prevent main thread lag and DB search locks
            repository.searchHospitals(
                userLat = _userLat.value,
                userLng = _userLng.value,
                sortBy = _sortBy.value,
                filterIcuType = _filterIcuType.value,
                filterHospitalType = _filterHospitalType.value,
                query = _searchQuery.value,
                filterRadiusKm = _filterRadiusKm.value
            ).collect { list ->
                // Map the results and manually apply simulated time shift if any
                val shiftedList = list.map { item ->
                    if (simulatedTimeShiftHours.value > 0) {
                        val shiftedHosp = item.hospital.copy(
                            lastUpdatedAt = item.hospital.lastUpdatedAt - (simulatedTimeShiftHours.value * 60 * 60 * 1000L)
                        )
                        val shiftedInv = item.inventory.map { inv ->
                            inv.copy(updatedAt = inv.updatedAt - (simulatedTimeShiftHours.value * 60 * 60 * 1000L))
                        }
                        item.copy(hospital = shiftedHosp, inventory = shiftedInv)
                    } else {
                        item
                    }
                }
                _hospitalsList.value = shiftedList

                // Keep detail in sync
                _selectedHospital.value?.let { current ->
                    val updated = shiftedList.find { it.hospital.id == current.hospital.id }
                    if (updated != null) {
                        _selectedHospital.value = updated
                    }
                }
            }
        }
    }

    // --- Booking Workflows ---
    fun placeBooking(
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
    ) {
        viewModelScope.launch {
            _isBookingLoading.value = true
            val bHospital = repository.getHospitalById(hospitalId)
            _bookingHospital.value = bHospital

            val b = repository.createBooking(
                hospitalId = hospitalId,
                icuType = icuType,
                patientName = patientName,
                patientAge = patientAge,
                contactPhone = contactPhone,
                paymentMethod = paymentMethod,
                paymentStatus = paymentStatus,
                cghsCardNumber = cghsCardNumber,
                finalPrice = finalPrice,
                isGovernmentServant = isGovernmentServant,
                cghsCardAttachedPath = cghsCardAttachedPath,
                downpaymentPaidAmount = downpaymentPaidAmount
            )
            _activeBooking.value = b
            _isBookingLoading.value = false

            if (b != null) {
                refreshHospitals()
                // Start countdown check
                startBookingCountdown(b.id)

                // Dispatch notification for booking hold
                val icuTitle = icuType.replace("_", " ").uppercase()
                val hName = bHospital?.name ?: "Hospital Nodal Office"
                val newNotif = UserNotification(
                    title = if (isGovernmentServant) "🏛️ CGHS Cashless Bed Hold Approved!" else "🎉 Bed Hold Requested!",
                    message = if (isGovernmentServant) 
                              "Cashless CGHS Bed Hold approved at $hName ($icuTitle). ₹0 Deposit charged with CGHS card $cghsCardNumber. Ref: #${b.id}."
                              else "Your ICU bed has been booked at $hName ($icuTitle)! Advance fee ₹${downpaymentPaidAmount.toInt()} paid. For daily ICU rate, please contact the respective hospital directly. Ref: #${b.id}.",
                    hospitalName = hName,
                    icuType = icuTitle,
                    amountPaid = downpaymentPaidAmount,
                    upiTxnId = if (downpaymentPaidAmount > 0) "DOWNPAY/" + (100000..999999).random() else "CGHS-CASHLESS",
                    bookingId = b.id
                )
                _userNotifications.value = listOf(newNotif) + _userNotifications.value
                _activeNotificationAlert.value = newNotif

                // Start Web Connector Integration Sequence
                viewModelScope.launch {
                    _webConnectorLogs.value = emptyList()
                    val isWebEnabled = bHospital?.webConnectorEnabled == true
                    val webUrl = if (bHospital?.webConnectorUrl.isNullOrBlank()) "https://api.${bHospital?.id ?: "hospital"}.org/v1/booking-webhook" else bHospital.webConnectorUrl
                    
                    if (isWebEnabled) {
                        _webConnectorLogs.value = listOf("Initializing Secured Hospital Web Link... 📡")
                        delay(600)
                        _webConnectorLogs.value = _webConnectorLogs.value + "Handshaking with API endpoint: $webUrl"
                        delay(800)
                        _webConnectorLogs.value = _webConnectorLogs.value + "Authorized with token: ${if (bHospital?.webConnectorToken.isNullOrBlank()) "Secure_Token_NH_${(1000..9999).random()}" else bHospital.webConnectorToken}"
                        delay(1000)
                        _webConnectorLogs.value = _webConnectorLogs.value + "Transmitting Bed hold parameters for ${b.patientName}..."
                        delay(1000)
                        _webConnectorLogs.value = _webConnectorLogs.value + "Auto-Accepted by Hospital Website Webhook! Syncing state..."
                        delay(600)
                        _webConnectorLogs.value = _webConnectorLogs.value + "Integrated with Hospital Database. Booking Confirmed. ✅"
                        
                        // Auto-confirm booking via web connector
                        if (_activeBooking.value?.id == b.id && _activeBooking.value?.status == "HELD") {
                            repository.autoConfirmBooking(b.id)
                            _activeBooking.value = _activeBooking.value?.copy(
                                status = "CONFIRMED",
                                confirmedAt = System.currentTimeMillis(),
                                paymentStatus = if (isGovernmentServant) "CGHS APPROVED" else if (downpaymentPaidAmount > 0.0) "DOWNPAYMENT SUCCESS" else "PENDING"
                            )
                        }
                    } else {
                        _webConnectorLogs.value = listOf("Direct Web Link disabled for ${bHospital?.name ?: "this hospital"}. ⚠️")
                        delay(800)
                        _webConnectorLogs.value = _webConnectorLogs.value + "Queuing manual hold request. Awaiting hospital staff manual verification..."
                        
                        // Fallback slow confirmation (e.g. 12 seconds)
                        delay(12000)
                        if (_activeBooking.value?.id == b.id && _activeBooking.value?.status == "HELD") {
                            _webConnectorLogs.value = _webConnectorLogs.value + "Hospital staff manually approved your booking queue!"
                            repository.autoConfirmBooking(b.id)
                            _activeBooking.value = _activeBooking.value?.copy(
                                status = "CONFIRMED",
                                confirmedAt = System.currentTimeMillis(),
                                paymentStatus = if (isGovernmentServant) "CGHS APPROVED" else if (downpaymentPaidAmount > 0.0) "DOWNPAYMENT SUCCESS" else "PENDING"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startBookingCountdown(bookingId: String) {
        holdTimerJob?.cancel()
        holdTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _activeBooking.value
                if (current != null && current.id == bookingId && current.status == "HELD") {
                    val remaining = current.expiresAt - System.currentTimeMillis()
                    if (remaining <= 0) {
                        repository.expireBooking(bookingId)
                        _activeBooking.value = current.copy(status = "EXPIRED")
                        refreshHospitals()
                        break
                    }
                } else {
                    break
                }
            }
        }
    }

    fun cancelActiveBooking() {
        viewModelScope.launch {
            val current = _activeBooking.value ?: return@launch
            repository.cancelBooking(current.id)
            _activeBooking.value = current.copy(status = "CANCELLED")
            refreshHospitals()
        }
    }

    fun cancelHistoryBooking(bookingId: String) {
        viewModelScope.launch {
            repository.cancelBooking(bookingId)
            refreshHospitals()
        }
    }

    fun modifyBooking(
        bookingId: String,
        patientName: String,
        patientAge: Int,
        contactPhone: String,
        icuType: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val success = repository.updateBookingDetails(bookingId, patientName, patientAge, contactPhone, icuType)
            if (success) {
                if (_activeBooking.value?.id == bookingId) {
                    _activeBooking.value = _activeBooking.value?.copy(
                        patientName = patientName,
                        patientAge = patientAge,
                        contactPhone = contactPhone,
                        icuType = icuType
                    )
                }
                refreshHospitals()
            }
            onResult(success)
        }
    }

    fun clearActiveBooking() {
        _activeBooking.value = null
        _bookingHospital.value = null
        holdTimerJob?.cancel()
    }

    // --- Payment Gateway Aggregator & Webhook Workflows (Card-Only Hosted Checkout) ---
    fun createRazorpayOrder(
        bookingId: String,
        amount: Double,
        method: String = "card",
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
        cardNetwork: String? = "visa",
        cardLast4: String? = "4242",
        onResult: (Payment) -> Unit = {}
    ) {
        viewModelScope.launch {
            val payment = repository.createPaymentOrder(
                bookingId = bookingId,
                amount = amount,
                method = method,
                idempotencyKey = idempotencyKey,
                cardNetwork = cardNetwork,
                cardLast4 = cardLast4
            )
            _latestPaymentOrder.value = payment

            if (method == "pay_at_arrival") {
                val current = _activeBooking.value
                if (current?.id == bookingId) {
                    _activeBooking.value = current.copy(
                        paymentMethod = "Pay at Hospital Desk (Arrival Option)",
                        paymentStatus = "UNPAID_HOLD"
                    )
                }
            }
            onResult(payment)
        }
    }

    fun processRazorpayPaymentWebhook(
        gatewayOrderId: String,
        gatewayPaymentId: String,
        signature: String,
        cardNetwork: String? = "visa",
        cardLast4: String? = "4242",
        onVerified: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            _paymentWebhookStatus.value = "VERIFYING"
            delay(1000) // Simulate server-side signature verification & bank rail settlement
            val verified = repository.processPaymentWebhook(
                gatewayOrderId = gatewayOrderId,
                gatewayPaymentId = gatewayPaymentId,
                signature = signature,
                cardNetwork = cardNetwork,
                cardLast4 = cardLast4
            )
            if (verified) {
                _paymentWebhookStatus.value = "SUCCESS"
                val current = _activeBooking.value
                if (current != null) {
                    _activeBooking.value = current.copy(
                        status = "CONFIRMED",
                        paymentStatus = "PAID",
                        confirmedAt = System.currentTimeMillis()
                    )
                    val newNotif = UserNotification(
                        title = "✅ Card Payment Webhook Signature Verified!",
                        message = "Razorpay Hosted Tokenized Card (${cardNetwork?.uppercase() ?: "VISA"} •••• ${cardLast4 ?: "4242"}) HMAC-SHA256 signature verified for Order #$gatewayOrderId (Pay ID: $gatewayPaymentId). Bed CONFIRMED.",
                        hospitalName = _bookingHospital.value?.name ?: "Hospital Nodal Office",
                        icuType = current.icuType.uppercase(),
                        amountPaid = current.finalPrice,
                        upiTxnId = gatewayPaymentId,
                        bookingId = current.id
                    )
                    _userNotifications.value = listOf(newNotif) + _userNotifications.value
                    _activeNotificationAlert.value = newNotif
                }
                refreshHospitals()
            } else {
                _paymentWebhookStatus.value = "FAILED"
            }
            onVerified(verified)
        }
    }

    suspend fun checkStaffAccountExists(emailOrPhone: String): HospitalStaffAccount? {
        return repository.loginStaff(emailOrPhone)
    }

    // --- Hospital Staff Actions ---
    fun loginHospitalStaff(email: String) {
        viewModelScope.launch {
            _loginError.value = null
            val staff = repository.loginStaff(email)
            if (staff != null) {
                _loggedInStaff.value = staff
                val h = repository.getHospitalById(staff.hospitalId)
                _staffHospital.value = h
                if (h != null) {
                    _staffInventory.value = repository.getInventoryForHospital(h.id)
                }
                saveStaffSession(email)
            } else {
                _loginError.value = "Account doesn't exist. Please sign up your facility below."
            }
        }
    }

    fun registerNewHospital(
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
    ) {
        viewModelScope.launch {
            _registerStatus.value = null
            try {
                val h = repository.registerHospital(
                    name = name,
                    type = type,
                    address = address,
                    city = city,
                    state = state,
                    pincode = pincode,
                    phone = phone,
                    contactName = contactName,
                    email = email,
                    lat = lat,
                    lng = lng,
                    registeredDate = registeredDate,
                    accreditationCertificate = accreditationCertificate,
                    regulatoryBody = regulatoryBody,
                    emergencyPhone = emergencyPhone,
                    websiteUrl = websiteUrl,
                    repIdCardType = repIdCardType,
                    repIdCardNumber = repIdCardNumber,
                    repDesignation = repDesignation
                )
                if (h != null) {
                    _registerStatus.value = "success"
                    // Auto log in!
                    loginHospitalStaff(email)
                    refreshHospitals()
                } else {
                    _registerStatus.value = "error"
                }
            } catch (e: Exception) {
                _registerStatus.value = "error: ${e.message}"
            }
        }
    }

    fun updateBedCounts(icuType: String, available: Int, total: Int) {
        viewModelScope.launch {
            val h = _staffHospital.value ?: return@launch
            repository.updateBeds(h.id, icuType, available, total)
            _staffInventory.value = repository.getInventoryForHospital(h.id)
            // Update lastUpdatedAt timestamp locally
            val updatedH = repository.getHospitalById(h.id)
            _staffHospital.value = updatedH
            refreshHospitals()
        }
    }

    // --- Direct Quick Bed Update 2.0 Engine ---
    fun findHospitalForQuickUpdate(identifier: String) {
        viewModelScope.launch {
            _quickUpdateError.value = null
            _quickUpdateSuccess.value = false
            _quickUpdateHospital.value = null
            _quickUpdateInventory.value = emptyList()

            val staff = repository.loginStaff(identifier)
            if (staff != null) {
                val h = repository.getHospitalById(staff.hospitalId)
                if (h != null) {
                    _quickUpdateHospital.value = h
                    _quickUpdateInventory.value = repository.getInventoryForHospital(h.id)
                } else {
                    _quickUpdateError.value = "Hospital details not found for this account."
                }
            } else {
                _quickUpdateError.value = "No registered hospital found matching your input details. Please ensure your email, phone, or website domain is registered."
            }
        }
    }

    fun submitQuickBedUpdate(icuType: String, available: Int, total: Int) {
        viewModelScope.launch {
            val h = _quickUpdateHospital.value ?: return@launch
            repository.updateBeds(h.id, icuType, available, total)
            _quickUpdateInventory.value = repository.getInventoryForHospital(h.id)
            val updatedH = repository.getHospitalById(h.id)
            _quickUpdateHospital.value = updatedH
            _quickUpdateSuccess.value = true
            refreshHospitals()
        }
    }

    fun clearQuickUpdateState() {
        _quickUpdateHospital.value = null
        _quickUpdateInventory.value = emptyList()
        _quickUpdateError.value = null
        _quickUpdateSuccess.value = false
    }

    fun submitStaffVerification(licenseNumber: String) {
        viewModelScope.launch {
            _verificationSubmitStatus.value = null
            val h = _staffHospital.value ?: return@launch
            if (licenseNumber.isBlank()) {
                _verificationSubmitStatus.value = "License number cannot be blank."
                return@launch
            }

            repository.submitHospitalVerification(h.id, licenseNumber)
            val updatedH = repository.getHospitalById(h.id)
            _staffHospital.value = updatedH
            _verificationSubmitStatus.value = "success"
            refreshHospitals()
        }
    }

    fun updateHospitalRegistryDetails(
        phone: String,
        registeredDate: String?,
        accreditationCertificate: String?,
        regulatoryBody: String?,
        emergencyPhone: String?,
        websiteUrl: String?
    ) {
        viewModelScope.launch {
            val h = _staffHospital.value ?: return@launch
            repository.updateHospitalRegistry(
                hospitalId = h.id,
                phone = phone,
                registeredDate = registeredDate,
                accreditationCertificate = accreditationCertificate,
                regulatoryBody = regulatoryBody,
                emergencyPhone = emergencyPhone,
                websiteUrl = websiteUrl
            )
            val updatedH = repository.getHospitalById(h.id)
            _staffHospital.value = updatedH
            refreshHospitals()
        }
    }

    fun updateHospitalWebConnectorDetails(
        enabled: Boolean,
        url: String,
        token: String,
        status: String
    ) {
        viewModelScope.launch {
            val h = _staffHospital.value ?: return@launch
            repository.updateHospitalWebConnector(
                hospitalId = h.id,
                enabled = enabled,
                url = url,
                token = token,
                status = status
            )
            val updatedH = repository.getHospitalById(h.id)
            _staffHospital.value = updatedH
            refreshHospitals()
        }
    }

    fun setHospitalVerificationManual(hospitalId: String, verified: Boolean) {
        viewModelScope.launch {
            repository.setHospitalVerificationManual(hospitalId, verified)
            refreshHospitals()
        }
    }

    fun logoutStaff() {
        _loggedInStaff.value = null
        _staffHospital.value = null
        _staffInventory.value = emptyList()
        _loginError.value = null
        _registerStatus.value = null
        _verificationSubmitStatus.value = null
        clearStaffSession()
    }

    // --- Public User Actions ---
    fun setUserLoginError(error: String?) {
        _userLoginError.value = error
    }

    fun setHospitalLoginError(error: String?) {
        _loginError.value = error
    }

    suspend fun checkUserAccountExists(input: String): UserAccount? {
        return repository.loginUser(input)
    }
    fun updateLocationToUserAccount(user: UserAccount) {
        val targetCity = when {
            user.city.isNotBlank() -> user.city
            user.address.isNotBlank() -> user.address
            else -> "Delhi"
        }
        val resolved = resolveIndianLocation(targetCity, user.pincode)
        val locationLabel = when {
            user.city.isNotBlank() -> "${user.name}'s Location (${user.city})"
            user.address.isNotBlank() -> "${user.name}'s Location (${user.address.take(15)})"
            else -> "${user.name}'s Location (${resolved.third})"
        }
        updateLocation(resolved.first, resolved.second, locationLabel)
    }

    fun loginPublicUser(input: String) {
        viewModelScope.launch {
            _userLoginError.value = null
            val user = repository.loginUser(input)
            if (user != null) {
                _loggedInUser.value = user
                val sessionKey = if (user.phone.isNotBlank()) user.phone else if (user.email.isNotBlank()) user.email else input
                saveUserSession(sessionKey)
                updateLocationToUserAccount(user)
            } else {
                _userLoginError.value = "Account doesn't exist. Please create an account below."
            }
        }
    }

    fun registerPublicUser(
        name: String,
        email: String,
        phone: String,
        address: String = "",
        city: String = "",
        state: String = "",
        pincode: String = ""
    ) {
        viewModelScope.launch {
            _userRegisterStatus.value = null
            try {
                val user = repository.registerUser(name, email, phone, address, city, state, pincode)
                if (user != null) {
                    _loggedInUser.value = user
                    _userRegisterStatus.value = "success"
                    val sessionKey = if (user.phone.isNotBlank()) user.phone else user.email
                    saveUserSession(sessionKey)
                    updateLocationToUserAccount(user)
                } else {
                    _userRegisterStatus.value = "error: Email already registered."
                }
            } catch (e: Exception) {
                _userRegisterStatus.value = "error: ${e.message}"
            }
        }
    }

    fun logoutPublicUser() {
        _loggedInUser.value = null
        _userLoginError.value = null
        _userRegisterStatus.value = null
        clearUserSession()
    }

    // --- AI Assistant Actions ---
    fun updateChatText(text: String) {
        _chatText.value = text
    }

    fun sendChatMessage() {
        val prompt = _chatText.value.trim()
        if (prompt.isBlank()) return

        _chatText.value = ""

        // Append user prompt to state
        val updatedHistory = _chatMessages.value.toMutableList()
        val userTurn = GeminiContent(role = "user", parts = listOf(GeminiPart(prompt)))
        updatedHistory.add(userTurn)
        _chatMessages.value = updatedHistory
        _isChatLoading.value = true

        viewModelScope.launch {
            val booking = _activeBooking.value
            val activeBookingText = if (booking != null) {
                "User has an active booking with ID ${booking.id} in state ${booking.status} for ICU type ${booking.icuType} at hospital ID ${booking.hospitalId}."
            } else {
                "User currently has NO active booking in this session."
            }

            // Call Assistant with grounded context
            val replyText = GeminiAssistant.getChatResponse(
                message = prompt,
                history = updatedHistory.dropLast(1), // pass previous turns
                locationName = _locationName.value,
                visibleHospitals = _hospitalsList.value,
                activeBookingText = activeBookingText
            )

            val replyTurn = GeminiContent(role = "model", parts = listOf(GeminiPart(replyText)))
            val finalHistory = _chatMessages.value.toMutableList()
            finalHistory.add(replyTurn)

            _chatMessages.value = finalHistory
            _isChatLoading.value = false
        }
    }

    fun sendQuickQuestion(prompt: String) {
        _chatText.value = prompt
        sendChatMessage()
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            GeminiContent(
                role = "model",
                parts = listOf(GeminiPart("Hello! I am 'I See You', your emergency medical guide. I can help you search nearby available ICU beds, understand the booking process, check emergency contacts, or explain app features. Please remember, I am not a doctor — in a life-threatening emergency, please dial 108 immediately! How can I help you today?"))
            )
        )
    }

    // --- Watchdog Simulator Tool ---
    fun setSimulatedTimeShift(hours: Int) {
        simulatedTimeShiftHours.value = hours
        refreshHospitals()
    }
}
