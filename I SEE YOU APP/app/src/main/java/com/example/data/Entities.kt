package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hospitals")
data class Hospital(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // "government", "private", "trust"
    val address: String,
    val city: String,
    val state: String,
    val pincode: String,
    val lat: Double,
    val lng: Double,
    val phone: String,
    val verified: Boolean = false,
    val licenseNumber: String? = null,
    val verificationStatus: String = "unverified", // "unverified", "pending", "verified"
    val verificationSubmittedAt: Long? = null,
    val dataSource: String = "mock",
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val registeredDate: String? = null,
    val accreditationCertificate: String? = null,
    val regulatoryBody: String? = null,
    val emergencyPhone: String? = null,
    val websiteUrl: String? = null,
    val webConnectorEnabled: Boolean = true,
    val webConnectorUrl: String = "",
    val webConnectorToken: String = "",
    val webConnectorStatus: String = "CONNECTED" // "CONNECTED", "DISCONNECTED", "SYNC_ERROR"
)

@Entity(tableName = "icu_inventory")
data class IcuInventory(
    @PrimaryKey val id: String, // combination of hospitalId + "_" + icuType
    val hospitalId: String,
    val icuType: String, // "general", "cardiac", "neonatal", "pediatric", "post_op", "isolation"
    val totalBeds: Int,
    val availableBeds: Int,
    val pricePerDay: Double?,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookings")
data class Booking(
    @PrimaryKey val id: String,
    val hospitalId: String,
    val icuType: String,
    val patientName: String,
    val patientAge: Int,
    val contactPhone: String,
    val status: String = "HELD", // "HELD", "CONFIRMED", "CANCELLED", "EXPIRED"
    val heldAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 10 * 60 * 1000, // 10 minutes from heldAt
    val confirmedAt: Long? = null,
    val paymentMethod: String = "Pay at Desk",
    val paymentStatus: String = "PENDING",
    val cghsCardNumber: String? = null,
    val finalPrice: Double = 0.0,
    val isGovernmentServant: Boolean = false,
    val cghsCardAttachedPath: String? = null,
    val downpaymentPaidAmount: Double = 0.0
)

@Entity(tableName = "hospital_accounts")
data class HospitalStaffAccount(
    @PrimaryKey val id: String,
    val hospitalId: String,
    val contactName: String,
    val email: String,
    val phone: String,
    val role: String = "hospital_staff",
    val createdAt: Long = System.currentTimeMillis(),
    val repIdCardType: String? = "Hospital Employee ID Badge",
    val repIdCardNumber: String? = null,
    val repDesignation: String? = null,
    val repIdVerified: Boolean = true
)

@Entity(tableName = "user_accounts")
data class UserAccount(
    @PrimaryKey val email: String,
    val name: String,
    val phone: String,
    val address: String = "",
    val city: String = "",
    val state: String = "",
    val pincode: String = "",
    val role: String = "public_user",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "searched_locations")
data class SearchedLocation(
    @PrimaryKey val name: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserNotification(
    val id: String = "notif_" + System.currentTimeMillis(),
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hospitalName: String,
    val icuType: String,
    val amountPaid: Double,
    val upiTxnId: String,
    val bookingId: String,
    val isRead: Boolean = false
)

@Entity(tableName = "payments")
data class Payment(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val bookingId: String,
    val amount: Double,
    val currency: String = "INR",
    val method: String = "card", // "card" or "pay_at_arrival"
    val cardNetwork: String? = "visa", // "visa", "mastercard", "rupay", "amex"
    val cardLast4: String? = null, // last 4 digits only for receipts
    val idempotencyKey: String = java.util.UUID.randomUUID().toString(), // prevents double-charge on retry
    val gatewayOrderId: String? = null,
    val gatewayPaymentId: String? = null,
    val status: String = "CREATED", // "CREATED", "PENDING_3DS", "SUCCESS", "FAILED", "REFUNDED"
    val createdAt: Long = System.currentTimeMillis(),
    val verifiedAt: Long? = null,
    val signatureVerified: Boolean = false
)

